package com.codesage.security;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class JsonAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (ex instanceof CsrfException) {
            JsonErrorWriter.write(request, response, 403, "CSRF_INVALID",
                    "Missing or invalid CSRF token. Refresh the page and try again.");
            return;
        }
        JsonErrorWriter.write(request, response, 403, "FORBIDDEN", "Access denied");
    }
}
