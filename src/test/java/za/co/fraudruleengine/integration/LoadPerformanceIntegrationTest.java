package za.co.fraudruleengine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import za.co.fraudruleengine.api.dto.TransactionRequest;
import za.co.fraudruleengine.infrastructure.persistence.repository.AlertJpaRepository;
import za.co.fraudruleengine.infrastructure.persistence.repository.TransactionJpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests that validate the fraud rule engine's behaviour under concurrent load.
 *
 * <p>These tests complement the unit and functional integration tests by verifying:
 * <ul>
 *   <li>The application remains stable when multiple requests are evaluated simultaneously.</li>
 *   <li>The {@link za.co.fraudruleengine.infrastructure.filter.RateLimitFilter} returns
 *       {@code 429 Too Many Requests} when a burst exceeds the configured per-IP limit,
 *       rather than letting the request flood the thread pool.</li>
 *   <li>Individual response latency stays within the 2 000ms wall-clock SLA when using
 *       the in-process {@link MockMvc} test context (no network overhead).</li>
 * </ul>
 *
 * <p><b>Note on the rate-limit test:</b> MockMvc uses a single shared instance with no
 * real IP differentiation; all requests appear as {@code 127.0.0.1}.  The per-IP rate limit
 * is therefore exercised by a burst that exceeds the configured threshold in one minute.
 * The test explicitly sets a low threshold via a system property to keep the burst count
 * manageable within a unit test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("local")
class LoadPerformanceIntegrationTest {

    // ── Testcontainers ────────────────────────────────────────────────────────

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("frauddb")
            .withUsername("frauduser")
            .withPassword("fraudpass");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Use a small per-IP limit so the burst test can trigger it without sending thousands of requests
        registry.add("rate-limit.transactions.requests-per-minute", () -> "10");
        registry.add("rate-limit.global.requests-per-minute", () -> "5000");
    }

    // ── Spring context ────────────────────────────────────────────────────────

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionJpaRepository transactionRepository;
    @Autowired AlertJpaRepository alertRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        alertRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Fires 20 concurrent transaction evaluation requests using a virtual thread pool
     * (Java 25) and asserts that all complete within a 10-second wall-clock budget.
     *
     * <p>MockMvc runs in the same JVM as the Spring context, so latency is dominated by
     * rule evaluation + DB I/O against Testcontainers (Docker socket overhead).  A 10-second
     * budget for 20 concurrent requests is deliberately generous to avoid flakiness on
     * developer machines; the intent is to detect deadlocks, thread starvation, and pool
     * exhaustion — not to benchmark raw throughput.
     */
    @Test
    void concurrentTransactionEvaluation_20Requests_allComplete() throws Exception {
        // Use virtual threads (JDK 25) to simulate concurrent clients without blocking OS threads.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        int requestCount = 20;
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Long> responseTimesMs = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    // Each request uses a unique accountId to avoid the DUPLICATE_TRANSACTION_RULE
                    // triggering on itself; we are testing throughput, not business logic here.
                    TransactionRequest request = new TransactionRequest(
                            "ACC-LOAD-" + idx, new BigDecimal("500.00"), "ZAR",
                            "Load Test Merchant", "GROCERY", "CARD_PRESENT", "ZAF",
                            LocalDateTime.now().withHour(10));

                    long start = System.currentTimeMillis();
                    MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                            .andReturn();
                    long elapsed = System.currentTimeMillis() - start;
                    responseTimesMs.add(elapsed);

                    int status = result.getResponse().getStatus();
                    if (status == 201) successCount.incrementAndGet();
                    else if (status == 429) rateLimitedCount.incrementAndGet();
                    else errorCount.incrementAndGet();

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }));
        }

        // Wait up to 10 seconds for all requests to complete
        boolean allDone = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(allDone)
                .as("All 20 concurrent requests must complete within 10 seconds")
                .isTrue();

        // No 5xx errors — the application should either process or rate-limit, never crash
        assertThat(errorCount.get())
                .as("Expected 0 server errors; got %d (check logs for exceptions)", errorCount.get())
                .isZero();

        // Total accounted for = success + rate-limited (both are valid outcomes)
        assertThat(successCount.get() + rateLimitedCount.get())
                .as("All requests must receive a 201 or 429 response")
                .isEqualTo(requestCount);

        // Per-request wall-clock time must stay below the SLA (2 000ms in test context)
        long maxResponseTime = responseTimesMs.stream().mapToLong(Long::longValue).max().orElse(0);
        assertThat(maxResponseTime)
                .as("Every individual request must complete within 2 000ms SLA; slowest was %dms", maxResponseTime)
                .isLessThan(2000L);
    }

    /**
     * Verifies that the rate-limit filter returns {@code 429 Too Many Requests} once
     * the per-IP threshold is breached and continues returning 429 for subsequent requests
     * within the same window.
     *
     * <p>The per-IP limit is set to 10 req/min via {@link DynamicPropertySource} so this
     * test only needs to send 15 sequential requests — a manageable count for a unit test.
     */
    @Test
    void transactionRateLimit_burstBeyondThreshold_returns429() throws Exception {
        // Per-IP limit configured to 10 in DynamicPropertySource above.
        // Send 15 requests from the same "IP" (all MockMvc requests appear as 127.0.0.1).
        int totalRequests = 15;
        int rateLimitThreshold = 10;
        int rateLimitedCount = 0;

        for (int i = 0; i < totalRequests; i++) {
            TransactionRequest request = new TransactionRequest(
                    "ACC-RL-" + i, new BigDecimal("100.00"), "ZAR",
                    "Rate Limit Test", "GROCERY", "CARD_PRESENT", "ZAF",
                    LocalDateTime.now().withHour(10));

            MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andReturn();

            int status = result.getResponse().getStatus();
            if (status == 429) {
                rateLimitedCount++;
                // Verify the Retry-After header is present on 429 responses
                assertThat(result.getResponse().getHeader("Retry-After"))
                        .as("429 response must include Retry-After header")
                        .isNotNull();
            }
        }

        // At least (totalRequests - rateLimitThreshold) requests should have been rate-limited.
        // The exact count can vary by a few due to window boundary timing, but clearly some must be 429.
        assertThat(rateLimitedCount)
                .as("Expected at least %d rate-limited responses after %d requests with a limit of %d/min",
                        totalRequests - rateLimitThreshold, totalRequests, rateLimitThreshold)
                .isGreaterThanOrEqualTo(totalRequests - rateLimitThreshold);
    }
}
