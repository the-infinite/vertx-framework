package io.github.the_infinite.framework.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IsDecimalOutside {
  double start();
  double end();
  boolean inclusive() default true;
  String message() default "";
}
