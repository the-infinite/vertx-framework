package io.github.the_infinite.framework.utils;

import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;

import java.util.Collection;

import io.vertx.core.Handler;
import io.vertx.ext.web.validation.RequestPredicate;
import io.vertx.ext.web.validation.builder.Bodies;
import io.vertx.ext.web.validation.builder.Parameters;
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.SchemaRepository;
import io.vertx.json.schema.common.dsl.*;

@SuppressWarnings("unused")
public final class VertxValidationHelper {
    final static String TAG = "ValidationHelper";
    static SchemaRepository repo;

    private VertxValidationHelper() {
    }

    public static SchemaRepository schemaRepository() {
        //        if (repo == null) {
        //            repo = SchemaRepository.create(
        //                    new JsonSchemaOptions().setBaseUri(ConfigurationRegistrant.getInstance().getServerUrl()).setDraft(Draft.DRAFT202012)
        //            );
        //        }
        //
        //        return repo;
        return SchemaRepository.create(
                new JsonSchemaOptions().setBaseUri(ConfigurationRegistrant.getInstance().getServerUrl()).setDraft(Draft.DRAFT202012)
        );
    }

    public static <T extends SchemaBuilder<?, ?>> Handler<CorrelationContext> validateQuery(Collection<QueryValidator<T>> validators) {
        return context -> {
            final var handler = buildQueryValidators(null, validators).build();
            handler.handle(context.router());
        };
    }


    public static Handler<CorrelationContext> validateBody(ObjectSchemaBuilder validator, boolean required) {
        return context -> {
            final var handler = buildBodyValidator(null, validator, required).build();
            handler.handle(context.router());
        };
    }

    public static Handler<CorrelationContext> validateBody(
            ObjectSchemaBuilder validator) {
        return validateBody(validator, true);
    }

    public static <T extends SchemaBuilder<?, ?>> Handler<CorrelationContext> validateRequest(
            ObjectSchemaBuilder objectSchemaBuilder,
            Collection<QueryValidator<T>> queryValidators,
            boolean bodyRequired
    ) {
        return context -> {
            var builder = buildBodyValidator(null, objectSchemaBuilder,
                    bodyRequired);
            builder = buildQueryValidators(builder, queryValidators);
            final var handler = builder.build();
            handler.handle(context.router());
        };
    }

    private static ValidationHandlerBuilder buildBodyValidator(ValidationHandlerBuilder builder,
                                                               ObjectSchemaBuilder validator,
                                                               boolean required) {
        //? We need to create a validation handler builder with the provided validator for all body types.
        if (builder == null) {
            builder = ValidationHandlerBuilder
                    .create(schemaRepository());
        }

        //? This is for required body cases.
        if (required) {
            builder = builder.predicate(RequestPredicate.BODY_REQUIRED);
        }

        //? We need to add the validator for all body types.
        builder = builder.body(Bodies.json(validator))
                .body(Bodies.formUrlEncoded(validator))
                .body(Bodies.multipartFormData(validator));

        return builder;
    }

    private static <T extends SchemaBuilder<?, ?>>

    ValidationHandlerBuilder buildQueryValidators(ValidationHandlerBuilder builder,
                                                  Collection<QueryValidator<T>> validators) {
        if (builder == null) {
            builder = ValidationHandlerBuilder
                    .create(schemaRepository());
        }

        for (final var validator : validators) {
            switch (validator.validator) {
                case ArraySchemaBuilder arraySchemaBuilder ->
                        builder = builder.queryParameter(Parameters.param(validator.name,
                                arraySchemaBuilder));
                case BooleanSchemaBuilder booleanSchemaBuilder ->
                        builder = builder.queryParameter(Parameters.param(validator.name,
                                booleanSchemaBuilder));
                case NumberSchemaBuilder numberSchemaBuilder ->
                        builder = builder.queryParameter(Parameters.param(validator.name,
                                numberSchemaBuilder));
                case ObjectSchemaBuilder objectSchemaBuilder ->
                        builder = builder.queryParameter(Parameters.jsonParam(validator.name,
                                objectSchemaBuilder));
                case StringSchemaBuilder stringSchemaBuilder ->
                        builder = builder.queryParameter(Parameters.param(validator.name,
                                stringSchemaBuilder));
                default ->
                        throw new IllegalArgumentException("Unsupported validator type: " + validator.getClass().getName());
            }
        }
        return builder;
    }

    public record QueryValidator<T extends SchemaBuilder<?, ?>>(String name,
                                                                T validator) {
    }
}
