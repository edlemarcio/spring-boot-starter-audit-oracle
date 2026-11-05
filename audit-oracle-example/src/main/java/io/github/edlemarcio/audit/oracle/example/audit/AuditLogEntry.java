package io.github.edlemarcio.audit.oracle.example.audit;

import java.time.LocalDateTime;

public record AuditLogEntry(
        Long id,
        LocalDateTime eventTimestamp,
        String userCpf,
        String userName,
        String operation,
        String tableName,
        String recordId,
        String requestId,
        String oldValues,
        String newValues
) {
}
