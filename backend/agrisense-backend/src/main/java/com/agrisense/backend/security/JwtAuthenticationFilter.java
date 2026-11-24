package com.agrisense.backend.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/*
 * Requires a valid "Authorization: Bearer <token>" on every /...api
 * endpoint except login and register. CORS preflight is let through.
 * On success the farmer id, email and role are exposed as request
 * attributes for controllers that need them.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String FARMER_ID_ATTR = "agrisense.farmerId";
    public static final String FARMER_EMAIL_ATTR = "agrisense.farmerEmail";
    public static final String FARMER_ROLE_ATTR = "agrisense.farmerRole";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/farmerapi/login",
            "/farmerapi/register"
    );

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {

        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }

        return !isApiPath(path);
    }

    // Protected API paths start with a segment ending in "api", e.g. /farmapi/...
    private boolean isApiPath(String path) {

        if (!path.startsWith("/")) {
            return false;
        }

        int end = path.indexOf('/', 1);
        String first = end == -1 ? path.substring(1) : path.substring(1, end);

        return first.endsWith("api");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            reject(response, "Missing bearer token");
            return;
        }

        Claims claims = jwtService.parse(header.substring(7).trim());

        if (claims == null) {
            reject(response, "Invalid or expired token");
            return;
        }

        request.setAttribute(FARMER_ID_ATTR, claims.getSubject());
        request.setAttribute(FARMER_EMAIL_ATTR,
                claims.get(JwtService.EMAIL_CLAIM, String.class));
        request.setAttribute(FARMER_ROLE_ATTR,
                claims.get(JwtService.ROLE_CLAIM, String.class));

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message)
            throws IOException {

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
