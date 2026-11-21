package com.nexus.hub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.common.dto.ApiResponse;
import com.nexus.hub.modules.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * The only endpoint where a JWT may be passed as {@code ?token=}: native browser EventSource (SSE)
     * cannot set an Authorization header. Everywhere else query-string tokens are ignored, since they
     * leak into access logs, browser history and Referer headers.
     */
    private static final Pattern SSE_STREAM_PATH = Pattern.compile("^/api/v1/tasks/[^/]+/stream/?$");

    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String jwt = resolveToken(request);

        if (!StringUtils.hasText(jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean revoked;
        try {
            revoked = tokenRevocationService.isTokenRevoked(jwt);
        } catch (TokenRevocationUnavailableException ex) {
            // Fail closed: we cannot prove the token was not revoked, so do not authenticate.
            writeServiceUnavailable(response);
            return;
        }

        if (revoked) {
            log.warn("Blocked request with revoked JWT token");
            filterChain.doFilter(request, response);
            return;
        }

        if (jwtService.isTokenValid(jwt) && SecurityContextHolder.getContext().getAuthentication() == null) {
            String userEmail = jwtService.extractUsername(jwt);

            userRepository.findByEmail(userEmail).ifPresent(user -> {
                if (user.isEnabled()) {
                    UserPrincipal principal = UserPrincipal.create(user);
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            principal.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            });
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Authorization: Bearer header everywhere; {@code ?token=} only for GET /api/v1/tasks/{id}/stream.
     */
    String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        if (isSseStreamRequest(request)) {
            String queryToken = request.getParameter("token");
            if (StringUtils.hasText(queryToken)) {
                return queryToken;
            }
        }
        return null;
    }

    private boolean isSseStreamRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null) {
            return false;
        }
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return SSE_STREAM_PATH.matcher(uri).matches();
    }

    private void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "5");
        ApiResponse<Void> body = ApiResponse.error(
                "Authentication service temporarily unavailable. Please retry shortly.",
                Map.of("code", "TOKEN_REVOCATION_UNAVAILABLE"));
        objectMapper.writeValue(response.getWriter(), body);
    }
}
