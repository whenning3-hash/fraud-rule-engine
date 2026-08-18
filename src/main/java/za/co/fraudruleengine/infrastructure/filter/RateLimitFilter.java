package za.co.fraudruleengine.infrastructure.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servlet filter that applies sliding-window rate limiting to protect the fraud evaluation
 * endpoint against DoS attacks and runaway clients.
 *
 * <p>Two independent limits are enforced:
 * <ol>
 *   <li><b>Per-IP limit</b> (default 120 req/min) — applied to {@code POST /api/v1/transactions}
 *       only.  Prevents any single client from monopolising rule evaluation capacity.</li>
 *   <li><b>Global limit</b> (default 1000 req/min) — applied to every request.  Prevents
 *       server-wide overload when many clients send bursts simultaneously.</li>
 * </ol>
 *
 * <p>When a limit is exceeded the filter returns {@code 429 Too Many Requests} with a
 * {@code Retry-After} header (fixed at 60 seconds) and a JSON error body — rather than
 * propagating the request to the application layer and wasting resources.
 *
 * <p><b>Algorithm</b>: fixed-window counter per 60-second period.  The window resets at the
 * start of each new minute.  This is simpler than a sliding-log approach and sufficient for
 * protecting against sustained high-rate clients; a sliding-window or token-bucket algorithm
 * may be swapped in here without changing the interface.
 *
 * <p><b>Thread safety</b>: all state is held in {@link ConcurrentHashMap}/{@link AtomicInteger};
 * no synchronised blocks are needed.
 */
@Slf4j
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    /** Window length in milliseconds (1 minute). */
    private static final long WINDOW_MS = 60_000L;

    /** Endpoint path on which the per-IP limit is enforced. */
    private static final String TRANSACTION_PATH = "/api/v1/transactions";

    // ── configuration (overridable via application.yml) ─────────────────────

    @Value("${rate-limit.transactions.enabled:true}")
    private boolean perIpEnabled;

    @Value("${rate-limit.transactions.requests-per-minute:120}")
    private int perIpLimit;

    @Value("${rate-limit.global.enabled:true}")
    private boolean globalEnabled;

    @Value("${rate-limit.global.requests-per-minute:1000}")
    private int globalLimit;

    // ── state ────────────────────────────────────────────────────────────────

    /**
     * Per-IP counters for the transaction endpoint.
     * Key: client IP address.  Value: {@code long[2]}{windowStart, requestCount}.
     */
    private final ConcurrentHashMap<String, long[]> perIpCounters = new ConcurrentHashMap<>();

    /** Global counter shared across all clients. */
    private volatile long globalWindowStart = System.currentTimeMillis();
    private final AtomicInteger globalCounter = new AtomicInteger(0);

    // ── filter logic ─────────────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // 1. Global rate limit check
        if (globalEnabled && isGlobalLimitExceeded()) {
            log.warn("rate-limit: global limit ({} req/min) exceeded — rejected {} {}",
                    globalLimit, request.getMethod(), request.getRequestURI());
            sendTooManyRequests(response, "Global rate limit exceeded — please retry after 60 seconds.");
            return;
        }

        // 2. Per-IP limit — transaction evaluation endpoint only
        if (perIpEnabled
                && "POST".equalsIgnoreCase(request.getMethod())
                && TRANSACTION_PATH.equals(request.getServletPath())) {
            String clientIp = resolveClientIp(request);
            if (isPerIpLimitExceeded(clientIp)) {
                log.warn("rate-limit: per-IP limit ({} req/min) exceeded for client {} on {}",
                        perIpLimit, clientIp, TRANSACTION_PATH);
                sendTooManyRequests(response,
                        "Per-client rate limit exceeded — maximum " + perIpLimit
                        + " transaction evaluations per minute. Retry after 60 seconds.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} and increments the global counter if the global limit is exceeded
     * for the current window.  Resets the window when a new minute starts.
     */
    private boolean isGlobalLimitExceeded() {
        long now = System.currentTimeMillis();
        if (now - globalWindowStart >= WINDOW_MS) {
            // New window — reset.  Race is harmless: the worst case is a few extra requests
            // slip through at window boundary, which is acceptable for a soft rate limit.
            globalWindowStart = now;
            globalCounter.set(1);
            return false;
        }
        return globalCounter.incrementAndGet() > globalLimit;
    }

    /**
     * Returns {@code true} and increments the per-IP counter if the per-IP limit is exceeded
     * for the current window.
     *
     * @param clientIp resolved client IP address
     */
    private boolean isPerIpLimitExceeded(String clientIp) {
        long now = System.currentTimeMillis();
        long[] slot = perIpCounters.computeIfAbsent(clientIp, k -> new long[]{now, 0});
        synchronized (slot) {
            if (now - slot[0] >= WINDOW_MS) {
                slot[0] = now;
                slot[1] = 1;
                return false;
            }
            slot[1]++;
            return slot[1] > perIpLimit;
        }
    }

    /**
     * Resolves the real client IP, honouring {@code X-Forwarded-For} set by a load balancer
     * or reverse proxy.  Falls back to {@link HttpServletRequest#getRemoteAddr()} when no
     * proxy header is present.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For may be a comma-separated chain; take the leftmost (originating client)
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Writes a {@code 429 Too Many Requests} response with a JSON body and
     * {@code Retry-After: 60} header.
     */
    private void sendTooManyRequests(HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\""
                + message + "\"}");
    }
}
