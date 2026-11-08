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
                SELECT ID, EVENT_TIMESTAMP, NM_USUARIO_APLICACAO, NR_IP_APLICACAO, NM_APLICATIVO,
                       OPERATION, TABLE_NAME, RECORD_ID, OLD_VALUES, NEW_VALUES
                  FROM AUDIT_LOG
                 ORDER BY EVENT_TIMESTAMP DESC, ID DESC
                 FETCH FIRST 200 ROWS ONLY
                """, (rs, rowNum) -> {
            Timestamp timestamp = rs.getTimestamp("EVENT_TIMESTAMP");
            return new AuditLogEntry(
                    rs.getLong("ID"),
                    timestamp == null ? null : timestamp.toLocalDateTime(),
                    rs.getString("NM_USUARIO_APLICACAO"),
                    rs.getString("NR_IP_APLICACAO"),
                    rs.getString("NM_APLICATIVO"),
                    rs.getString("OPERATION"),
                    rs.getString("TABLE_NAME"),
                    rs.getString("RECORD_ID"),
                    rs.getString("OLD_VALUES"),
                    rs.getString("NEW_VALUES")
            );
        });
    }
}
