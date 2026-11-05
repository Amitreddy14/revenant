package io.github.amitreddy14.revenant.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses any {@code /api/*} request that does not carry a valid {@code Authorization: Bearer <key>}
 * header with status 401 (ADR-0014). It skips every other path, so the health endpoint that load
 * balancers and Docker probe stays open, and it does nothing at all when no keys are configured.
 */
final class ApiKeyFilter extends OncePerRequestFilter {

    private final ApiKeys keys;

    ApiKeyFilter(ApiKeys keys) {
        this.keys = keys;
    }

    /** Only the API is protected; the health probe and everything else pass untouched. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !keys.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (keys.accepts(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // The same answer for "no key" and "wrong key": do not help a guesser.
        response.getWriter().write("{\"error\":\"a valid API key is required\"}");
    }
}
