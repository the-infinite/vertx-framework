package io.github.the_infinite.core.doc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking static fields in a class as example data for documentation.
 * It should be applied to fields that are used as DTO examples.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResponseExample {
    /**
     * The name of the example.
     * @return the name of the example.
     */
    String value() default "Example";
}
