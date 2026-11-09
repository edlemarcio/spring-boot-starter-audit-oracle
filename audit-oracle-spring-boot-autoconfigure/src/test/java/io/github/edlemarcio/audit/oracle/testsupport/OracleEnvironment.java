package io.github.edlemarcio.audit.oracle.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/** A requested Oracle profile fails rather than silently skipping missing infrastructure. */
public record OracleEnvironment(String url, String user, String password, int expectedMajor) {
    public static OracleEnvironment required() {
        return new OracleEnvironment(required("AUDIT_ORACLE_URL"), required("AUDIT_ORACLE_USER"),
                required("AUDIT_ORACLE_PASSWORD"), Integer.parseInt(System.getProperty("audit.expectedOracleMajor", "23")));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required test configuration missing: " + name);
        return value;
    }

    public Connection connect() throws SQLException {
        Properties properties = driverProperties();
        properties.setProperty("user", user); properties.setProperty("password", password);
        Connection connection = DriverManager.getConnection(url, properties);
        try {
            var metadata = connection.getMetaData();
            if (!metadata.getDatabaseProductName().contains("Oracle") || metadata.getDatabaseMajorVersion() != expectedMajor) {
                throw new IllegalStateException("Expected Oracle " + expectedMajor + ", got "
                        + metadata.getDatabaseProductVersion());
            }
            return connection;
        } catch (Throwable failure) {
            connection.close();
            throw failure;
        }
    }

    public static Properties driverProperties() {
        var properties = new Properties();
        // Avoid reverse DNS of the workstation hostname for each short-lived observer connection.
        properties.setProperty(oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_LOCAL_HOST_NAME, "localhost");
        properties.setProperty(oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_THIN_VSESSION_MACHINE, "audit-concurrency-test");
        properties.setProperty(oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_THIN_VSESSION_PROGRAM, "audit-concurrency-test");
        properties.setProperty("oracle.jdbc.ReadTimeout", "15000");
        return properties;
    }

    public void describe(ScenarioReport report) throws SQLException {
        try (var connection = connect()) {
            var metadata = connection.getMetaData();
            report.detail("environment", Map.of("oracle", metadata.getDatabaseProductVersion(),
                    "driver", metadata.getDriverVersion(), "java", System.getProperty("java.version"),
                    "hikari", hikariVersion()));
        }
    }

    private static String hikariVersion() {
        var properties = new Properties();
        try (var input = com.zaxxer.hikari.HikariDataSource.class.getResourceAsStream("/META-INF/maven/com.zaxxer/HikariCP/pom.properties")) {
            if (input == null) throw new IllegalStateException("Missing Hikari version metadata");
            properties.load(input);
            return properties.getProperty("version");
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
