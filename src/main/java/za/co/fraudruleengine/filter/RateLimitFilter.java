package za.co.fraudruleengine.filter;

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
import java.util.concurrent.locks.ReentrantLock;

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
 * {@code Retry-After} header (derived from {@code WINDOW_MS / 1000}) and a JSON error body — rather than
 * propagating the request to the application layer and wasting resources.
 *
 * <p><b>Algorithm</b>: fixed-window counter per 60-second period.  The window resets at the
 * start of each new minute.  This is simpler than a sliding-log approach and sufficient for
 * protecting against sustained high-rate clients; a sliding-window or token-bucket algorithm
 * may be swapped in here without changing the interface.
 *
 * <p><b>Thread safety</b>: per-IP state is held in {@link RateLimitSlot} instances each guarded
 * by a {@link ReentrantLock} rather than {@code synchronized} — {@code synchronized} blocks pin
 * virtual threads to their carrier thread, which defeats Project Loom's scalability benefit.
 * {@link ReentrantLock} suspends the virtual thread instead, freeing the carrier for other work.
 * Global state uses {@link AtomicInteger} which is always lock-free.
 */
@Slf4j
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    /** Window length in milliseconds (1 minute). */
    private static final long WINDOW_MS = 60_000L;

    /** Window length in seconds — used as the value of the {@code Retry-After} response header. */
    private static final String WINDOW_SECONDS = String.valueOf(WINDOW_MS / 1000);

    /** Endpoint path on which the per-IP transaction limit is enforced. */
    private static final String TRANSACTION_PATH = "/api/v1/transactions";

    /** Endpoint path on which the auth brute-force limit is enforced. */
    private static final String AUTH_PATH = "/api/v1/auth/token";

    // ── configuration (overridable via application.yml) ─────────────────────

    @Value("${rate-limit.transactions.enabled:true}")
    private boolean perIpEnabled;

    @Value("${rate-limit.transactions.requests-per-minute:120}")
    private int perIpLimit;

    @Value("${rate-limit.global.enabled:true}")
    private boolean globalEnabled;

    @Value("${rate-limit.global.requests-per-minute:1000}")
    private int globalLimit;

    /**
     * Enables brute-force protection on the authentication endpoint.
     * A strict per-IP limit prevents automated credential-stuffing and password-spray attacks.
     * Disable only in local/dev environments where the auth endpoint is used freely for testing.
     */
    @Value("${rate-limit.auth.enabled:true}")
    private boolean authEnabled;

    /**
     * Maximum authentication attempts per IP per minute (default: 10).
     * At 10 attempts/min an attacker would need 6 minutes per 60-guess dictionary — effectively
     * preventing any automated brute-force within a session window.
     */
    @Value("${rate-limit.auth.requests-per-minute:10}")
    private int authLimit;

    /**
     * When {@code true}, the {@code X-Forwarded-For} header is trusted for IP resolution.
     * <strong>Only enable this when the service sits behind a trusted reverse proxy or load
     * balancer that sets and sanitises the header.</strong>  Enabling it in a deployment without
     * a trusted proxy allows any caller to spoof their IP and bypass the per-IP rate limit by
     * rotating arbitrary values in the header.
     *
     * <p>Defaults to {@code false} — the direct {@code REMOTE_ADDR} is used.  Set to
     * {@code true} in environments where the service is fronted by an L7 load balancer
     * (e.g. AWS ALB, NGINX, Kubernetes ingress) that reliably populates the header.
     */
    @Value("${rate-limit.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    // ── state ────────────────────────────────────────────────────────────────

    /**
     * Per-IP counters for the transaction endpoint.
     * Key: client IP address.  Value: {@link RateLimitSlot} (ReentrantLock-guarded window + count).
     */
    private final ConcurrentHashMap<String, RateLimitSlot> perIpCounters = new ConcurrentHashMap<>();

    /** Per-IP counters for the authentication endpoint (brute-force protection). */
    private final ConcurrentHashMap<String, RateLimitSlot> perIpAuthCounters = new ConcurrentHashMap<>();

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
            sendTooManyRequests(response, "Global rate limit exceeded — please retry after " + WINDOW_SECONDS + " seconds.");
            return;
        }

        // 2. Per-IP limit — transaction evaluation endpoint only.
        // Use getRequestURI() as it is always populated; getServletPath() can be empty
        // in embedded containers and test environments when the dispatcher is mapped to "/".
        String requestPath = request.getServletPath();
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = request.getRequestURI();
        }
        if (perIpEnabled
                && "POST".equalsIgnoreCase(request.getMethod())
                && TRANSACTION_PATH.equals(requestPath)) {
            String clientIp = resolveClientIp(request);
            if (isPerIpLimitExceeded(clientIp)) {
                log.warn("rate-limit: per-IP limit ({} req/min) exceeded for client {} on {}",
                        perIpLimit, clientIp, TRANSACTION_PATH);
                sendTooManyRequests(response,
                        "Per-client rate limit exceeded — maximum " + perIpLimit
                        + " transaction evaluations per minute. Retry after " + WINDOW_SECONDS + " seconds.");
                return;
            }
        }

        // 3. Auth brute-force protection — strict per-IP limit on the token endpoint.
        // Prevents automated credential-stuffing and password-spray attacks.
        if (authEnabled
                && "POST".equalsIgnoreCase(request.getMethod())
                && AUTH_PATH.equals(requestPath)) {
            String clientIp = resolveClientIp(request);
            if (isAuthLimitExceeded(clientIp)) {
                log.warn("rate-limit: auth limit ({} req/min) exceeded for client {} — possible brute-force",
                        authLimit, clientIp);
                sendTooManyRequests(response,
                        "Authentication rate limit exceeded — maximum " + authLimit
                        + " attempts per minute. Retry after " + WINDOW_SECONDS + " seconds.");
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
        return isLimitExceeded(perIpCounters, clientIp, perIpLimit);
    }

    /**
     * Returns {@code true} if the authentication attempt rate for the given IP has exceeded
     * the configured limit within the current window.
     *
     * <p>Uses a separate counter map from the transaction limit so that the two controls
     * operate independently and can be tuned or disabled individually.
     *
     * @param clientIp resolved client IP address
     */
    private boolean isAuthLimitExceeded(String clientIp) {
        return isLimitExceeded(perIpAuthCounters, clientIp, authLimit);
    }

    /**
     * Core fixed-window counter logic shared by the per-IP transaction and auth limits.
     *
     * <p>Each entry in {@code counters} is a {@link RateLimitSlot} holding the window start
     * timestamp and a request count, guarded by a {@link ReentrantLock}.  Using
     * {@code ReentrantLock} rather than {@code synchronized} is critical for virtual-thread
     * compatibility: {@code synchronized} pins the virtual thread to its carrier (blocking the
     * carrier for other virtual threads), whereas {@code ReentrantLock} merely suspends the
     * virtual thread and returns the carrier to the pool while waiting.
     *
     * @param counters the counter map keyed by client IP
     * @param clientIp the resolved client IP address used as the map key
     * @param limit    the maximum number of requests allowed within the window
     * @return {@code true} when the request count exceeds {@code limit}; {@code false} otherwise
     */
    private boolean isLimitExceeded(ConcurrentHashMap<String, RateLimitSlot> counters,
                                    String clientIp, int limit) {
        long now = System.currentTimeMillis();
        RateLimitSlot slot = counters.computeIfAbsent(clientIp, k -> new RateLimitSlot(now));
        slot.lock.lock();
        try {
            if (now - slot.windowStart >= WINDOW_MS) {
                slot.windowStart = now;
                slot.count = 1;
                return false;
            }
            slot.count++;
            return slot.count > limit;
        } finally {
            slot.lock.unlock();
        }
    }

    /**
     * Per-IP rate-limit slot holding window state, guarded by a {@link ReentrantLock}.
     *
     * <p>Using {@link ReentrantLock} instead of {@code synchronized} ensures virtual-thread
     * compatibility — a virtual thread waiting to acquire this lock is suspended (not pinned),
     * so its carrier thread is released to schedule other virtual threads in the meantime.
     */
    private static final class RateLimitSlot {
        final ReentrantLock lock = new ReentrantLock();
        long windowStart;
        long count;

        RateLimitSlot(long windowStart) {
            this.windowStart = windowStart;
            this.count = 0;
        }
    }

    /**
     * Resolves the client IP used for per-IP rate limiting.
     *
     * <p>When {@code rate-limit.trust-proxy-headers=true} the {@code X-Forwarded-For} header
     * is consulted first.  The leftmost entry in the comma-separated chain is taken as the
     * originating client IP (the standard convention for L7 proxies/load balancers).
     *
     * <p><strong>Security note:</strong> {@code X-Forwarded-For} is only trusted when
     * {@code trustProxyHeaders=true} because the header can be freely set by any client.
     * Trusting it unconditionally would allow an attacker to rotate arbitrary IPs and bypass
     * the per-IP limit entirely.  Only enable proxy header trust when the service is deployed
     * behind a reverse proxy that strips and re-sets the header from the real client address.
     *
     * @param request the incoming HTTP request
     * @return the resolved client IP address used as the rate-limit key
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // X-Forwarded-For may be a comma-separated chain; take the leftmost (originating client)
                return forwarded.split(",")[0].trim();
            }
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
        response.setHeader("Retry-After", WINDOW_SECONDS);
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\""
                + message + "\"}");
    }
}
