package io.github.edlemarcio.audit.oracle.testsupport;

import oracle.jdbc.pool.OracleDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** Driver-level test instrumentation; the Hikari pool and auditing code remain real. */
public final class TrackingOracleDataSource implements DataSource, AutoCloseable {
    public enum Point {
        SET_BEFORE, SET_USER, SET_IP, SET_AFTER, SET_STATEMENT_CLOSE,
        CLEAR_BEFORE, CLEAR_USER, CLEAR_IP, CLEAR_AFTER, CLEAR_STATEMENT_CLOSE,
        ROLLBACK, AUTOCOMMIT_RESET, COMMIT_BEFORE, COMMIT_AFTER, PHYSICAL_CLOSE, ABORT
    }

    public record Fault(Point point, boolean runtime, CountDownLatch entered, CountDownLatch release) { }
    private final OracleDataSource delegate;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, Connection> physical = new ConcurrentHashMap<>();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final AtomicReference<Fault> fault = new AtomicReference<>();
    private final ScenarioReport report;
    private final AtomicLong cleanups = new AtomicLong();

    public TrackingOracleDataSource(OracleEnvironment environment, ScenarioReport report) throws SQLException {
        this.report = report;
        delegate = new OracleDataSource();
        delegate.setURL(environment.url());
        delegate.setUser(environment.user());
        delegate.setPassword(environment.password());
        delegate.setConnectionProperties(OracleEnvironment.driverProperties());
    }

    public void arm(Point point) { arm(new Fault(point, false, null, null)); }
    public void arm(Fault value) {
        if (!fault.compareAndSet(null, value)) throw new IllegalStateException("A fault is already armed");
    }
    public void disarm() { fault.set(null); }
    public long cleanups() { return cleanups.get(); }
    public long created() { return sequence.get(); }
    public long generation(Connection connection) throws SQLException { return sessions.get(session(connection)); }

    public static String session(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT SYS_CONTEXT('USERENV','SID') FROM DUAL")) {
            result.next();
            return result.getString(1);
        }
    }

    public static Map<String, String> snapshot(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT "
                + "SYS_CONTEXT('USERENV','CLIENT_IDENTIFIER'), SYS_CONTEXT('USERENV','CLIENT_INFO'), "
                + "SYS_CONTEXT('USERENV','MODULE') FROM DUAL")) {
            result.next();
            var snapshot = new LinkedHashMap<String, String>();
            snapshot.put("user", result.getString(1));
            snapshot.put("ip", result.getString(2));
            snapshot.put("application", result.getString(3));
            return snapshot;
        }
    }

    /** Only call while a barrier prevents all borrowers from using this physical connection. */
    public Map<String, String> idleSnapshot(long generation) throws SQLException {
        return snapshot(physical.get(generation));
    }

    public boolean closed(long generation) throws SQLException { return physical.get(generation).isClosed(); }
    public void breakConnection(long generation) throws SQLException { physical.get(generation).abort(Runnable::run); }

    @Override
    public Connection getConnection() throws SQLException {
        Connection raw = delegate.getConnection();
        long generation = sequence.incrementAndGet();
        physical.put(generation, raw);
        sessions.put(session(raw), generation);
        report.count("physicalConnectionsCreated");
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Point point = switch (method.getName()) {
                        case "rollback" -> Point.ROLLBACK;
                        case "commit" -> Point.COMMIT_BEFORE;
                        case "close" -> Point.PHYSICAL_CLOSE;
                        case "abort" -> Point.ABORT;
                        case "setAutoCommit" -> Boolean.TRUE.equals(args[0]) ? Point.AUTOCOMMIT_RESET : null;
                        default -> null;
                    };
                    hit(point);
                    Object result = invoke(raw, method, args);
                    if ("commit".equals(method.getName())) hit(Point.COMMIT_AFTER);
                    if ("prepareCall".equals(method.getName())) {
                        return statement(raw, (CallableStatement) result, String.valueOf(args[0]), generation);
                    }
                    return result;
                });
    }

    private CallableStatement statement(Connection raw, CallableStatement statement, String sql, long generation) {
        boolean setting = sql.startsWith("BEGIN DBMS_SESSION.SET_IDENTIFIER");
        boolean clearing = sql.startsWith("BEGIN DBMS_SESSION.CLEAR_IDENTIFIER");
        if (!setting && !clearing) return statement;
        Map<Integer, String> parameters = new LinkedHashMap<>();
        return (CallableStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CallableStatement.class}, (proxy, method, args) -> {
                    if ("setString".equals(method.getName())) parameters.put((Integer) args[0], (String) args[1]);
                    if ("execute".equals(method.getName())) {
                        hit(setting ? Point.SET_BEFORE : Point.CLEAR_BEFORE);
                        Fault current = fault.get();
                        if (current != null && (current.point() == (setting ? Point.SET_USER : Point.CLEAR_USER)
                                || current.point() == (setting ? Point.SET_IP : Point.CLEAR_IP))) {
                            boolean includeIp = current.point() == Point.SET_IP || current.point() == Point.CLEAR_IP;
                            partial(raw, parameters, setting, includeIp);
                            hit(current.point());
                        }
                    }
                    if ("close".equals(method.getName())) hit(setting ? Point.SET_STATEMENT_CLOSE : Point.CLEAR_STATEMENT_CLOSE);
                    Object result = invoke(statement, method, args);
                    if ("execute".equals(method.getName())) {
                        if (clearing) {
                            var snapshot = snapshot(raw);
                            report.count("cleanupObservations");
                            cleanups.incrementAndGet();
                            if (snapshot.values().stream().anyMatch(value -> value != null)) {
                                throw new AssertionError("Dirty session before pool handoff: generation=" + generation + " " + snapshot);
                            }
                        }
                        hit(setting ? Point.SET_AFTER : Point.CLEAR_AFTER);
                    }
                    return result;
                });
    }

    private void partial(Connection raw, Map<Integer, String> parameters, boolean setting, boolean includeIp) throws SQLException {
        String sql = setting ? "BEGIN DBMS_SESSION.SET_IDENTIFIER(?); "
                + (includeIp ? "DBMS_APPLICATION_INFO.SET_CLIENT_INFO(?); " : "") + "END;"
                : "BEGIN DBMS_SESSION.CLEAR_IDENTIFIER; "
                + (includeIp ? "DBMS_APPLICATION_INFO.SET_CLIENT_INFO(NULL); " : "") + "END;";
        try (var statement = raw.prepareCall(sql)) {
            if (setting) {
                statement.setString(1, parameters.get(1));
                if (includeIp) statement.setString(2, parameters.get(2));
            }
            statement.execute();
        }
    }

    private void hit(Point point) throws Exception {
        Fault current = fault.get();
        if (point == null || current == null || current.point() != point || !fault.compareAndSet(current, null)) return;
        report.trace(Map.of("phase", point.name(), "fault", current.runtime() ? "runtime" : "sql"));
        if (current.entered() != null) current.entered().countDown();
        if (current.release() != null) ConcurrentWork.await(current.release());
        if (current.runtime()) throw new IllegalStateException("Injected " + point);
        throw new SQLException("Injected " + point, "HY000");
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }

    @Override public void close() throws SQLException {
        disarm();
        SQLException failure = null;
        for (Connection connection : physical.values()) {
            try { connection.close(); } catch (SQLException error) { failure = error; }
        }
        if (failure != null) throw failure;
    }
    @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(Math.max(30, seconds)); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
}
