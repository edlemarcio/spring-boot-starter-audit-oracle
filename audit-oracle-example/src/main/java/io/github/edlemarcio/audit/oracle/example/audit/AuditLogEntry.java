package io.github.edlemarcio.audit.oracle.example.audit;

import java.time.LocalDateTime;

public record AuditLogEntry(
        Long id,
        LocalDateTime eventTimestamp,
        String user,
        String ip,
        String application,
        String operation,
        String tableName,
        String recordId,
        String oldValues,
        String newValues
) {
}
