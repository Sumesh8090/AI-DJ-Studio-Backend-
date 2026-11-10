package com.djstudio.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Returns the standard JSON error envelope with a 401 for unauthenticated requests. */
@Component
public class ApiAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        Object reason = request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTRIBUTE);
        boolean hadToken = reason != null;

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", hadToken ? "invalid_token" : "not_authenticated");
        error.put("message", hadToken ? reason.toString() : "Missing bearer token.");

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", error));
    }
}
