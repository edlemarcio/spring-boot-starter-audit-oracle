package io.github.edlemarcio.audit.oracle.autoconfigure.annotation;

import org.springframework.transaction.annotation.Propagation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Executes a service method in an audit-aware transaction.
 *
 * <p>The annotation owns the transaction boundary for the annotated method.
 * Do not combine it with a second, conflicting {@code @Transactional}
 * declaration on the same method. Use {@link #propagation()} to select the
 * transaction propagation behavior.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    Propagation propagation() default Propagation.REQUIRED;
}
