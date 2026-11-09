package io.github.edlemarcio.audit.oracle.testsupport;

import io.github.edlemarcio.audit.oracle.autoconfigure.jdbc.AuditDataSource;
import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.core.AuditContextProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Unique tables isolate concurrent runs; expected identities never come from SYS_CONTEXT/provider output. */
public final class OracleScenario implements AutoCloseable {
    public static final AuditContext ANA = new AuditContext("sub-ana", "2001:db8::1", "internal");
    public static final AuditContext BRUNO = new AuditContext("sub-bruno", "203.0.113.20", "external");
    public static final AuditContext SYSTEM = new AuditContext("SISTEMA:ROTINA", "0.0.0.0", "internal");
    public final OracleEnvironment environment;
    public final ScenarioReport report;
    public final TrackingOracleDataSource tracking;
    public final AuditDataSource pool;
    public final String target;
    public final String logs;
    public final String markers;
    private final ThreadLocal<AuditContext> context = ThreadLocal.withInitial(() -> SYSTEM);
    private final Map<String, Expected> expected = new ConcurrentHashMap<>();
    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> owners = new ConcurrentHashMap<>();
    private final OracleTestLease lease;
    private enum Outcome { COMMIT, ROLLBACK, AMBIGUOUS }
    private record Expected(String transaction, AuditContext identity, String recordId, String operation) { }
    private record Observed(String user, String ip, String application) {
        static Observed of(AuditContext identity) { return new Observed(identity.user(), identity.ip(), identity.application()); }
    }

    public OracleScenario(int poolSize) throws Exception {
        this(poolSize, null);
    }

    public OracleScenario(int poolSize, AuditContextProvider provider) throws Exception {
        environment = OracleEnvironment.required();
        report = ScenarioReport.current();
        environment.describe(report);
        lease = new OracleTestLease(environment);
        String prefix = "AC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        target = prefix + "_T"; logs = prefix + "_L"; markers = prefix + "_M";
        tracking = new TrackingOracleDataSource(environment, report);
        pool = new AuditDataSource(provider == null ? context::get : provider);
        pool.setDataSource(tracking);
        pool.setMinimumIdle(1);
        pool.setMaximumPoolSize(poolSize);
        pool.setConnectionTimeout(5000);
        pool.setValidationTimeout(500);
        pool.setPoolName(prefix);
        execute("CREATE TABLE " + target + " (ID VARCHAR2(64) PRIMARY KEY, VERSION NUMBER NOT NULL, PAYLOAD VARCHAR2(100), DELETED CHAR(1) DEFAULT 'N', PARENT_ID VARCHAR2(64))");
        execute("CREATE TABLE " + markers + " (ID VARCHAR2(64) PRIMARY KEY)");
        execute("CREATE TABLE " + logs + " (RECORD_ID VARCHAR2(64), VERSION NUMBER, OP VARCHAR2(1), "
                + "AUTHOR VARCHAR2(100), IP VARCHAR2(45), APP VARCHAR2(50), SID VARCHAR2(100))");
        execute("CREATE OR REPLACE TRIGGER " + prefix + "_TG AFTER INSERT OR UPDATE OR DELETE ON " + target
                + " FOR EACH ROW DECLARE v_id VARCHAR2(64); v_version NUMBER; v_op VARCHAR2(1); BEGIN "
                + "IF DELETING THEN v_id := :OLD.ID; v_version := :OLD.VERSION; v_op := 'D'; ELSE "
                + "v_id := :NEW.ID; v_version := :NEW.VERSION; IF INSERTING THEN v_op := 'I'; ELSE "
                + "v_op := CASE WHEN :OLD.DELETED = 'N' AND :NEW.DELETED = 'S' THEN 'E' ELSE 'U' END; END IF; END IF; "
                + "INSERT INTO " + logs + " VALUES(v_id, v_version, v_op, "
                + "NVL(SYS_CONTEXT('USERENV','CLIENT_IDENTIFIER'),SYS_CONTEXT('USERENV','SESSION_USER')), "
                + "NVL(SYS_CONTEXT('USERENV','CLIENT_INFO'),NVL(SYS_CONTEXT('USERENV','IP_ADDRESS'),'0.0.0.0')), "
                + "SYS_CONTEXT('USERENV','MODULE'), SYS_CONTEXT('USERENV','SID')); END;");
    }

    public Connection borrow(AuditContext identity) throws SQLException {
        context.set(identity);
        try {
            Connection connection = pool.getConnection();
            observe(connection, identity);
            return connection;
        } finally { context.remove(); }
    }

    public <T> T as(AuditContext identity, Callable<T> action) throws Exception {
        AuditContext previous = context.get();
        context.set(identity);
        try { return action.call(); } finally { context.set(previous); }
    }

    public void expect(String id, int version, String operation, String transaction, AuditContext identity) {
        expected.put(key(id, version, operation), new Expected(transaction, identity, id, operation));
        outcomes.putIfAbsent(transaction, Outcome.COMMIT);
    }

    public void expectRollback(String transaction) { outcomes.put(transaction, Outcome.ROLLBACK); }
    public void allowAmbiguousCommit(String transaction) { outcomes.put(transaction, Outcome.AMBIGUOUS); }

    public void observe(Connection connection, AuditContext identity) throws SQLException {
        long generation = tracking.generation(connection);
        Set<String> users = owners.computeIfAbsent(generation, key -> ConcurrentHashMap.newKeySet());
        if (users.add(identity.user()) && users.size() > 1) report.count("crossUserPhysicalReuses");
        var snapshot = TrackingOracleDataSource.snapshot(connection);
        assertThat(snapshot).as("checkout generation %s", generation).containsExactlyInAnyOrderEntriesOf(
                Map.of("user", identity.user(), "ip", identity.ip(), "application", identity.application()));
        report.count("checkouts");
    }

    public String operation(Connection connection, AuditContext identity, boolean autocommit, boolean rollback) throws SQLException {
        String transaction = UUID.randomUUID().toString();
        if (!autocommit && rollback) expectRollback(transaction);
        connection.setAutoCommit(autocommit);
        insert(connection, transaction, transaction, identity);
        update(connection, transaction, transaction, identity, false);
        delete(connection, transaction, transaction, identity);
        marker(connection, transaction);
        if (!autocommit) {
            if (rollback) connection.rollback(); else connection.commit();
        }
        report.count("operations");
        return transaction;
    }

    public void insert(Connection connection, String id, String transaction, AuditContext identity) throws SQLException {
        expect(id, 1, "I", transaction, identity);
        try (var statement = connection.prepareStatement("INSERT INTO " + target + " (ID,VERSION,PAYLOAD) VALUES (?,1,'initial')")) {
            statement.setString(1, id); statement.executeUpdate();
        }
    }

    public void update(Connection connection, String id, String transaction, AuditContext identity, boolean softDelete) throws SQLException {
        expect(id, 2, softDelete ? "E" : "U", transaction, identity);
        try (var statement = connection.prepareStatement("UPDATE " + target + " SET VERSION=2, PAYLOAD='updated', DELETED=? WHERE ID=?")) {
            statement.setString(1, softDelete ? "S" : "N"); statement.setString(2, id);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    public void delete(Connection connection, String id, String transaction, AuditContext identity) throws SQLException {
        expect(id, 2, "D", transaction, identity);
        try (var statement = connection.prepareStatement("DELETE FROM " + target + " WHERE ID=?")) {
            statement.setString(1, id); assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    public void marker(Connection connection, String transaction) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO " + markers + " VALUES (?)")) {
            statement.setString(1, transaction); statement.executeUpdate();
        }
    }

    /** Query physical state only after all workers are joined and no connection can be borrowed. */
    public void assertIdleClean(long generation) throws SQLException {
        var snapshot = tracking.idleSnapshot(generation);
        if (snapshot.values().stream().anyMatch(value -> value != null)) {
            report.trace(Map.of("physicalGeneration", generation, "phase", "before-next-checkout",
                    "actual", snapshot.toString(), "expected", "user/ip/application must all be NULL"));
        }
        assertThat(snapshot).as("dirty session before another checkout: generation=%s", generation)
                .allSatisfy((field, value) -> assertThat(value).as(field).isNull());
    }

    public void reconcile() throws SQLException {
        Set<String> committed = new HashSet<>();
        Set<String> businessRows = new HashSet<>();
        Map<String, Observed> actual = new HashMap<>();
        try (var connection = environment.connect(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT ID FROM " + markers)) {
                while (result.next()) committed.add(result.getString(1));
            }
            try (var result = statement.executeQuery("SELECT ID FROM " + target)) {
                while (result.next()) businessRows.add(result.getString(1));
            }
            try (var result = statement.executeQuery("SELECT RECORD_ID,VERSION,OP,AUTHOR,IP,APP,SID FROM " + logs)) {
                while (result.next()) {
                    String key = key(result.getString(1), result.getInt(2), result.getString(3));
                    var identity = new Observed(result.getString(4), result.getString(5), result.getString(6));
                    assertThat(actual.put(key, identity)).as("duplicate audit event %s", key).isNull();
                    Expected intended = expected.get(key);
                    if (intended == null || !Observed.of(intended.identity()).equals(identity)) {
                        report.trace(Map.of("operation", key, "sid", result.getString(7), "actual", identity.toString(),
                                "expected", intended == null ? "no operation authorized" : intended.identity().toString()));
                    }
                }
            }
        }
        var wrongOutcomes = new ArrayList<String>();
        outcomes.forEach((transaction, outcome) -> {
            boolean exists = committed.contains(transaction);
            if ((outcome == Outcome.COMMIT && !exists) || (outcome == Outcome.ROLLBACK && exists)) {
                String actualOutcome = exists ? "COMMITTED" : "ABSENT";
                wrongOutcomes.add(transaction + " expected " + outcome + " but was " + actualOutcome);
                report.trace(Map.of("transaction", transaction, "expected", outcome.name(), "actual", actualOutcome,
                        "businessRows", businessRows.toString(), "auditEvents", actual.keySet().toString()));
            }
        });
        assertThat(wrongOutcomes).as("known transaction outcomes must match intent; only explicit ambiguous commits use observed markers").isEmpty();
        expected.values().forEach(event -> {
            if (event.operation().equals("I") && outcomes.get(event.transaction()) == Outcome.ROLLBACK)
                assertThat(businessRows).as("rolled-back insert %s", event.recordId()).doesNotContain(event.recordId());
        });
        var intended = new HashMap<String, Observed>();
        expected.forEach((key, value) -> { if (committed.contains(value.transaction())) intended.put(key, Observed.of(value.identity())); });
        report.add("auditEventsVerified", actual.size());
        assertThat(actual).as("each committed DML must have exactly its intended author/IP/application; rollback must leave none")
                .containsExactlyInAnyOrderEntriesOf(intended);
    }

    public void resetBatch() throws SQLException {
        reconcile();
        execute("DELETE FROM " + target);
        execute("DELETE FROM " + logs);
        execute("DELETE FROM " + markers);
        expected.clear();
        outcomes.clear();
    }

    public void execute(String sql) throws SQLException {
        try (var connection = environment.connect(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static String key(String id, int version, String operation) { return id + ":" + version + ":" + operation; }

    @Override
    public void close() throws Exception {
        context.remove();
        tracking.disarm();
        List<Exception> failures = new ArrayList<>();
        try { pool.close(); } catch (Exception failure) { failures.add(failure); }
        try { tracking.close(); } catch (Exception failure) { failures.add(failure); }
        for (String table : List.of(target, logs, markers)) {
            try { execute("DROP TABLE " + table + " PURGE"); } catch (Exception failure) { failures.add(failure); }
        }
        try { lease.close(); } catch (Exception failure) { failures.add(failure); }
        if (!failures.isEmpty()) {
            Exception failure = failures.removeFirst(); failures.forEach(failure::addSuppressed); throw failure;
        }
    }
}
