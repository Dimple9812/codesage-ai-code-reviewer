package com.codesage.security;

import java.io.IOException;
import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.codesage.config.AppProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Per-user (or per-IP for anonymous calls) request rate limiting for the REST API. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final AppProperties props;
    private final CurrentUser currentUser;

    public RateLimitFilter(RateLimiter limiter, AppProperties props, CurrentUser currentUser) {
        this.limiter = limiter;
        this.props = props;
        this.currentUser = currentUser;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.rateLimit().enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String principal = currentUser.currentId().map(id -> "u:" + id).orElse("ip:" + request.getRemoteAddr());
        if (!limiter.tryAcquire("api:" + principal, props.rateLimit().requestsPerMinute(), Duration.ofMinutes(1))) {
            response.setHeader("Retry-After", "60");
            JsonErrorWriter.write(request, response, 429, "RATE_LIMITED", "Too many requests. Please slow down.");
            return;
        }
        chain.doFilter(request, response);
    }
}
