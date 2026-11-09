"""Curated valid-Java mutations in disposable copies; a non-green baseline cannot kill a mutant."""
from pathlib import Path
import shutil
import tempfile

AUTO = 'audit-oracle-spring-boot-autoconfigure'
SOURCE = Path(AUTO) / 'src/main/java/io/github/edlemarcio/audit/oracle/autoconfigure/jdbc/AuditConnection.java'


def definitions():
    reuse = 'OracleConcurrencyTest#forcesManyOwnersOntoOnePhysicalSession'
    return [
        ('omit-checkout-clear', '        try {\n            clearContext(connection);\n            try (CallableStatement statement = connection.prepareCall(SET_CONTEXT)) {',
         '        try {\n            /* deliberately omit checkout cleanup */\n            try (CallableStatement statement = connection.prepareCall(SET_CONTEXT)) {',
         'OracleConcurrencyTest#cleansStaleSessionOnCheckoutBeforeApplyingTheNextIdentity'),
        ('wrong-user', 'statement.setString(1, context.user());', 'statement.setString(1, "MUTANT_WRONG_USER");', reuse),
        ('wrong-ip', 'statement.setString(2, context.ip());', 'statement.setString(2, "192.0.2.254");', reuse),
        ('wrong-application', 'statement.setString(3, context.application());', 'statement.setString(3, "MUTANT_APP");', reuse),
        ('omit-clear-user', 'DBMS_SESSION.CLEAR_IDENTIFIER;', 'NULL;', reuse),
        ('omit-clear-ip', 'DBMS_APPLICATION_INFO.SET_CLIENT_INFO(NULL);', 'NULL;', reuse),
        ('omit-clear-application', 'DBMS_APPLICATION_INFO.SET_MODULE(NULL, NULL);', 'NULL;', reuse),
        ('recycle-after-cleanup-failure', 'discard.accept(connection);', '/* deliberately bypass eviction */',
         'OracleLifecycleFaultTest#finalizationFailuresEvictBeforeTheWaitingUserReceivesAConnection'),
        ('commit-instead-of-implicit-rollback', 'connection.rollback();', 'connection.commit();',
         'OracleConcurrencyTest#cleansAfterExplicitOrImplicitRollback'),
        ('clear-before-flush', '        try {\n            return method.invoke(connection, args);',
         '        if ("prepareStatement".equals(method.getName())) {\n'
         '            try (CallableStatement cleanup = connection.prepareCall(CLEAR_CONTEXT)) { cleanup.execute(); }\n'
         '        }\n        try {\n            return method.invoke(connection, args);',
         'OracleJpaConcurrencyTest#concurrentJpaTransactionsKeepFlushCascadeAndOrphanRemovalAuthors'),
    ]


def mutation_assertions(stage_status, cases, baseline_cases):
    if stage_status != 'tests_failed':
        return []
    return [case for case in cases if case['status'] == 'failed'
            and case['scenario'] in baseline_cases
            and ('Assertion' in case['errorType'] or 'MultipleFailures' in case['errorType'])]


def run_mutations(runner):
    runner.mutation_results = []
    baselines = {}
    for _, _, _, test in definitions():
        if test not in baselines:
            index = len(baselines)
            stage, cases = runner.maven_stage('mutation-baseline-' + str(index),
                ['-pl', AUTO, '-Poracle-concurrency', '-Dtest=' + test, 'test'], [AUTO], 45)
            baselines[test] = (stage['status'] == 'passed', {case['scenario'] for case in cases})
    for name, old, new, test in definitions():
        passed, baseline_cases = baselines[test]
        if not passed:
            runner.mutation_results.append({'name': name, 'status': 'inconclusive', 'reason': 'baseline is not green'})
            runner.stages.append({'name': 'mutant-' + name, 'status': 'inconclusive'})
            continue
        with tempfile.TemporaryDirectory(prefix='audit-mutant-') as folder:
            copy = Path(folder)
            for module in ('audit-oracle-core', AUTO, 'audit-oracle-spring-boot-starter', 'audit-oracle-example'):
                shutil.copytree(runner.root / module, copy / module, ignore=shutil.ignore_patterns('target', '.env', '.git'))
            shutil.copy2(runner.root / 'pom.xml', copy / 'pom.xml')
            source = copy / SOURCE
            original = source.read_text()
            if original.count(old) != 1:
                raise RuntimeError('Mutation anchor is no longer unique: ' + name)
            source.write_text(original.replace(old, new, 1))
            stage, cases = runner.maven_stage('mutant-' + name,
                ['-pl', AUTO, '-am', '-Poracle-concurrency', '-Dtest=' + test,
                 '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], [AUTO], 35, root=copy)
            # Compilation, connection errors, missing tests and timeouts are NOT mutation detection.
            assertions = mutation_assertions(stage['status'], cases, baseline_cases)
            killed = bool(assertions) and stage['status'] == 'tests_failed'
            stage['status'] = 'killed' if killed else 'survived_or_inconclusive'
            runner.mutation_results.append({'name': name, 'status': stage['status'],
                                            'detected_by': [case['scenario'] for case in assertions]})
