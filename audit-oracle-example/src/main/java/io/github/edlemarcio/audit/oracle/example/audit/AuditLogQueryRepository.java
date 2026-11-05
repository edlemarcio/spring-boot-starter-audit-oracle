package io.github.edlemarcio.audit.oracle.example.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class AuditLogQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AuditLogEntry> findLatest() {
        return jdbcTemplate.query("""
                SELECT ID, EVENT_TIMESTAMP, USER_CPF, USER_NAME, OPERATION,
                       TABLE_NAME, RECORD_ID, REQUEST_ID
                  FROM AUDIT_LOG
                 ORDER BY EVENT_TIMESTAMP DESC, ID DESC
                """, (rs, rowNum) -> {
            Timestamp timestamp = rs.getTimestamp("EVENT_TIMESTAMP");
            return new AuditLogEntry(
                    rs.getLong("ID"),
                    timestamp == null ? null : timestamp.toLocalDateTime(),
                    rs.getString("USER_CPF"),
                    rs.getString("USER_NAME"),
                    rs.getString("OPERATION"),
                    rs.getString("TABLE_NAME"),
                    rs.getString("RECORD_ID"),
                    rs.getString("REQUEST_ID")
            );
        });
    }
}
