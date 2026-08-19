package za.co.fraudruleengine.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that propagates a correlation ID through every inbound request to support
 * end-to-end request tracing across the Capitec DMC VAS platform.
 *
 * <p>The correlation ID is read from the {@code X-Capitec-Correlation-ID} request header.
 * If the header is absent (e.g. for direct calls or health probes), a new UUID is generated
 * so that every request has a traceable identity regardless of origin.
 *
 * <p>The resolved ID is:
 * <ol>
 *   <li>Placed in the SLF4J {@link MDC} under the key {@value #MDC_KEY} so that all log
 *       statements emitted during request processing automatically include it in the log
 *       pattern (see {@code log4j2.xml}).</li>
 *   <li>Written back to the response via the same {@value #CORRELATION_HEADER} header so
 *       that callers can correlate their own trace with the server-side log entry.</li>
 * </ol>
 *
 * <p>MDC cleanup is guaranteed by the {@code finally} block to prevent leakage across
 * thread-pool reuse. Without this, a Tomcat worker thread that is reused for a subsequent
 * request would still carry the previous request's correlation ID in its MDC context.
 *
 * <p>This filter is ordered first ({@code @Order(1)}) to ensure the correlation ID is
 * available in MDC before the JWT filter runs, so that authentication log entries are also
 * stamped with the correct correlation ID.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** The HTTP header name used by the Capitec DMC VAS platform for request tracing. */
    public static final String CORRELATION_HEADER = "X-Capitec-Correlation-ID";

    /** The MDC key under which the correlation ID is stored during request processing. */
    public static final String MDC_KEY = "correlationId";

    /**
     * Resolves the correlation ID from the request header (or generates one if absent),
     * stamps the MDC context, echoes the ID in the response header, and ensures MDC cleanup
     * after the request completes.
     *
     * @param request     the current HTTP request
     * @param response    the current HTTP response; the correlation header is added before the
     *                    response is committed so that callers receive it even on error responses
     * @param filterChain the remaining filter chain
     * @throws ServletException if the filter chain throws a servlet-level exception
     * @throws IOException      if an I/O error occurs during filter chain execution
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        // Echo the ID back — callers can log this to tie their trace to the server-side log entry
        response.setHeader(CORRELATION_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Must run even if the filter chain throws — thread pool workers are reused
            MDC.remove(MDC_KEY);
        }
    }
}
