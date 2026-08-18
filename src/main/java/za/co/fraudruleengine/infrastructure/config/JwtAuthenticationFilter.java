package za.co.fraudruleengine.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Servlet filter that extracts a JWT Bearer token from the {@code Authorization} request header,
 * validates it, and populates the Spring {@link SecurityContextHolder} with the authenticated
 * principal.
 *
 * <p>Extends {@link OncePerRequestFilter} to guarantee exactly one execution per request, even
 * when a request is dispatched or forwarded internally within the servlet container.
 *
 * <p>The filter follows the standard Bearer Token authentication flow:
 * <ol>
 *   <li>Extract the token from the {@code Authorization: Bearer <token>} header.</li>
 *   <li>Validate the signature and expiry via {@link JwtTokenProvider#validateToken}.</li>
 *   <li>Extract the subject (username) from the token claims.</li>
 *   <li>Build an {@link UsernamePasswordAuthenticationToken} with no granted authorities
 *       (this application does not use role-based access control beyond authenticated vs
 *       unauthenticated).</li>
 *   <li>Set the authentication in the {@link SecurityContextHolder} so that downstream
 *       security checks see the request as authenticated.</li>
 * </ol>
 *
 * <p>If the token is absent, malformed, or expired, the filter chain continues without setting
 * an authentication object. The subsequent security filter ({@code ExceptionTranslationFilter})
 * will then reject the request with HTTP 401 for any protected endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Core filter logic: validates the JWT and populates the security context if valid.
     *
     * @param request     the incoming HTTP request
     * @param response    the HTTP response (not modified by this filter)
     * @param filterChain the remaining filter chain; always invoked regardless of token validity
     * @throws ServletException if the filter chain throws a servlet-level exception
     * @throws IOException      if an I/O error occurs during filter chain execution
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            String subject = jwtTokenProvider.getSubject(token);
            log.debug("JWT authenticated — subject: {}, uri: {}", subject, request.getRequestURI());

            // Build an authentication token with no credentials and an empty authority list;
            // the mere presence of the authentication object signals "authenticated" to Spring Security
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(subject, null, List.of());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the raw JWT string from the {@code Authorization} header.
     *
     * <p>The header must be in the format {@code Bearer <token>} (the prefix is case-sensitive
     * as per RFC 6750). If the header is absent, blank, or does not start with {@code "Bearer "},
     * {@code null} is returned and the filter treats the request as unauthenticated.
     *
     * @param request the HTTP request to extract the token from
     * @return the raw JWT string (without the {@code "Bearer "} prefix), or {@code null} if absent
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        // Skip the first 7 characters ("Bearer ") to get the raw token string
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
