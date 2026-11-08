package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import io.github.edlemarcio.audit.oracle.core.AuditContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class AuditConnection implements InvocationHandler {

    static final String SET_CONTEXT = "BEGIN DBMS_SESSION.SET_IDENTIFIER(?); "
            + "DBMS_APPLICATION_INFO.SET_CLIENT_INFO(?); DBMS_APPLICATION_INFO.SET_MODULE(?, NULL); END;";
    static final String CLEAR_CONTEXT = "BEGIN DBMS_SESSION.CLEAR_IDENTIFIER; "
            + "DBMS_APPLICATION_INFO.SET_CLIENT_INFO(NULL); DBMS_APPLICATION_INFO.SET_MODULE(NULL, NULL); END;";

    private final Connection connection;
    private final Consumer<Connection> discard;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AuditConnection(final Connection connection, final Consumer<Connection> discard) {
        this.connection = connection;
        this.discard = discard;
    }

    static Connection configure(final Connection connection, final AuditContext context,
                                 final Consumer<Connection> discard) throws SQLException {
        try (CallableStatement statement = connection.prepareCall(SET_CONTEXT)) {
            statement.setString(1, context.user());
            statement.setString(2, context.ip());
            statement.setString(3, context.application());
            statement.execute();
        } catch (SQLException | RuntimeException e) {
            discardAndClose(connection, discard, e);
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(AuditConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new AuditConnection(connection, discard));
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "AuditConnection[" + connection + "]";
            };
        }
        if ("close".equals(method.getName())) {
            close();
            return null;
        }
        if ("isClosed".equals(method.getName())) {
            return closed.get() || connection.isClosed();
        }
        if (("unwrap".equals(method.getName()) || "isWrapperFor".equals(method.getName()))
                && ((Class<?>) args[0]).isInstance(proxy)) {
            return "unwrap".equals(method.getName()) ? proxy : true;
        }
        try {
            return method.invoke(connection, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void close() throws SQLException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            // Roll back pending work before clearing metadata, as Hikari would do on close.
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException | RuntimeException e) {
            discardAndClose(connection, discard, e);
            throw e;
        }
        try (CallableStatement statement = connection.prepareCall(CLEAR_CONTEXT)) {
            statement.execute();
        } catch (SQLException | RuntimeException e) {
            discardAndClose(connection, discard, e);
            throw e;
        }
        connection.close();
    }

    private static void discardAndClose(final Connection connection, final Consumer<Connection> discard,
                                        final Throwable failure) {
        try {
            discard.accept(connection);
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException abortException) {
                // Keep the connection reserved if neither eviction nor abort can guarantee its removal.
                failure.addSuppressed(abortException);
                return;
            }
        }
        try {
            connection.close();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }
}
