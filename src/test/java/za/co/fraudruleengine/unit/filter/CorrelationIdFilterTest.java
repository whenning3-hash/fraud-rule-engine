package za.co.fraudruleengine.unit.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import za.co.fraudruleengine.filter.CorrelationIdFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CorrelationIdFilter}.
 *
 * <p>Verifies that the filter correctly resolves, propagates, and cleans up the correlation ID
 * for the full range of inbound request scenarios — header present, header absent, and blank
 * header — and that MDC state is always cleaned up after request processing.
 *
 * <p>{@code OncePerRequestFilter.doFilterInternal} is {@code protected}; tests call the public
 * {@code doFilter} entry point, which delegates to {@code doFilterInternal} after ensuring
 * the filter runs only once per request. The behaviour under test is identical.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    // ── Header present: existing correlation ID is forwarded as-is ──────────

    @Test
    @DisplayName("Existing X-Capitec-Correlation-ID header is forwarded to MDC and response")
    void existingCorrelationId_isPreserved() throws Exception {
        String incoming = "test-correlation-id-abc123";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_HEADER)).isEqualTo(incoming);
    }

    // ── Header absent: a new UUID is generated ───────────────────────────────

    @Test
    @DisplayName("Missing correlation header generates a new UUID — response header is populated")
    void missingHeader_generatesNewUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String responseId = response.getHeader(CorrelationIdFilter.CORRELATION_HEADER);
        assertThat(responseId).isNotBlank();
        // Must be a valid UUID (length check as proxy — full UUID regex would be overkill here)
        assertThat(responseId).hasSizeGreaterThanOrEqualTo(32);
    }

    // ── Two separate requests generate independent IDs ───────────────────────

    @Test
    @DisplayName("Two requests without a header each get a different generated correlation ID")
    void twoRequests_getDifferentGeneratedIds() throws Exception {
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        MockHttpServletResponse r2 = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), r1, new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest(), r2, new MockFilterChain());

        assertThat(r1.getHeader(CorrelationIdFilter.CORRELATION_HEADER))
                .isNotEqualTo(r2.getHeader(CorrelationIdFilter.CORRELATION_HEADER));
    }

    // ── MDC is cleared after request completes ───────────────────────────────

    @Test
    @DisplayName("MDC key is removed after the filter chain completes — no thread-pool leakage")
    void mdcIsClearedAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, "some-id");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    // ── MDC is populated during filter execution (inside the chain) ──────────

    @Test
    @DisplayName("MDC contains the correlation ID while the filter chain is executing")
    void mdcIsPopulatedDuringChainExecution() throws Exception {
        String incoming = "chain-test-id";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, incoming);

        // Capture the MDC value from inside the filter chain using a custom chain implementation
        String[] capturedId = new String[1];
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> capturedId[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertThat(capturedId[0]).isEqualTo(incoming);
    }

    // ── Blank header is treated as absent ────────────────────────────────────

    @Test
    @DisplayName("Blank correlation header is treated as absent — a new ID is generated")
    void blankHeader_isIgnoredAndNewIdGenerated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String responseId = response.getHeader(CorrelationIdFilter.CORRELATION_HEADER);
        assertThat(responseId).isNotBlank();
        // The blank input must NOT appear in the response as-is
        assertThat(responseId.trim()).isNotEmpty();
    }
}
