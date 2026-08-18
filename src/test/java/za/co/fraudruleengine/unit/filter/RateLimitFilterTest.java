package za.co.fraudruleengine.unit.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import za.co.fraudruleengine.infrastructure.filter.RateLimitFilter;

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
 *   <li>{@code X-Forwarded-For} header is honoured for real-IP detection behind a proxy.</li>
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
    void xForwardedForHeader_usedAsClientIp() throws Exception {
        // Behind a load balancer: X-Forwarded-For contains the real client IP
        for (int i = 0; i < 4; i++) {
            MockHttpServletRequest req = txPost("10.0.0.1"); // load balancer IP
            req.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.1");
            if (i < 3) {
                int status = invoke(req);
                assertThat(status).isNotEqualTo(429);
            } else {
                // 4th request from the same forwarded IP must be rate-limited
                MockHttpServletResponse resp = new MockHttpServletResponse();
                filter.doFilter(req, resp, mock(FilterChain.class));
                assertThat(resp.getStatus())
                        .as("4th request from X-Forwarded-For IP must return 429")
                        .isEqualTo(429);
            }
        }
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
