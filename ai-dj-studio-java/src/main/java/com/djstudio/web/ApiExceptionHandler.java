package com.djstudio.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** One consistent error envelope: {"error": {"code", "message", "fields"?}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(envelope(e.code(), e.getMessage(), e.fields()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        List<ApiException.FieldIssue> fields = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiException.FieldIssue(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
        return validationFailure(fields);
    }

    /** Wrong JSON type / unparsable value, e.g. {"bpm": "fast"} - name the offending field. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(n -> n != null)
                    .collect(Collectors.joining("."));
            return validationFailure(List.of(new ApiException.FieldIssue(field, "Value has the wrong type or format.")));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope("malformed_json", "Request body is missing or is not valid JSON.", List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return validationFailure(List.of(new ApiException.FieldIssue(e.getName(), "Value has the wrong type or format.")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return validationFailure(List.of(new ApiException.FieldIssue(e.getParameterName(), "This parameter is required.")));
    }

    private static ResponseEntity<Map<String, Object>> validationFailure(List<ApiException.FieldIssue> fields) {
        String summary = fields.stream().map(f -> f.field() + ": " + f.message()).collect(Collectors.joining("; "));
        return ResponseEntity.unprocessableEntity().body(envelope("validation_error", summary, fields));
    }

    private static Map<String, Object> envelope(String code, String message, List<ApiException.FieldIssue> fields) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (!fields.isEmpty()) {
            error.put("fields", fields);
        }
        return Map.of("error", error);
    }
}
