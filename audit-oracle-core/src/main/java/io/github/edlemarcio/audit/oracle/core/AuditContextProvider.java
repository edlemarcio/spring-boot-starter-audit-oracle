package io.github.edlemarcio.audit.oracle.core;

/** Supplies trusted metadata for each connection checkout, before a pool connection is acquired. */
@FunctionalInterface
public interface AuditContextProvider {
    AuditContext getContext();
}
