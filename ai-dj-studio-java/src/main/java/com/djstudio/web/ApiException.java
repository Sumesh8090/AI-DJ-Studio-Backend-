package com.djstudio.web;

import java.util.List;

/** An expected, client-facing failure. Rendered as {"error": {"code", "message", "fields"?}}. */
public class ApiException extends RuntimeException {

    public record FieldIssue(String field, String message) {
    }

    private final int status;
    private final String code;
    private final List<FieldIssue> fields;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, String code, String message, List<FieldIssue> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    /** 422 for a single invalid field. */
    public static ApiException invalid(String field, String message) {
        return new ApiException(422, "validation_error", field + ": " + message,
                List.of(new FieldIssue(field, message)));
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldIssue> fields() {
        return fields;
    }
}
