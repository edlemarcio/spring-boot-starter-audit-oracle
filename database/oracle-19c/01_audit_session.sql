-- The TRE session contract uses built-in Oracle attributes: no custom package/context is installed.
-- The application configures these values on each connection checkout and clears them on close.
-- Informational query, also useful when checking a connection before returning it to the pool.
SELECT SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') AS NM_USUARIO_APLICACAO,
       SYS_CONTEXT('USERENV', 'CLIENT_INFO') AS NR_IP_APLICACAO,
       SYS_CONTEXT('USERENV', 'MODULE') AS NM_APLICATIVO
  FROM DUAL;
