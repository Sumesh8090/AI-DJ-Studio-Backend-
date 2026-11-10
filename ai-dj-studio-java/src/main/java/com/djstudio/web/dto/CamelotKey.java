package com.djstudio.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The value must be one of the 24 Camelot wheel keys (1A-12A, 1B-12B). Null is left to @NotNull. */
@Documented
@Constraint(validatedBy = CamelotKeyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CamelotKey {

    String message() default "Invalid Camelot key: expected one of 1A-12A or 1B-12B (24 valid keys).";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
