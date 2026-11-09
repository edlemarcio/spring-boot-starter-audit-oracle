"""Regression tests for evidence and false-green prevention; no Oracle is required."""
from pathlib import Path
import argparse
import csv
import importlib.util
import json
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from audit_concurrency import Runner, classify, junit_results, production_fingerprint, write_reports, missing_required_cases, REQUIRED_COUNTS
from audit_mutations import definitions, mutation_assertions


class RunnerTests(unittest.TestCase):
    def test_listener_failures_are_infrastructure_not_an_author_regression(self):
        case = {'status': 'failed', 'error': 'java.sql.SQLException: ORA-12516: no handler available'}
        self.assertEqual('infrastructure_failure', classify(1, [case]))
        case['error'] = 'java.lang.AssertionError: wrong author'
        self.assertEqual('tests_failed', classify(1, [case]))

    def test_partial_test_discovery_does_not_satisfy_the_required_catalog(self):
        cases = [{'scenario': 'package.OracleConcurrencyTest.onlyOneMethod', 'status': 'passed'}]
        missing = missing_required_cases('jdbc-jpa', cases)
        self.assertEqual(1, missing['OracleConcurrencyTest']['actual'])
        self.assertEqual(0, missing['OracleJpaConcurrencyTest']['actual'])

    def test_the_complete_catalog_and_mutation_probes_are_distinguished(self):
        cases = [{'scenario': 'package.' + name + '.method' + str(i), 'status': 'passed'}
                 for name, count in REQUIRED_COUNTS['jdbc-jpa'].items() for i in range(count)]
        self.assertEqual({}, missing_required_cases('jdbc-jpa', cases))
        self.assertEqual({}, missing_required_cases('mutant-example', []))

    def test_empty_test_selection_never_passes(self):
        self.assertEqual('infrastructure_failure', classify(0, []))

    def test_skipped_oracle_case_is_incomplete(self):
        self.assertEqual('incomplete', classify(0, [{'status': 'skipped'}]))

    def test_timeout_is_not_success_even_with_partial_green_results(self):
        self.assertEqual('infrastructure_timeout', classify(124, [{'status': 'passed'}], True))

    def test_nonzero_exit_with_green_tests_is_an_infrastructure_failure(self):
        self.assertEqual('infrastructure_failure', classify(1, [{'status': 'passed'}]))

    def test_assertion_failure_remains_red(self):
        self.assertEqual('tests_failed', classify(1, [{'status': 'failed'}]))

    def test_xml_with_empty_failure_element_is_still_failed(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, 'TEST-example.xml').write_text('<testsuite><testcase classname="C" name="t"><failure type="java.lang.AssertionError" message="wrong author"/></testcase></testsuite>')
            self.assertEqual('failed', junit_results(folder)[0]['status'])

    def test_reports_escape_html_and_preserve_csv_json_content(self):
        with tempfile.TemporaryDirectory() as folder:
            payload = '<script>alert("x")</script>\nnext,row'
            write_reports(folder, {'status': payload}, [{'scenario': 'case', 'status': 'failed', 'error': payload}])
            self.assertNotIn('<script>', Path(folder, 'report.html').read_text())
            self.assertEqual(payload, json.loads(Path(folder, 'summary.json').read_text())['status'])
            with Path(folder, 'scenarios.csv').open() as handle:
                self.assertEqual(payload, list(csv.DictReader(handle))[0]['error'])

    def test_compile_and_connection_errors_do_not_kill_a_mutant(self):
        case = {'status': 'failed', 'scenario': 'C.t', 'errorType': 'java.sql.SQLException'}
        self.assertEqual([], mutation_assertions('tests_failed', [case], {'C.t'}))
        case['errorType'] = 'java.lang.AssertionError'
        self.assertEqual([], mutation_assertions('infrastructure_failure', [case], {'C.t'}))

    def test_a_mutant_requires_an_assertion_from_the_green_baseline(self):
        case = {'status': 'failed', 'scenario': 'C.t', 'errorType': 'java.lang.AssertionError'}
        self.assertEqual([], mutation_assertions('tests_failed', [case], {'other.t'}))
        self.assertEqual([case], mutation_assertions('tests_failed', [case], {'C.t'}))

    def test_mutation_anchors_are_unique_and_do_not_edit_the_original(self):
        source = SCRIPTS.parent / 'audit-oracle-spring-boot-autoconfigure/src/main/java/io/github/edlemarcio/audit/oracle/autoconfigure/jdbc/AuditConnection.java'
        original = source.read_bytes()
        for name, old, new, _ in definitions():
            self.assertEqual(1, original.decode().count(old), name)
            self.assertNotEqual(old, new)
        self.assertEqual(original, source.read_bytes())

    def test_test_source_changes_do_not_change_the_production_fingerprint(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / 'module/src/main/java/A.java'
            source.parent.mkdir(parents=True)
            source.write_text('class A {}')
            tests = root / 'module/src/test/java/ATest.java'
            tests.parent.mkdir(parents=True)
            tests.write_text('class ATest {}')
            original = production_fingerprint(root)
            tests.write_text('changed test')
            self.assertEqual(original, production_fingerprint(root))
            source.write_text('changed production')
            self.assertNotEqual(original, production_fingerprint(root))

    def test_oracle19_is_not_replaced_by_free23_when_configuration_is_missing(self):
        with tempfile.TemporaryDirectory() as folder:
            args = argparse.Namespace(report_dir=None, expected_major=19, phase='complete', stress_seconds=900,
                                      seeds='1', java_home=None, maven='/fake/mvn')
            runner = Runner(args, Path(folder))
            runner.env = {}
            with patch.object(runner, 'command', side_effect=['revision', 'openjdk version "21.0.9"']), patch.object(runner, 'start_free') as start:
                with self.assertRaisesRegex(RuntimeError, 'Missing dedicated Oracle'):
                    runner.prepare()
                start.assert_not_called()

    def test_no_empty_run_can_be_approved(self):
        with tempfile.TemporaryDirectory() as folder:
            args = argparse.Namespace(report_dir=None, expected_major=23, phase='complete', stress_seconds=900, seeds='1')
            runner = Runner(args, Path(folder))
            runner.finish(RuntimeError('No tests executed'))
            report = json.loads((runner.directory / 'summary.json').read_text())
            self.assertFalse(report['complete_battery_passed'])
            self.assertNotEqual('approved', report['overall_validation'])

    def test_missing_stages_fail_even_without_an_exception(self):
        with tempfile.TemporaryDirectory() as folder:
            args = argparse.Namespace(report_dir=None, expected_major=23, phase='complete', stress_seconds=900, seeds='1')
            runner = Runner(args, Path(folder))
            runner.finish()
            self.assertEqual('failed', runner.manifest['status'])
            self.assertIn('jdbc-jpa-stress', runner.manifest['missing_stages'])

    def test_changing_code_during_a_run_invalidates_the_result(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            args = argparse.Namespace(report_dir=None, expected_major=23, phase='deterministic', stress_seconds=900, seeds='1')
            runner = Runner(args, root)
            (root / 'pom.xml').write_text('changed dependencies')
            runner.finish()
            self.assertTrue(runner.manifest['source_changed_during_run'])


if __name__ == '__main__':
    unittest.main()
