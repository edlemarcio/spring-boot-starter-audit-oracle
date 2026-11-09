#!/usr/bin/env python3
"""Manual Oracle test orchestration. Standard library only; never edits production sources."""
import argparse
import csv
import fcntl
import hashlib
import html
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
AUTO = 'audit-oracle-spring-boot-autoconfigure'
EXAMPLE = 'audit-oracle-example'
SOURCE = Path(AUTO) / 'src/main/java/io/github/edlemarcio/audit/oracle/autoconfigure/jdbc/AuditConnection.java'
REQUIRED_COUNTS = {
    'jdbc-jpa': {'OracleConcurrencyTest': 21, 'OracleTransactionConcurrencyTest': 25,
                 'OracleJpaConcurrencyTest': 21, 'OracleLifecycleFaultTest': 32},
    'http-security': {'OracleHttpConcurrencyTest': 6},
    'jdbc-jpa-stress': {'OracleStressTest': 1},
    'http-stress': {'OracleHttpStressTest': 1},
}


def missing_required_cases(stage, cases):
    from collections import Counter
    actual = Counter(case['scenario'].rsplit('.', 1)[0].rsplit('.', 1)[-1] for case in cases)
    return {name: {'expected': count, 'actual': actual[name]}
            for name, count in REQUIRED_COUNTS.get(stage, {}).items() if actual[name] != count}


def junit_results(directory):
    cases = []
    for path in sorted(Path(directory).rglob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        for case in root.findall('testcase'):
            problem = case.find('failure')
            if problem is None:
                problem = case.find('error')
            status = 'failed' if problem is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            cases.append({'scenario': case.get('classname', '') + '.' + case.get('name', ''),
                          'status': status, 'seconds': float(case.get('time', 0)),
                          'error': '' if problem is None else problem.get('message', ''),
                          'errorType': '' if problem is None else problem.get('type', '')})
    return cases


def production_fingerprint(root):
    digest = hashlib.sha256()
    paths = [p for p in root.rglob('*.java') if 'src/main/java/' in p.as_posix() and 'target' not in p.parts]
    paths += [p for p in root.rglob('pom.xml') if 'target' not in p.parts]
    for path in sorted(paths):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def test_fingerprint(root):
    digest = hashlib.sha256()
    paths = [p for p in root.rglob('*.java') if '/src/test/' in p.as_posix() and 'target' not in p.parts]
    paths += [p for p in (root / 'scripts').rglob('*') if p.suffix in ('.py', '.sh') and '__pycache__' not in p.parts]
    for path in sorted(paths):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def write_reports(directory, manifest, cases):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'summary.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    fields = ['scenario', 'status', 'seconds', 'errorType', 'error']
    with (directory / 'scenarios.csv').open('w', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows({key: case.get(key, '') for key in fields} for case in cases)
    rows = ''.join('<tr>' + ''.join('<td>' + html.escape(str(case.get(key, ''))) + '</td>' for key in fields) + '</tr>' for case in cases)
    body = '<!doctype html><html lang="pt-BR"><meta charset="utf-8"><title>Auditoria Oracle — concorrência</title>'
    body += '<style>body{font:14px system-ui;margin:2rem}td,th{border:1px solid #ccc;padding:.5rem;vertical-align:top}table{border-collapse:collapse}pre{white-space:pre-wrap}</style>'
    body += '<h1>Validação de concorrência Oracle</h1><pre>' + html.escape(json.dumps(manifest, indent=2, ensure_ascii=False)) + '</pre>'
    body += '<table><thead><tr>' + ''.join('<th>' + key + '</th>' for key in fields) + '</tr></thead><tbody>' + rows + '</tbody></table></html>'
    (directory / 'report.html').write_text(body)


def classify(returncode, cases, timed_out=False):
    if timed_out:
        return 'infrastructure_timeout'
    if not cases:
        return 'infrastructure_failure'
    if any(case['status'] == 'skipped' for case in cases):
        return 'incomplete'
    if any(any(code in case.get('error', '') for code in ('ORA-12516', 'ORA-12520', 'ORA-12514', 'ORA-12541', 'ORA-01017', 'ORA-28000'))
           for case in cases if case['status'] == 'failed'):
        return 'infrastructure_failure'
    if any(case['status'] == 'failed' for case in cases):
        return 'tests_failed'
    return 'passed' if returncode == 0 else 'infrastructure_failure'


class Runner:
    def __init__(self, args, root=ROOT):
        self.args, self.root = args, root
        stamp = time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
        self.directory = Path(args.report_dir).resolve() if args.report_dir else root / 'target/audit-concurrency' / f'oracle-{args.expected_major}-{stamp}'
        self.directory.mkdir(parents=True, exist_ok=True)
        self.env = dict(os.environ)
        self.stages, self.cases = [], []
        self.manifest = {'expected_oracle_major': args.expected_major, 'phase': args.phase,
                         'production_fingerprint': production_fingerprint(root), 'test_fingerprint': test_fingerprint(root), 'started': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
                         'stress_seconds': args.stress_seconds, 'seeds': args.seeds,
                         'status': 'running', 'overall_validation': 'incomplete', 'stages': self.stages}
        self.started = time.monotonic()
        self.container = None
        self.temp = None
        self.phase_deadline = None

    def command(self, *parts):
        return subprocess.check_output(list(parts), text=True, stderr=subprocess.STDOUT).strip()

    def prepare(self):
        self.manifest['git_revision'] = self.command('git', '-C', str(self.root), 'rev-parse', 'HEAD')
        if self.args.java_home:
            self.env['JAVA_HOME'] = str(Path(self.args.java_home).resolve())
        java = str(Path(self.env['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in self.env else shutil.which('java')
        self.manifest['java'] = self.command(java, '-version')
        if 'version "21' not in self.manifest['java']:
            raise RuntimeError('The manual battery requires Java 21; set JAVA_HOME or --java-home')
        self.maven = self.args.maven or shutil.which('mvn') or (str(self.root / 'mvnw') if (self.root / 'mvnw').exists() else None)
        if not self.maven:
            raise RuntimeError('Maven not found; install Maven or supply --maven /path/to/mvn')
        if not self.env.get('AUDIT_ORACLE_URL') and self.args.expected_major == 23:
            self.start_free()
        missing = [key for key in ('AUDIT_ORACLE_URL', 'AUDIT_ORACLE_USER', 'AUDIT_ORACLE_PASSWORD') if not self.env.get(key)]
        if missing:
            raise RuntimeError('Missing dedicated Oracle test configuration: ' + ', '.join(missing))
        if self.env['AUDIT_ORACLE_USER'].upper() in ('SYS', 'SYSTEM'):
            raise RuntimeError('Use a dedicated non-administrative test schema')
        self.manifest['endpoint'] = self.env['AUDIT_ORACLE_URL']

    def start_free(self):
        self.temp = tempfile.TemporaryDirectory(prefix='audit-concurrency-')
        self.container = 'audit-concurrency-' + uuid.uuid4().hex[:10]
        password = secrets.token_urlsafe(24)
        env_file = Path(self.temp.name) / 'oracle.env'
        env_file.write_text('ORACLE_PASSWORD=' + secrets.token_urlsafe(24) + '\nAPP_USER=AUDIT_CONCURRENCY\nAPP_USER_PASSWORD=' + password + '\n')
        env_file.chmod(0o600)
        self.command('docker', 'run', '-d', '--name', self.container, '-p', '127.0.0.1::1521', '--shm-size=1g',
                     '--env-file', str(env_file), 'gvenzl/oracle-free:23-slim')
        deadline = time.monotonic() + 600
        while True:
            health = subprocess.run(['docker', 'exec', self.container, 'healthcheck.sh'], capture_output=True)
            if health.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError('Oracle Free did not become ready within 10 minutes')
            time.sleep(2)
        port = self.command('docker', 'port', self.container, '1521/tcp').rsplit(':', 1)[1]
        self.env.update(AUDIT_ORACLE_URL='jdbc:oracle:thin:@//127.0.0.1:' + port + '/FREEPDB1',
                        AUDIT_ORACLE_USER='AUDIT_CONCURRENCY', AUDIT_ORACLE_PASSWORD=password)
        # Provisioning does not consume the agreed workload budget.
        self.started = time.monotonic()

    def maven_stage(self, name, arguments, modules, timeout, root=None, require_cases=True):
        root = root or self.root
        stage_dir = self.directory / 'junit' / name
        stage_dir.mkdir(parents=True, exist_ok=True)
        for module in modules:
            shutil.rmtree(root / module / 'target/surefire-reports', ignore_errors=True)
        command = [self.maven, '-B', '-ntp', '-Djunit.jupiter.execution.parallel.enabled=false', f'-Daudit.expectedOracleMajor={self.args.expected_major}',
                   '-Daudit.report.dir=' + str(self.directory / ('mutation-evidence/' + name if name.startswith(('mutant-', 'mutation-baseline-')) else 'evidence')), *arguments]
        remaining = min(timeout, 1800 - (time.monotonic() - self.started))
        if self.phase_deadline is not None:
            remaining = min(remaining, self.phase_deadline - time.monotonic())
        if remaining <= 0:
            raise TimeoutError('The workload phase budget was exhausted')
        timed_out = False
        with (self.directory / (name + '.log')).open('w') as log:
            process = subprocess.Popen(command, cwd=root, env=self.env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                returncode = process.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                timed_out = True
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                returncode = 124
            except BaseException:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                raise
        for module in modules:
            source = root / module / 'target/surefire-reports'
            if source.exists():
                shutil.copytree(source, stage_dir / module, dirs_exist_ok=True)
        cases = junit_results(stage_dir)
        status = classify(returncode, cases, timed_out) if require_cases else ('passed' if returncode == 0 else 'infrastructure_failure')
        stage = {'name': name, 'returncode': returncode, 'status': status, 'cases': len(cases)}
        missing = missing_required_cases(name, cases)
        if missing:
            stage['missing_required_cases'] = missing
            if status == 'passed':
                status = stage['status'] = 'incomplete'
        self.stages.append(stage)
        if not name.startswith(('mutant-', 'mutation-baseline-')):
            self.cases.extend(cases)
        print(name + ': ' + status + ' (' + str(len(cases)) + ' cases)', flush=True)
        return stage, cases

    def build(self):
        stage, _ = self.maven_stage('build', ['-DskipTests', 'install'], [AUTO, EXAMPLE], 300, require_cases=False)
        if stage['status'] != 'passed':
            raise RuntimeError('Test infrastructure did not compile; see build.log')

    def deterministic(self):
        self.phase_deadline = self.started + 600
        try:
            for module, name in ((AUTO, 'jdbc-jpa'), (EXAMPLE, 'http-security')):
                self.maven_stage(name, ['-pl', module, '-Poracle-concurrency', '-Dgroups=oracle-concurrency', 'test'], [module], 300)
        except TimeoutError:
            self.stages.append({'name': 'deterministic-budget', 'status': 'infrastructure_timeout'})
        finally:
            self.phase_deadline = None

    def stress(self):
        for module, name in ((AUTO, 'jdbc-jpa-stress'), (EXAMPLE, 'http-stress')):
            self.maven_stage(name, ['-pl', module, '-Poracle-stress', '-Dgroups=oracle-stress',
                                   f'-Daudit.stress.seconds={self.args.stress_seconds}',
                                   '-Daudit.stress.seeds=' + self.args.seeds, 'test'], [module], self.args.stress_seconds + 90)

    def mutations(self):
        from audit_mutations import run_mutations
        self.phase_deadline = time.monotonic() + 300
        try:
            run_mutations(self)
        except TimeoutError:
            self.stages.append({'name': 'mutation-budget', 'status': 'infrastructure_timeout'})
        finally:
            self.phase_deadline = None

    def finish(self, error=None):
        if error:
            self.manifest['infrastructure_error'] = str(error)
        evidence = []
        for path in sorted((self.directory / 'evidence').glob('*.json')):
            evidence.append(json.loads(path.read_text()))
        counters = {}
        for case in evidence:
            for key, value in case.get('counters', {}).items():
                counters[key] = counters.get(key, 0) + value
        self.manifest['environments'] = [json.loads(value) for value in sorted({json.dumps(case['environment'], sort_keys=True) for case in evidence if 'environment' in case})]
        self.manifest['source_changed_during_run'] = (production_fingerprint(self.root) != self.manifest['production_fingerprint'] or test_fingerprint(self.root) != self.manifest['test_fingerprint'])
        self.manifest['evidence_cases'] = len(evidence)
        self.manifest['counters'] = counters
        self.manifest['elapsed_seconds'] = round(time.monotonic() - self.started, 2)
        required_stages = {'build'}
        if self.args.phase in ('complete', 'deterministic'):
            required_stages.update(('jdbc-jpa', 'http-security'))
        if self.args.phase in ('complete', 'stress'):
            required_stages.update(('jdbc-jpa-stress', 'http-stress'))
        if self.args.phase in ('complete', 'mutations'):
            from audit_mutations import definitions
            required_stages.update('mutant-' + name for name, *_ in definitions())
        self.manifest['missing_stages'] = sorted(required_stages - {stage['name'] for stage in self.stages})
        evidence_missing = len(evidence) != len(self.cases)
        self.manifest['evidence_missing'] = evidence_missing
        failed = error is not None or evidence_missing or self.manifest['source_changed_during_run'] or bool(self.manifest['missing_stages']) or any(stage['status'] not in ('passed', 'killed') for stage in self.stages)
        self.manifest['status'] = 'failed' if failed else 'passed'
        complete = self.args.phase == 'complete' and self.args.stress_seconds >= 900 and not failed
        self.manifest['complete_battery_passed'] = complete
        peers = []
        for path in (self.root / 'target/audit-concurrency').glob('*/summary.json'):
            if path.parent == self.directory:
                continue
            try:
                peer = json.loads(path.read_text())
                if peer.get('production_fingerprint') == self.manifest['production_fingerprint'] and peer.get('test_fingerprint') == self.manifest['test_fingerprint'] and peer.get('complete_battery_passed'):
                    peers.append(peer.get('expected_oracle_major'))
            except (OSError, ValueError):
                continue
        if complete:
            peers.append(self.args.expected_major)
        self.manifest['validated_databases'] = sorted(set(peers))
        self.manifest['overall_validation'] = 'approved' if {19, 23}.issubset(peers) else 'incomplete'
        if failed:
            self.manifest['overall_validation'] = 'rejected' if any(stage['status'] == 'tests_failed' for stage in self.stages) else 'incomplete'
        self.manifest['mutation_results'] = getattr(self, 'mutation_results', [])
        write_reports(self.directory, self.manifest, self.cases)
        print('Report: ' + str(self.directory / 'report.html'), flush=True)
        print('Overall validation: ' + self.manifest['overall_validation'], flush=True)
        return 1 if failed else 0

    def cleanup(self):
        if self.container:
            subprocess.run(['docker', 'rm', '-fv', self.container], capture_output=True, timeout=60)
        if self.temp:
            self.temp.cleanup()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-major', type=int, choices=(19, 23), default=23)
    parser.add_argument('--phase', choices=('complete', 'deterministic', 'stress', 'mutations'), default='complete')
    parser.add_argument('--stress-seconds', type=int, default=900)
    parser.add_argument('--seeds', default='1,42,20261108')
    parser.add_argument('--maven')
    parser.add_argument('--java-home')
    parser.add_argument('--report-dir')
    args = parser.parse_args(argv)
    if args.stress_seconds < 12:
        parser.error('--stress-seconds must be at least 12; values below 900 are diagnostic runs only')
    try:
        if not args.seeds or any(not seed.strip() for seed in args.seeds.split(',')):
            raise ValueError()
        [int(seed) for seed in args.seeds.split(',')]
    except ValueError:
        parser.error('--seeds must be a nonempty comma-separated list of integers')
    runner = Runner(args)
    lock_dir = ROOT / 'target/audit-concurrency'
    lock_dir.mkdir(parents=True, exist_ok=True)
    error = None
    with (lock_dir / '.runner.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            runner.prepare()
            runner.build()
            if args.phase in ('complete', 'deterministic'):
                runner.deterministic()
            if args.phase in ('complete', 'mutations'):
                runner.mutations()
            if args.phase in ('complete', 'stress'):
                runner.stress()
        except (Exception, KeyboardInterrupt) as failure:
            error = failure
        finally:
            try:
                result = runner.finish(error)
            finally:
                runner.cleanup()
    return result


if __name__ == '__main__':
    raise SystemExit(main())
