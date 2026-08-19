package za.co.fraudruleengine.unit.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import za.co.fraudruleengine.api.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>Verifies that every exception type handled by the advice produces the correct HTTP status
 * code, a non-null {@code title}, and a meaningful {@code detail} message. These tests exercise
 * the handler methods directly without a running servlet container — no Spring context is needed.
 *
 * <p>Coverage includes both application-domain exceptions (e.g. {@link IllegalArgumentException}
 * for 404) and Spring MVC infrastructure exceptions (e.g. {@link NoResourceFoundException},
 * {@link HttpRequestMethodNotSupportedException}) that must not fall through to the generic
 * 500 catch-all.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── IllegalArgumentException → 404 ──────────────────────────────────────

    @Test
    @DisplayName("IllegalArgumentException maps to 404 Not Found")
    void illegalArgumentException_returns404() {
        ProblemDetail result = handler.handleNotFound(new IllegalArgumentException("Alert not found"));
        assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(result.getTitle()).isEqualTo("Resource Not Found");
        assertThat(result.getDetail()).contains("Alert not found");
    }

    // ── NoResourceFoundException → 404 ──────────────────────────────────────

    @Test
    @DisplayName("NoResourceFoundException maps to 404 — not swallowed by catch-all as 500")
    void noResourceFoundException_returns404() throws Exception {
        // Constructor: NoResourceFoundException(HttpMethod, String resourcePath, String description)
        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/v1/nonexistent", "No handler found");
        ProblemDetail result = handler.handleNoResourceFound(ex);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(result.getTitle()).isEqualTo("Not Found");
        assertThat(result.getDetail()).isNotBlank();
    }

    // ── HttpRequestMethodNotSupportedException → 405 ────────────────────────

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException maps to 405 — not swallowed as 500")
    void methodNotSupportedException_returns405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET");
        ProblemDetail result = handler.handleMethodNotSupported(ex);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(result.getTitle()).isEqualTo("Method Not Allowed");
        assertThat(result.getDetail()).contains("GET");
    }

    // ── HttpMediaTypeNotSupportedException → 415 ────────────────────────────

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException maps to 415 — not swallowed as 500")
    void mediaTypeNotSupportedException_returns415() {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException("text/plain is not supported");
        ProblemDetail result = handler.handleMediaTypeNotSupported(ex);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        assertThat(result.getTitle()).isEqualTo("Unsupported Media Type");
    }

    // ── Generic Exception → 500 ──────────────────────────────────────────────

    @Test
    @DisplayName("Unexpected exception maps to 500 with generic message (no detail leaked)")
    void unexpectedException_returns500() {
        ProblemDetail result = handler.handleGeneric(new RuntimeException("internal db error detail"));
        assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(result.getTitle()).isEqualTo("Internal Server Error");
        // Must NOT leak internal detail to the caller
        assertThat(result.getDetail()).doesNotContain("internal db error detail");
        assertThat(result.getDetail()).isEqualTo("An unexpected error occurred");
    }
}
