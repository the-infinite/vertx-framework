package io.github.the_infinite.framework.data;

import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

@SuppressWarnings("unused")
public final class ValidationHelper {
  private static final ValidationHelper INSTANCE = new ValidationHelper();
  private final ValidatorFactory factory;

  // Expose the factory as a constant for use in configuration
  public static final ValidatorFactory VALIDATION_FACTORY = INSTANCE.factory;

  private ValidationHelper() {
    factory = Validation.byDefaultProvider()
      .configure()
      .messageInterpolator(new ParameterMessageInterpolator())
      .buildValidatorFactory();
  }

  public static ValidationHelper getInstance() {
    return INSTANCE;
  }

  public ValidatorFactory factory() {
    return this.factory;
  }

  public Validator validator() {
    return this.factory.getValidator();
  }

  public ConstraintValidatorFactory constraintValidatorFactory() {
    return this.factory.getConstraintValidatorFactory();
  }
}
