package io.github.edlemarcio.audit.oracle.autoconfigure.transaction;

import io.github.edlemarcio.audit.oracle.autoconfigure.annotation.Audited;
import io.github.edlemarcio.audit.oracle.autoconfigure.exception.AuditContextException;
import io.github.edlemarcio.audit.oracle.autoconfigure.exception.AuditInvocationException;
import io.github.edlemarcio.audit.oracle.autoconfigure.oracle.OracleAuditContextManager;
import io.github.edlemarcio.audit.oracle.core.AuditIdentity;
import io.github.edlemarcio.audit.oracle.core.AuditIdentityProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Aspect
public class AuditedTransactionAspect {

    private final AuditIdentityProvider identityProvider;
    private final OracleAuditContextManager contextManager;
    private final PlatformTransactionManager transactionManager;

    public AuditedTransactionAspect(
            AuditIdentityProvider identityProvider,
            OracleAuditContextManager contextManager,
            PlatformTransactionManager transactionManager
    ) {
        this.identityProvider = identityProvider;
        this.contextManager = contextManager;
        this.transactionManager = transactionManager;
    }

    @Around(value = "@annotation(audited)", argNames = "invocation,audited")
    public Object execute(ProceedingJoinPoint invocation, Audited audited) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(audited.propagation().value());

        return transaction.execute(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new AuditContextException(
                        "@Audited requires an active transaction; use REQUIRED or REQUIRES_NEW propagation");
            }

            AuditIdentity identity = identityProvider.getCurrentIdentity()
                    .orElseThrow(() -> new AuditContextException(
                            "No authenticated audit identity is available for this operation"));

            try (OracleAuditContextManager.Scope ignored = contextManager.open(identity)) {
                return invocation.proceed();
            } catch (RuntimeException | Error exception) {
                status.setRollbackOnly();
                throw exception;
            } catch (Throwable exception) {
                status.setRollbackOnly();
                throw new AuditInvocationException(exception);
            }
        });
    }
}
