package io.github.the_infinite.framework.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IsBetween {
  long start();
  long end();
  boolean inclusive() default true;
  String message() default "";
}
