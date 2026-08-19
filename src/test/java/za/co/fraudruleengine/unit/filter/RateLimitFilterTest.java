package za.co.fraudruleengine.unit.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import za.co.fraudruleengine.filter.RateLimitFilter;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>Directly exercises the filter logic without requiring a Spring context or Testcontainers.
 * Uses Spring's {@link MockHttpServletRequest} / {@link MockHttpServletResponse} so that
 * {@code OncePerRequestFilter}'s "already-filtered" attribute is handled correctly — a new
 * request object is created for each invocation so the filter is never skipped.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Requests below the per-IP threshold are allowed through (filter chain continues).</li>
 *   <li>Requests that exceed the per-IP threshold receive a 429 response with
 *       {@code Retry-After: 60}; the filter chain is NOT continued.</li>
 *   <li>The per-IP limit applies only to {@code POST /api/v1/transactions}; other
 *       methods and paths are not counted.</li>
 *   <li>Different client IPs maintain independent counters.</li>
 *   <li>{@code X-Forwarded-For} is ignored by default (prevents IP spoofing); honoured only
 *       when {@code rate-limit.trust-proxy-headers=true} to verify trusted proxy deployment.</li>
 *   <li>The global limit returns 429 once the server-wide threshold is breached.</li>
 *   <li>Disabling either limit via configuration skips the corresponding check entirely.</li>
 * </ul>
 */
class RateLimitFilterTest {

    private static final String TX_PATH = "/api/v1/transactions";
    private static final String RULES_PATH = "/api/v1/rules";
    private static final String LOCAL_IP = "127.0.0.1";

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        // Set small limits so tests don't need to send hundreds of requests
        ReflectionTestUtils.setField(filter, "perIpEnabled", true);
        ReflectionTestUtils.setField(filter, "perIpLimit", 3);
        ReflectionTestUtils.setField(filter, "globalEnabled", true);
        ReflectionTestUtils.setField(filter, "globalLimit", 100); // high — don't interfere with per-IP tests
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "authLimit", 3);     // small — same as perIpLimit for test symmetry
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Creates a fresh {@link MockHttpServletRequest} for each call so that
     * {@link org.springframework.web.filter.OncePerRequestFilter}'s "already filtered"
     * attribute is cleared.  Each call therefore flows through the full filter logic.
     */
    private MockHttpServletRequest txPost(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", TX_PATH);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private MockHttpServletRequest getRequest(String path, String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    /** Invokes the filter and returns the response status code. */
    private int invoke(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);
        // If the chain was NOT called the filter blocked the request; read status from response.
        // If the chain WAS called, the response status defaults to 200 (not set by filter).
        return resp.getStatus();
    }

    /** Returns true if the mock filter chain was called (request was allowed through). */
    private boolean wasAllowed(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);
        // verify the chain was invoked
        try {
            verify(chain).doFilter(req, resp);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    // ── per-IP limit ───────────────────────────────────────────────────────────

    @Test
    void requestsBelowPerIpThreshold_areAllowed() throws Exception {
        // perIpLimit = 3; requests 1–3 must all be allowed
        for (int i = 0; i < 3; i++) {
            int status = invoke(txPost(LOCAL_IP));
            assertThat(status)
                    .as("Request %d of 3 must be allowed (status should NOT be 429)", i + 1)
                    .isNotEqualTo(429);
        }
    }

    @Test
    void requestAbovePerIpThreshold_returns429() throws Exception {
        // Allow requests 1–3, then check that request 4 is rejected
        for (int i = 0; i < 3; i++) {
            invoke(txPost(LOCAL_IP));
        }
        int status = invoke(txPost(LOCAL_IP));
        assertThat(status)
                .as("4th request with limit=3 must return 429")
                .isEqualTo(429);
    }

    @Test
    void rateLimited429Response_includesRetryAfterHeader() throws Exception {
        // Exhaust limit, then verify response headers on the over-limit request
        for (int i = 0; i < 3; i++) {
            invoke(txPost(LOCAL_IP));
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(txPost(LOCAL_IP), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getHeader("Retry-After"))
                .as("429 response must include Retry-After: 60")
                .isEqualTo("60");
    }

    @Test
    void rateLimited429Response_bodyContainsJsonMessage() throws Exception {
        for (int i = 0; i < 3; i++) {
            invoke(txPost(LOCAL_IP));
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(txPost(LOCAL_IP), resp, mock(FilterChain.class));

        String body = resp.getContentAsString();
        assertThat(body)
                .as("429 body must be a JSON object with status 429 and error message")
                .contains("\"status\":429")
                .contains("Too Many Requests");
    }

    @Test
    void perIpLimit_doesNotApplyToGetRequests() throws Exception {
        // GET requests on the transaction path must not be rate-limited by the per-IP rule
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest getReq = new MockHttpServletRequest("GET", TX_PATH);
            getReq.setRemoteAddr(LOCAL_IP);
            int status = invoke(getReq);
            assertThat(status)
                    .as("GET request %d must never be per-IP rate-limited", i + 1)
                    .isNotEqualTo(429);
        }
    }

    @Test
    void perIpLimit_doesNotApplyToOtherPostPaths() throws Exception {
        // POST to a different path should not be counted against the transaction per-IP limit
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest postRules = new MockHttpServletRequest("POST", RULES_PATH);
            postRules.setRemoteAddr(LOCAL_IP);
            int status = invoke(postRules);
            assertThat(status)
                    .as("POST to /api/v1/rules must not be per-IP rate-limited")
                    .isNotEqualTo(429);
        }
    }

    @Test
    void differentClientIps_haveIndependentCounters() throws Exception {
        String ipA = "192.168.1.1";
        String ipB = "192.168.1.2";

        // IP A reaches its limit (3 requests)
        for (int i = 0; i < 3; i++) {
            invoke(txPost(ipA));
        }
        // IP A's 4th request is rate-limited
        assertThat(invoke(txPost(ipA))).isEqualTo(429);

        // IP B has made 0 requests; its counter is independent — first 3 must be allowed
        for (int i = 0; i < 3; i++) {
            int status = invoke(txPost(ipB));
            assertThat(status)
                    .as("IP B request %d must not be affected by IP A's counter", i + 1)
                    .isNotEqualTo(429);
        }
    }

    @Test
    void xForwardedForHeader_ignoredByDefault_remoteAddrUsed() throws Exception {
        // SECURITY: trustProxyHeaders defaults to false to prevent IP spoofing.
        // Even when X-Forwarded-For claims two different IPs, the filter uses remoteAddr.
        // Both requests come from the same remoteAddr (10.0.0.1) so they share a counter.
        ReflectionTestUtils.setField(filter, "trustProxyHeaders", false);

        // 3 requests: spoofed X-Forwarded-For headers claiming to be different IPs
        MockHttpServletRequest req1 = txPost("10.0.0.1");
        req1.addHeader("X-Forwarded-For", "203.0.113.1");
        invoke(req1);

        MockHttpServletRequest req2 = txPost("10.0.0.1");
        req2.addHeader("X-Forwarded-For", "203.0.113.2"); // different spoofed IP
        invoke(req2);

        MockHttpServletRequest req3 = txPost("10.0.0.1");
        req3.addHeader("X-Forwarded-For", "203.0.113.3"); // yet another spoofed IP
        invoke(req3);

        // 4th request — all 4 share remoteAddr=10.0.0.1, so limit IS reached
        // (the X-Forwarded-For IP rotation did NOT bypass the limit)
        MockHttpServletRequest req4 = txPost("10.0.0.1");
        req4.addHeader("X-Forwarded-For", "203.0.113.4");
        assertThat(invoke(req4))
                .as("Rotating X-Forwarded-For must NOT bypass rate limit — remoteAddr is used")
                .isEqualTo(429);
    }

    @Test
    void xForwardedForHeader_usedWhenTrustProxyEnabled() throws Exception {
        // When trustProxyHeaders=true (behind a trusted L7 LB), the real client IP is taken
        // from the leftmost entry in X-Forwarded-For.
        ReflectionTestUtils.setField(filter, "trustProxyHeaders", true);

        String lbIp = "10.0.0.1";         // load balancer's address (same for all requests)
        String clientIp = "203.0.113.99"; // real client IP populated by the load balancer

        // 3 requests from the same real client IP — all should be allowed
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = txPost(lbIp);
            req.addHeader("X-Forwarded-For", clientIp + ", " + lbIp);
            assertThat(invoke(req))
                    .as("Request %d of 3 must be allowed when trustProxyHeaders=true", i + 1)
                    .isNotEqualTo(429);
        }

        // 4th request from the same real client IP — must be rate-limited
        MockHttpServletRequest req4 = txPost(lbIp);
        req4.addHeader("X-Forwarded-For", clientIp + ", " + lbIp);
        assertThat(invoke(req4))
                .as("4th request from X-Forwarded-For client IP must return 429 when trustProxyHeaders=true")
                .isEqualTo(429);
    }

    // ── auth brute-force limit ─────────────────────────────────────────────────

    @Test
    void authRequestsBelowLimit_areAllowed() throws Exception {
        // authLimit = 3; first 3 attempts from same IP must be allowed through
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
            req.setRemoteAddr(LOCAL_IP);
            assertThat(invoke(req))
                    .as("Auth attempt %d of 3 must not be rate-limited", i + 1)
                    .isNotEqualTo(429);
        }
    }

    @Test
    void authRequestsAboveLimit_returns429() throws Exception {
        // Exhaust the auth limit, then verify the next attempt is blocked
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
            req.setRemoteAddr(LOCAL_IP);
            invoke(req);
        }
        MockHttpServletRequest req4 = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        req4.setRemoteAddr(LOCAL_IP);
        assertThat(invoke(req4))
                .as("4th auth attempt with limit=3 must return 429 — brute-force protection")
                .isEqualTo(429);
    }

    @Test
    void authLimit_isIndependentOfTransactionLimit() throws Exception {
        // Exhaust the transaction limit — auth endpoint must still be accessible
        for (int i = 0; i < 3; i++) {
            invoke(txPost(LOCAL_IP));
        }
        MockHttpServletRequest authReq = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        authReq.setRemoteAddr(LOCAL_IP);
        assertThat(invoke(authReq))
                .as("Auth endpoint must have its own independent counter from transactions")
                .isNotEqualTo(429);
    }

    // ── global limit ───────────────────────────────────────────────────────────

    @Test
    void requestsBelowGlobalThreshold_areAllowed() throws Exception {
        ReflectionTestUtils.setField(filter, "globalLimit", 5);
        for (int i = 0; i < 5; i++) {
            // Use GET + different IPs so per-IP limit doesn't apply
            int status = invoke(getRequest(RULES_PATH, "10.0.0." + i));
            assertThat(status).isNotEqualTo(429);
        }
    }

    @Test
    void requestAboveGlobalThreshold_returns429() throws Exception {
        ReflectionTestUtils.setField(filter, "globalLimit", 3);
        // Use different IPs to isolate from the per-IP limit
        for (int i = 0; i < 3; i++) {
            invoke(getRequest(RULES_PATH, "10.0.0." + i));
        }
        int status = invoke(getRequest(RULES_PATH, "10.0.0.99"));
        assertThat(status)
                .as("Request beyond global limit must return 429")
                .isEqualTo(429);
    }

    // ── virtual-thread safety ──────────────────────────────────────────────────

    @Test
    @DisplayName("RateLimitSlot uses ReentrantLock — no synchronized block that would pin virtual threads")
    void rateLimitSlot_usesReentrantLock_notSynchronized() throws Exception {
        // Structural guard: verifies the per-IP counter map holds RateLimitSlot instances
        // whose lock field is a ReentrantLock — not a synchronized primitive.
        // If someone changes this back to long[] + synchronized, virtual threads will pin
        // to carrier threads under load and this test will catch the regression.
        ReflectionTestUtils.setField(filter, "perIpEnabled", true);
        ReflectionTestUtils.setField(filter, "perIpLimit", 100);

        // Trigger slot creation by sending one request
        invoke(txPost("192.168.99.1"));

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, ?> counters =
                (ConcurrentHashMap<String, ?>) ReflectionTestUtils.getField(filter, "perIpCounters");
        assertThat(counters).isNotNull().isNotEmpty();

        Object slot = counters.get("192.168.99.1");
        assertThat(slot).as("slot must not be null after first request").isNotNull();

        // The slot must expose a ReentrantLock field named 'lock'
        Field lockField = slot.getClass().getDeclaredField("lock");
        lockField.setAccessible(true);
        Object lock = lockField.get(slot);
        assertThat(lock)
                .as("RateLimitSlot.lock must be a ReentrantLock — synchronized blocks pin virtual threads")
                .isInstanceOf(ReentrantLock.class);
    }

    @Test
    @DisplayName("Concurrent virtual threads — exactly limit requests allowed, rest blocked (thread-safe counter)")
    void concurrentRequests_exactlyLimitAllowed_reentrantLockThreadSafe() throws Exception {
        // Verifies that the ReentrantLock-based slot handles concurrent access correctly.
        // With 50 virtual threads all hitting the same IP simultaneously against a limit of 10,
        // exactly 10 must be allowed and 40 must receive 429. Any number other than 10 would
        // indicate a race condition in the counter.
        final int limit = 10;
        final int totalRequests = 50;
        ReflectionTestUtils.setField(filter, "perIpLimit", limit);
        ReflectionTestUtils.setField(filter, "globalLimit", 10000); // don't interfere

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger blocked = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1); // all threads start simultaneously
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await(); // hold until all threads are ready
                    int status = invoke(txPost(LOCAL_IP));
                    if (status == 429) {
                        blocked.incrementAndGet();
                    } else {
                        allowed.incrementAndGet();
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all 50 threads simultaneously
        doneLatch.await();      // wait for all to complete

        assertThat(allowed.get())
                .as("Exactly %d requests should be allowed (limit=%d, total=%d)", limit, limit, totalRequests)
                .isEqualTo(limit);
        assertThat(blocked.get())
                .as("Exactly %d requests should be blocked", totalRequests - limit)
                .isEqualTo(totalRequests - limit);
    }

    // ── disabled limits ────────────────────────────────────────────────────────

    @Test
    void perIpLimitDisabled_noThrottling() throws Exception {
        ReflectionTestUtils.setField(filter, "perIpEnabled", false);
        for (int i = 0; i < 20; i++) {
            assertThat(invoke(txPost(LOCAL_IP))).isNotEqualTo(429);
        }
    }

    @Test
    void globalLimitDisabled_noThrottling() throws Exception {
        ReflectionTestUtils.setField(filter, "globalEnabled", false);
        ReflectionTestUtils.setField(filter, "globalLimit", 1); // would fire if enabled
        for (int i = 0; i < 10; i++) {
            assertThat(invoke(getRequest(RULES_PATH, "10.0.0." + i))).isNotEqualTo(429);
        }
    }
}
