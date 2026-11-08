-- Upgrade ONLY an existing database created by the previous CPF/name/request-id POC.
-- Stop the old application before applying this migration. DDL commits in Oracle.
-- Keep historical fields and rows unchanged; their new session metadata remains NULL.
ALTER TABLE AUDIT_LOG ADD (
    NM_USUARIO_APLICACAO VARCHAR2(100 CHAR),
    NR_IP_APLICACAO VARCHAR2(45 CHAR),
    NM_APLICATIVO VARCHAR2(50 CHAR)
);
ALTER TABLE AUDIT_LOG MODIFY (USER_CPF NULL, USER_NAME NULL, REQUEST_ID NULL);
CREATE INDEX IDX_AUDIT_LOG_USER_TIMESTAMP ON AUDIT_LOG (NM_USUARIO_APLICACAO, EVENT_TIMESTAMP);
-- Next apply ../03_cliente_audit_triggers.sql, then deploy the new application.
-- The old package/context may remain installed but are no longer used by the application or trigger.
