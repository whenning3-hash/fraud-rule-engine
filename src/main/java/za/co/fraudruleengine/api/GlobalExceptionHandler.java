package za.co.fraudruleengine.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Centralised exception handler that maps application and framework exceptions to structured
 * RFC 7807 {@link ProblemDetail} HTTP responses.
 *
 * <p>Using {@code @RestControllerAdvice} ensures that all exceptions thrown from any controller
 * in this application are intercepted here rather than being handled individually per controller,
 * which keeps controller code focused on the happy path and ensures consistent error response
 * shapes across all API endpoints.
 *
 * <p>Response bodies conform to RFC 7807 ({@code application/problem+json}) via Spring's
 * built-in {@link ProblemDetail} support introduced in Spring 6. This provides clients with
 * machine-readable {@code status}, {@code title}, and {@code detail} fields without a custom
 * error DTO.
 *
 * <p>Log levels are deliberately varied by severity:
 * <ul>
 *   <li>Business rule / 404 / 400 errors: {@code WARN} or {@code DEBUG} — expected in normal operation</li>
 *   <li>Unexpected 500 errors: {@code ERROR} with full stack trace — requires investigation</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maps {@link IllegalArgumentException} to HTTP 404 Not Found.
     *
     * <p>Service layer methods (e.g. {@code findById}) throw {@code IllegalArgumentException}
     * when a requested resource does not exist. This convention keeps the service layer free of
     * HTTP concerns while still producing the semantically correct 404 response.
     *
     * @param ex the exception carrying the not-found detail message
     * @return a {@link ProblemDetail} with status 404 and the exception message as the detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleNotFound(IllegalArgumentException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Resource Not Found");
        return detail;
    }

    /**
     * Handles type conversion failures on path variables and request parameters.
     *
     * <p>For example, {@code GET /api/v1/transactions/not-a-uuid} will fail to bind the path
     * variable to a {@link java.util.UUID} parameter, producing this exception. Without this
     * handler, Spring would return a generic 500; this handler returns a meaningful 400 with the
     * offending value and parameter name included in the response body.
     *
     * @param ex the mismatch exception describing the offending value and target type
     * @return a {@link ProblemDetail} with status 400 and a descriptive message
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());
        log.warn("Type mismatch: {}", message);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        detail.setTitle("Bad Request");
        return detail;
    }

    /**
     * Maps malformed or unreadable request body payloads to HTTP 400 Bad Request.
     *
     * <p>Triggered when the request body cannot be deserialised (e.g. invalid JSON syntax, an
     * enum value that doesn't match any constant). The most specific cause message is extracted
     * to avoid exposing internal stack traces to clients while still providing actionable detail.
     *
     * @param ex the exception describing the parse failure
     * @return a {@link ProblemDetail} with status 400 and the parse error message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid request body: " + ex.getMostSpecificCause().getMessage());
        detail.setTitle("Bad Request");
        return detail;
    }

    /**
     * Maps Bean Validation constraint failures to HTTP 400 Bad Request with field-level detail.
     *
     * <p>All field errors from the binding result are aggregated into a single comma-separated
     * message so that clients receive a complete picture of all validation failures in one
     * response, rather than needing to fix and resubmit iteratively.
     *
     * @param ex the validation exception containing one or more field errors
     * @return a {@link ProblemDetail} with status 400 and all field errors listed in the detail
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.debug("Validation failed: {}", errors);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errors);
        detail.setTitle("Validation Failed");
        return detail;
    }

    /**
     * Maps Spring's {@link NoResourceFoundException} to HTTP 404 Not Found.
     *
     * <p>Spring 6.1+ throws this exception when the {@code DispatcherServlet} cannot find a
     * matching route or static resource for the request URI. Without an explicit handler here,
     * the catch-all {@code Exception} handler would intercept it and incorrectly return 500.
     *
     * @param ex the exception thrown when no handler or resource is found for the request path
     * @return a {@link ProblemDetail} with status 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("No handler found for request: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "The requested endpoint does not exist");
        detail.setTitle("Not Found");
        return detail;
    }

    /**
     * Maps {@link HttpRequestMethodNotSupportedException} to HTTP 405 Method Not Allowed.
     *
     * <p>Thrown when the correct path is matched but the HTTP method is wrong — for example,
     * a {@code GET} request against a {@code POST}-only endpoint. Without an explicit handler,
     * the catch-all would return 500.
     *
     * @param ex the exception carrying the unsupported method and the list of allowed methods
     * @return a {@link ProblemDetail} with status 405 and the offending method name
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.debug("HTTP method not supported: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint");
        detail.setTitle("Method Not Allowed");
        return detail;
    }

    /**
     * Maps {@link HttpMediaTypeNotSupportedException} to HTTP 415 Unsupported Media Type.
     *
     * <p>Thrown when the client supplies a {@code Content-Type} that no endpoint can consume —
     * for example, {@code text/plain} instead of {@code application/json}. Without an explicit
     * handler, the catch-all would return 500.
     *
     * @param ex the exception carrying the unsupported content type
     * @return a {@link ProblemDetail} with status 415 and the offending content type
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.debug("Media type not supported: {}", ex.getMessage());
        String contentType = ex.getContentType() != null ? ex.getContentType().toString() : "unknown";
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type '" + contentType + "' is not supported. Use application/json");
        detail.setTitle("Unsupported Media Type");
        return detail;
    }

    /**
     * Catch-all handler for any unhandled exception, returning HTTP 500 Internal Server Error.
     *
     * <p>The detail message is intentionally vague to avoid leaking internal implementation
     * details to clients. The full exception is logged at ERROR level so that the root cause
     * can be investigated via application logs.
     *
     * @param ex the unexpected exception
     * @return a {@link ProblemDetail} with status 500 and a generic error message
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        detail.setTitle("Internal Server Error");
        return detail;
    }
}
