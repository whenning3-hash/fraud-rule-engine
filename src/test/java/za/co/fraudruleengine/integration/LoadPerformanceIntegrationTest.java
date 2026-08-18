package za.co.fraudruleengine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
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
import za.co.fraudruleengine.infrastructure.filter.RateLimitFilter;
import za.co.fraudruleengine.infrastructure.persistence.repository.AlertJpaRepository;
import za.co.fraudruleengine.infrastructure.persistence.repository.TransactionJpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA-style load and performance integration tests for the fraud rule engine.
 *
 * <p>These tests simulate realistic concurrent client behaviour against the full application
 * stack (Spring context + PostgreSQL + Redis via Testcontainers) to verify:
 * <ul>
 *   <li>The server remains stable and returns correct HTTP status codes under concurrent load.</li>
 *   <li>No deadlocks, thread starvation, or connection pool exhaustion occurs.</li>
 *   <li>Individual request latency stays within the documented SLA (2 000ms in the test
 *       environment, which includes Docker-socket overhead).</li>
 *   <li>The {@link RateLimitFilter} correctly returns {@code 429 Too Many Requests} once the
 *       per-IP threshold is exceeded, with a {@code Retry-After} header.</li>
 *   <li>Rule evaluation produces deterministically correct fraud flags under concurrent access
 *       (no dirty reads, no shared mutable state across transactions).</li>
 * </ul>
 *
 * <p><b>Rate-limit configuration:</b> the per-IP limit is set to 10 req/min directly on the
 * autowired {@link RateLimitFilter} bean via {@link ReflectionTestUtils} in {@link #setUp()}.
 * This bypasses the property-injection lifecycle and works correctly even when the Spring
 * context is cached between test classes.  The limit is restored to its default (120) in
 * {@link #tearDown()} so other integration tests are not affected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("local")
class LoadPerformanceIntegrationTest {

    // ── Testcontainers ─────────────────────────────────────────────────────────

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
    }

    // ── Spring context ─────────────────────────────────────────────────────────

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionJpaRepository transactionRepository;
    @Autowired AlertJpaRepository alertRepository;
    @Autowired RateLimitFilter rateLimitFilter;

    MockMvc mockMvc;

    /** Per-IP limit used by the rate-limit test; injected directly to avoid context-cache conflicts. */
    private static final int TEST_RATE_LIMIT = 10;

    @BeforeEach
    void setUp() {
        // Explicitly add the RateLimitFilter to the MockMvc chain.
        // @Component filters are not auto-registered in the MOCK WebApplicationContext
        // (that registration happens via the embedded Tomcat at runtime), so we add it
        // manually here to ensure it participates in the MockMvc dispatch lifecycle.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .addFilter(rateLimitFilter)
                .build();
        alertRepository.deleteAll();
        transactionRepository.deleteAll();

        // Lower the per-IP limit to a test-friendly value so the burst test can trigger 429s
        // without sending hundreds of requests.  Set directly on the bean so the change
        // takes effect even if the Spring context is shared with another test class.
        ReflectionTestUtils.setField(rateLimitFilter, "perIpLimit", TEST_RATE_LIMIT);
        ReflectionTestUtils.setField(rateLimitFilter, "perIpEnabled", true);
        // Clear per-IP counters so each test starts with a fresh window
        ((java.util.concurrent.ConcurrentHashMap<?, ?>) ReflectionTestUtils
                .getField(rateLimitFilter, "perIpCounters")).clear();
        // Reset global counter
        ((java.util.concurrent.atomic.AtomicInteger) ReflectionTestUtils
                .getField(rateLimitFilter, "globalCounter")).set(0);
        ReflectionTestUtils.setField(rateLimitFilter, "globalWindowStart",
                System.currentTimeMillis());
    }

    @AfterEach
    void tearDown() {
        // Restore default per-IP limit so other integration test classes are not affected
        ReflectionTestUtils.setField(rateLimitFilter, "perIpLimit", 120);
        ((java.util.concurrent.ConcurrentHashMap<?, ?>) ReflectionTestUtils
                .getField(rateLimitFilter, "perIpCounters")).clear();
        ((java.util.concurrent.atomic.AtomicInteger) ReflectionTestUtils
                .getField(rateLimitFilter, "globalCounter")).set(0);
    }

    // ── Concurrent load tests ──────────────────────────────────────────────────

    /**
     * Fires 20 concurrent transaction evaluation requests using virtual threads (Java 25)
     * and asserts that all complete within a 10-second wall-clock budget.
     *
     * <p>Each request uses a unique account ID to prevent the DUPLICATE_TRANSACTION_RULE from
     * firing cross-request, isolating the concurrent load test from business logic tests.
     * With 20 unique accounts and a per-IP limit of 10, only requests 11–20 are subject to
     * rate limiting; all 20 can complete without error (201 or 429 are both valid).
     */
    @Test
    void concurrentTransactionEvaluation_20Requests_allCompleteWithin10Seconds() throws Exception {
        // Set the per-IP limit high for this test so all 20 concurrent requests succeed
        ReflectionTestUtils.setField(rateLimitFilter, "perIpLimit", 200);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        int requestCount = 20;
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Long> responseTimesMs = new CopyOnWriteArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    TransactionRequest request = new TransactionRequest(
                            "ACC-CONCURRENT-" + idx,
                            new BigDecimal("500.00"), "ZAR",
                            "Concurrent Test Merchant", "GROCERY", "CARD_PRESENT", "ZAF",
                            LocalDateTime.now().withHour(10));

                    long start = System.currentTimeMillis();
                    MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                            .andReturn();
                    responseTimesMs.add(System.currentTimeMillis() - start);

                    if (result.getResponse().getStatus() == 201) successCount.incrementAndGet();
                    else errorCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean allDone = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // ── QA assertions ──────────────────────────────────────────────────────
        assertThat(allDone)
                .as("All 20 concurrent requests must complete within the 10-second budget")
                .isTrue();
        assertThat(errorCount.get())
                .as("No 5xx errors expected under concurrent load; got %d error(s)", errorCount.get())
                .isZero();
        assertThat(successCount.get())
                .as("All 20 requests should succeed (201); got only %d", successCount.get())
                .isEqualTo(requestCount);

        LongSummaryStatistics stats = responseTimesMs.stream().mapToLong(Long::longValue).summaryStatistics();
        assertThat(stats.getMax())
                .as("Max response time %dms exceeds the 2 000ms SLA", stats.getMax())
                .isLessThan(2000L);
    }

    /**
     * Validates that concurrent requests for DIFFERENT accounts produce independent,
     * correctly-scored fraud evaluations.  Verifies there is no shared mutable state
     * between evaluation threads (no score bleed-through between accounts).
     */
    @Test
    void concurrentEvaluation_mixedRiskLevels_isolatedCorrectly() throws Exception {
        // Set rate limit high for this test
        ReflectionTestUtils.setField(rateLimitFilter, "perIpLimit", 200);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        int batchSize = 10;
        CountDownLatch latch = new CountDownLatch(batchSize * 2);
        AtomicInteger highRiskFraudCount = new AtomicInteger(0);
        AtomicInteger lowRiskFraudCount = new AtomicInteger(0);

        // High-risk: R15 000 LUXURY at 14:00 → AMOUNT_THRESHOLD_RULE fires (score >= 40)
        for (int i = 0; i < batchSize; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    TransactionRequest req = new TransactionRequest(
                            "ACC-HIGH-" + idx, new BigDecimal("15000.00"), "ZAR",
                            "Luxury Store", "LUXURY", "ONLINE", "ZAF",
                            LocalDateTime.now().withHour(14));
                    MvcResult r = mockMvc.perform(post("/api/v1/transactions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(req))).andReturn();
                    if (r.getResponse().getStatus() == 201) {
                        var body = objectMapper.readTree(r.getResponse().getContentAsString());
                        if (body.path("fraudulent").asBoolean()) highRiskFraudCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally { latch.countDown(); }
            });
        }

        // Low-risk: R250 GROCERY at 10:00 → no rules fire above threshold of 20
        for (int i = 0; i < batchSize; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    TransactionRequest req = new TransactionRequest(
                            "ACC-LOW-" + idx, new BigDecimal("250.00"), "ZAR",
                            "Pick n Pay", "GROCERY", "CARD_PRESENT", "ZAF",
                            LocalDateTime.now().withHour(10));
                    MvcResult r = mockMvc.perform(post("/api/v1/transactions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(req))).andReturn();
                    if (r.getResponse().getStatus() == 201) {
                        var body = objectMapper.readTree(r.getResponse().getContentAsString());
                        if (body.path("fraudulent").asBoolean()) lowRiskFraudCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally { latch.countDown(); }
            });
        }

        latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(highRiskFraudCount.get())
                .as("All %d high-value transactions must be flagged as fraud", batchSize)
                .isEqualTo(batchSize);
        assertThat(lowRiskFraudCount.get())
                .as("No low-value clean transactions should be flagged as fraud")
                .isZero();
    }

    /**
     * Validates the read-heavy alert retrieval endpoint under concurrent reads.
     * Multiple threads hit {@code GET /api/v1/alerts} simultaneously; all must return 200.
     */
    @Test
    void concurrentAlertRetrieval_10Threads_allSucceed() throws Exception {
        // Set rate limit high for this test
        ReflectionTestUtils.setField(rateLimitFilter, "perIpLimit", 200);

        // Seed a fraud alert
        TransactionRequest seed = new TransactionRequest(
                "ACC-ALERT-SEED", new BigDecimal("20000.00"), "ZAR",
                "Seeded Alert Merchant", "LUXURY", "ONLINE", "ZAF",
                LocalDateTime.now().withHour(3));
        mockMvc.perform(post("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(seed))).andReturn();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Long> responseTimesMs = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    long start = System.currentTimeMillis();
                    MvcResult result = mockMvc.perform(get("/api/v1/alerts")).andReturn();
                    responseTimesMs.add(System.currentTimeMillis() - start);
                    if (result.getResponse().getStatus() == 200) successCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally { latch.countDown(); }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get())
                .as("All %d concurrent alert reads must return 200", threadCount)
                .isEqualTo(threadCount);

        LongSummaryStatistics stats = responseTimesMs.stream().mapToLong(Long::longValue).summaryStatistics();
        assertThat(stats.getMax())
                .as("Max alert read time %dms exceeds the 2 000ms SLA", stats.getMax())
                .isLessThan(2000L);
    }

    // ── Rate limit test ────────────────────────────────────────────────────────

    /**
     * Verifies that the rate-limit filter returns {@code 429 Too Many Requests} when the
     * per-IP threshold is exceeded and that the {@code Retry-After: 60} header is present.
     *
     * <p>The per-IP limit is set to {@value #TEST_RATE_LIMIT} req/min in {@link #setUp()}.
     * Sending 15 sequential requests must produce at least 5 rate-limited (429) responses.
     */
    @Test
    void transactionRateLimit_burstBeyondThreshold_returns429WithRetryAfter() throws Exception {
        int totalRequests = 15;
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
                assertThat(result.getResponse().getHeader("Retry-After"))
                        .as("429 response must include Retry-After header (request %d)", i + 1)
                        .isNotNull()
                        .isEqualTo("60");
            }
        }

        int minExpected = totalRequests - TEST_RATE_LIMIT;
        assertThat(rateLimitedCount)
                .as("Expected at least %d rate-limited (429) responses; got %d "
                    + "(limit=%d, sent=%d)",
                    minExpected, rateLimitedCount, TEST_RATE_LIMIT, totalRequests)
                .isGreaterThanOrEqualTo(minExpected);
    }
}
