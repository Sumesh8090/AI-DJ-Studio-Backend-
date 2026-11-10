package com.djstudio.web.dto;

import com.djstudio.core.CamelotWheel;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CamelotKeyValidator implements ConstraintValidator<CamelotKey, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || CamelotWheel.isValid(value);
    }
}
