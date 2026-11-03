package com.judgex.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judgex.web.dto.Dto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Stateless bearer-token gate. Rejects early, before any controller runs.
 */
@Component
@Order(10)
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTR = "judgex.user";

    private static final Set<String> PUBLIC = Set.of(
            "/api/auth/login",
            "/api/health",
            "/api/languages"
    );

    private final JwtService jwt;
    private final ObjectMapper mapper;

    public AuthFilter(JwtService jwt, ObjectMapper mapper) {
        this.jwt = jwt;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || PUBLIC.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = bearer(request);
        if (token == null) {
            token = request.getParameter("token"); // EventSource cannot set headers
        }
        UserPrincipal user = jwt.verify(token);
        if (user == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "missing or expired token");
            return;
        }
        if (request.getRequestURI().startsWith("/api/admin") && !user.isAdmin()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "admin role required");
            return;
        }
        request.setAttribute(USER_ATTR, user);
        chain.doFilter(request, response);
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7).trim();
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(),
                new Dto.ApiError(code, message, MDC.get("traceId")));
    }
}
