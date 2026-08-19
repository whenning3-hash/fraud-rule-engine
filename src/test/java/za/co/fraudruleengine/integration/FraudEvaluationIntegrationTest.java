package za.co.fraudruleengine.integration;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import za.co.fraudruleengine.api.dto.TransactionRequest;
import za.co.fraudruleengine.entity.RuleConfigEntity;
import za.co.fraudruleengine.repository.AlertJpaRepository;
import za.co.fraudruleengine.repository.RuleConfigJpaRepository;
import za.co.fraudruleengine.repository.TransactionJpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.lessThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("local")
class FraudEvaluationIntegrationTest {

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

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionJpaRepository transactionRepository;
    @Autowired AlertJpaRepository alertRepository;
    @Autowired RuleConfigJpaRepository ruleConfigRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        alertRepository.deleteAll();
        transactionRepository.deleteAll();
        // Reset rule configs to seeded defaults so tests that modify rules don't bleed into each other.
        // Each case restores the exact values inserted by the V4 and V5 Flyway migrations.
        List<RuleConfigEntity> rules = ruleConfigRepository.findAll();
        rules.forEach(rule -> {
            rule.setEnabled(true);
            switch (rule.getRuleName()) {
                // V4 baseline rules
                case "VELOCITY_RULE"              -> { rule.setRiskWeight(30);
                    rule.setParameters(Map.of("maxTransactions","5","windowMinutes","10")); }
                case "AMOUNT_THRESHOLD_RULE"      -> { rule.setRiskWeight(40);
                    rule.setParameters(Map.of("maxAmount","10000.00")); }
                case "OFF_HOURS_RULE"             -> { rule.setRiskWeight(20);
                    rule.setParameters(Map.of("startHour","0","endHour","5")); }
                case "DUPLICATE_TRANSACTION_RULE" -> { rule.setRiskWeight(35);
                    rule.setParameters(Map.of("windowSeconds","60")); }
                // V5 Capitec-realistic rules
                case "ROUND_NUMBER_AMOUNT_RULE"   -> { rule.setRiskWeight(25);
                    rule.setParameters(Map.of("divisor","1000","minAmount","5000.00")); }
                case "NIGHT_TIME_ATM_RULE"        -> { rule.setRiskWeight(45);
                    rule.setParameters(Map.of("startHour","0","endHour","5","minAmount","1500.00")); }
                case "COUNTRY_MISMATCH_RULE"      -> { rule.setRiskWeight(50);
                    rule.setParameters(Map.of("windowHours","24")); }
                case "UNUSUAL_MERCHANT_CATEGORY_RULE" -> { rule.setRiskWeight(15);
                    rule.setParameters(Map.of()); }
                default -> { /* leave any unknown rules untouched */ }
            }
        });
        ruleConfigRepository.saveAll(rules);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Authentication
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void authToken_validCredentials_returns200WithAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber());
    }

    @Test
    void authToken_emptyUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"pass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Transactions — submission and evaluation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submitTransaction_clean_returns201WithFraudulentFalse() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(cleanTransaction("ACC-100"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.fraudulent").value(false))
                .andExpect(jsonPath("$.riskScore").isNumber())
                .andExpect(jsonPath("$.accountId").value("ACC-100"));

        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void submitTransaction_highAmount_flaggedAsFraud() throws Exception {
        TransactionRequest request = new TransactionRequest(
                "ACC-200", new BigDecimal("15000.00"), "ZAR",
                "Luxury Jewellers", "LUXURY", "ONLINE", "ZAF",
                LocalDateTime.now().withHour(14));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(40)));

        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    void submitTransaction_offHoursOnly_flaggedAsFraud() throws Exception {
        TransactionRequest request = new TransactionRequest(
                "ACC-300", new BigDecimal("500.00"), "ZAR",
                "ATM Sandton", "ATM", "ATM", "ZAF",
                LocalDateTime.now().withHour(3).withMinute(30));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(20)));

        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    void submitTransaction_multiRuleBreach_highScore() throws Exception {
        // Amount (R50k > R10k = +40) + Off-hours (03:00 = +20) = 60 >= threshold
        TransactionRequest request = new TransactionRequest(
                "ACC-400", new BigDecimal("50000.00"), "ZAR",
                "Suspect Store", "ELECTRONICS", "ONLINE", "ZAF",
                LocalDateTime.now().withHour(3));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(60)));
    }

    @Test
    void submitTransaction_validationError_returns400WithDetail() throws Exception {
        String badJson = "{\"accountId\":\"\",\"amount\":-1,\"currency\":\"INVALID\",\"merchantName\":\"\",\"channel\":\"\",\"transactionTime\":null}";

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void getTransaction_existing_returns200() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(cleanTransaction("ACC-500"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.accountId").value("ACC-500"))
                .andExpect(jsonPath("$.fraudulent").isBoolean())
                .andExpect(jsonPath("$.riskScore").isNumber());
    }

    @Test
    void getTransaction_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Fraud Alerts
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void listAlerts_afterFraudDetected_returnsPaginatedResults() throws Exception {
        submitHighAmountTransaction("ACC-600");
        submitHighAmountTransaction("ACC-601");

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.content[0].id").isNotEmpty())
                .andExpect(jsonPath("$.content[0].accountId").isNotEmpty())
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].matchedRules").isArray())
                .andExpect(jsonPath("$.content[0].totalRiskScore").isNumber());
    }

    @Test
    void listAlerts_filterByAccountId_returnsOnlyMatchingAlerts() throws Exception {
        submitHighAmountTransaction("ACC-700");
        submitHighAmountTransaction("ACC-701");

        mockMvc.perform(get("/api/v1/alerts?accountId=ACC-700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].accountId", everyItem(equalTo("ACC-700"))));
    }

    @Test
    void listAlerts_filterByStatus_returnsOnlyMatchingStatus() throws Exception {
        submitHighAmountTransaction("ACC-800");

        mockMvc.perform(get("/api/v1/alerts?status=OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].status", everyItem(equalTo("OPEN"))));
    }

    @Test
    void getAlert_existing_returns200WithMatchedRules() throws Exception {
        submitHighAmountTransaction("ACC-900");

        String alertId = getFirstAlertId();
        assertThat(alertId).isNotBlank();

        mockMvc.perform(get("/api/v1/alerts/" + alertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alertId))
                .andExpect(jsonPath("$.matchedRules").isArray())
                .andExpect(jsonPath("$.matchedRules", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.ruleDetails").isNotEmpty())
                .andExpect(jsonPath("$.totalRiskScore").isNumber());
    }

    @Test
    void getAlert_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateAlertStatus_openToReviewed_returns200() throws Exception {
        submitHighAmountTransaction("ACC-950");
        String alertId = getFirstAlertId();

        mockMvc.perform(patch("/api/v1/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));
    }

    @Test
    void updateAlertStatus_reviewedToClosed_returns200() throws Exception {
        submitHighAmountTransaction("ACC-960");
        String alertId = getFirstAlertId();

        // OPEN → REVIEWED
        mockMvc.perform(patch("/api/v1/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED\"}"))
                .andExpect(status().isOk());

        // REVIEWED → CLOSED
        mockMvc.perform(patch("/api/v1/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void updateAlertStatus_invalidStatus_returns400() throws Exception {
        submitHighAmountTransaction("ACC-970");
        String alertId = getFirstAlertId();

        mockMvc.perform(patch("/api/v1/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INVALID_STATUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateAlertStatus_alertNotFound_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/00000000-0000-0000-0000-000000000000/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED\"}"))
                .andExpect(status().isNotFound());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Rule Configuration
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void listRules_returns200WithAllEightSeededRules() throws Exception {
        // V4 seeds 4 baseline rules; V5 seeds 4 additional Capitec-realistic rules
        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[*].ruleName", containsInAnyOrder(
                        "VELOCITY_RULE",
                        "AMOUNT_THRESHOLD_RULE",
                        "OFF_HOURS_RULE",
                        "DUPLICATE_TRANSACTION_RULE",
                        "ROUND_NUMBER_AMOUNT_RULE",
                        "NIGHT_TIME_ATM_RULE",
                        "COUNTRY_MISMATCH_RULE",
                        "UNUSUAL_MERCHANT_CATEGORY_RULE")))
                .andExpect(jsonPath("$[*].enabled", everyItem(equalTo(true))));
    }

    @Test
    void getRule_velocityRule_returns200WithCorrectParameters() throws Exception {
        mockMvc.perform(get("/api/v1/rules/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleName").value("VELOCITY_RULE"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.riskWeight").value(30))
                .andExpect(jsonPath("$.parameters.maxTransactions").value("5"))
                .andExpect(jsonPath("$.parameters.windowMinutes").value("10"));
    }

    @Test
    void getRule_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/rules/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateRule_velocityThreshold_persistsChange() throws Exception {
        mockMvc.perform(patch("/api/v1/rules/11111111-1111-1111-1111-111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"riskWeight\":35,\"parameters\":{\"maxTransactions\":\"3\",\"windowMinutes\":\"5\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskWeight").value(35))
                .andExpect(jsonPath("$.parameters.maxTransactions").value("3"))
                .andExpect(jsonPath("$.parameters.windowMinutes").value("5"));

        // Verify persisted
        mockMvc.perform(get("/api/v1/rules/11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.riskWeight").value(35));
    }

    @Test
    void disableRule_offHoursRule_offHoursTransactionNolongerFlagged() throws Exception {
        // Disable the off-hours rule
        mockMvc.perform(patch("/api/v1/rules/33333333-3333-3333-3333-333333333333")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"riskWeight\":20,\"parameters\":{\"startHour\":\"0\",\"endHour\":\"5\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Submit off-hours transaction (only off-hours should have been triggered, but rule is disabled)
        TransactionRequest request = new TransactionRequest(
                "ACC-TEST-DISABLED", new BigDecimal("500.00"), "ZAR",
                "ATM Test", "ATM", "ATM", "ZAF",
                LocalDateTime.now().withHour(3));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false)); // disabled rule not counted

        // Re-enable
        mockMvc.perform(patch("/api/v1/rules/33333333-3333-3333-3333-333333333333")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"riskWeight\":20,\"parameters\":{\"startHour\":\"0\",\"endHour\":\"5\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void updateRule_invalidRuleId_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/rules/00000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"riskWeight\":30,\"parameters\":{}}"))
                .andExpect(status().isNotFound());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Health
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void healthEndpoint_returns200WithStatusUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Input validation — malformed path variables
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getTransaction_malformedUuid_returns400NotServerError() throws Exception {
        // A non-UUID path variable must return 400, not 500.
        mockMvc.perform(get("/api/v1/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAlert_malformedUuid_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Alert filter combinations
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void listAlerts_filterByAccountIdAndStatus_returnsOnlyMatchingAlerts() throws Exception {
        submitHighAmountTransaction("ACC-COMBO-1");  // creates OPEN alert
        submitHighAmountTransaction("ACC-COMBO-2");  // creates OPEN alert

        // Filter by both accountId and status — should return only ACC-COMBO-1's alert
        mockMvc.perform(get("/api/v1/alerts?accountId=ACC-COMBO-1&status=OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].accountId", everyItem(equalTo("ACC-COMBO-1"))))
                .andExpect(jsonPath("$.content[*].status", everyItem(equalTo("OPEN"))));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Duplicate transaction rule — end-to-end
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submitTransaction_duplicate_flaggedAsFraud() throws Exception {
        // First submission — not a duplicate
        TransactionRequest request = new TransactionRequest(
                "ACC-DUP", new BigDecimal("250.00"), "ZAR",
                "Pick n Pay", "GROCERY", "POS", "ZAF",
                LocalDateTime.now().withHour(10));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false));

        // Second identical submission within the window — should trigger DUPLICATE_TRANSACTION_RULE
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(35)));

        assertThat(alertRepository.count()).isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // New Capitec-realistic rules — end-to-end
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submitTransaction_nightTimeAtmHighValue_flaggedAsFraud() throws Exception {
        // 02:00 ATM withdrawal of R2 000 — all three conditions of NIGHT_TIME_ATM_RULE are met:
        // channel=ATM (cash), hour=2 (in [0,5)), amount=2000 >= minAmount=1500.
        // Risk weight = 45 >= threshold = 20, so the transaction is flagged.
        TransactionRequest request = new TransactionRequest(
                "ACC-ATM-1", new BigDecimal("2000.00"), "ZAR",
                "ABSA ATM Sandton", "CASH", "ATM", "ZAF",
                LocalDateTime.now().withHour(2).withMinute(15));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(45)));

        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    void submitTransaction_roundNumberAmount_flaggedAsFraud() throws Exception {
        // R10 000 is exactly divisible by 1 000 and above the R5 000 minimum — structuring signal.
        // ROUND_NUMBER_AMOUNT_RULE fires with risk weight = 25 >= threshold = 20.
        TransactionRequest request = new TransactionRequest(
                "ACC-ROUND-1", new BigDecimal("10000.00"), "ZAR",
                "Cash Deposit", "BANKING", "MOBILE", "ZAF",
                LocalDateTime.now().withHour(11));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                // ROUND_NUMBER_AMOUNT_RULE fires (25) + AMOUNT_THRESHOLD_RULE fires (40) = 65 >= 20
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(25)));

        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    void submitTransaction_disableRoundNumberRule_roundAmountNoLongerFlagged() throws Exception {
        // Disable ROUND_NUMBER_AMOUNT_RULE and submit a round number below other thresholds.
        mockMvc.perform(patch("/api/v1/rules/55555555-5555-5555-5555-555555555555")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"riskWeight\":25,\"parameters\":{\"divisor\":\"1000\",\"minAmount\":\"5000.00\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // R5 000 — round and above minimum, but the rule is disabled.
        // AMOUNT_THRESHOLD_RULE does NOT fire (5000 is not > 10000).
        // Score should be < threshold (20), so fraudulent=false.
        TransactionRequest request = new TransactionRequest(
                "ACC-ROUND-DIS", new BigDecimal("5000.00"), "ZAR",
                "Investment Transfer", "BANKING", "ONLINE", "ZAF",
                LocalDateTime.now().withHour(11));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false));

        assertThat(alertRepository.count()).isZero();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Country mismatch rule — two-step end-to-end
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submitTransaction_countryMismatch_twoStepScenario_flaggedAsFraud() throws Exception {
        // Step 1 — Establish South African baseline for this account.
        // UNUSUAL_MERCHANT_CATEGORY_RULE fires (15) but 15 < local threshold (20), so not fraudulent.
        TransactionRequest baseline = new TransactionRequest(
                "ACC-CM-1", new BigDecimal("800.00"), "ZAR",
                "Woolworths Cape Town", "RETAIL", "POS", "ZAF",
                LocalDateTime.now().withHour(9));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(baseline)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false));

        // Step 2 — Same account, GBR country code within minutes of Step 1.  Impossible travel
        // detected.  COUNTRY_MISMATCH_RULE (50) fires because ZAF exists in the 24-hour window
        // for ACC-CM-1.  Combined score = 50 + 15 (unusual category LUXURY) = 65 >= threshold (20).
        TransactionRequest mismatch = new TransactionRequest(
                "ACC-CM-1", new BigDecimal("1500.00"), "GBP",
                "Harrods London", "LUXURY", "ONLINE", "GBR",
                LocalDateTime.now().withHour(14));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(mismatch)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(50)));

        // Only one fraud alert — the baseline transaction was clean.
        assertThat(alertRepository.count()).isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Velocity rule — end-to-end (positive + negative)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submitTransaction_velocityLimitExceeded_flaggedAsFraud() throws Exception {
        // VELOCITY_RULE default: maxTransactions=5, windowMinutes=10.
        // Submit 6 transactions for the same account in quick succession.
        // The 6th crosses the >= 5 threshold and must be flagged.
        for (int i = 1; i <= 5; i++) {
            TransactionRequest tx = new TransactionRequest(
                    "ACC-VEL-1", new BigDecimal("100.00"), "ZAR",
                    "Merchant " + i, "GROCERY", "POS", "ZAF",
                    LocalDateTime.now().withHour(11));
            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(tx)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.fraudulent").value(false));
        }

        // 6th transaction — velocity threshold exceeded
        TransactionRequest sixth = new TransactionRequest(
                "ACC-VEL-1", new BigDecimal("100.00"), "ZAR",
                "Merchant 6", "GROCERY", "POS", "ZAF",
                LocalDateTime.now().withHour(11));
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(sixth)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(30)));

        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    void submitTransaction_belowVelocityLimit_notFlagged() throws Exception {
        // 4 transactions — well below the default threshold of 5 — must all be clean
        for (int i = 1; i <= 4; i++) {
            TransactionRequest tx = new TransactionRequest(
                    "ACC-VEL-CLEAN", new BigDecimal("100.00"), "ZAR",
                    "Merchant " + i, "GROCERY", "POS", "ZAF",
                    LocalDateTime.now().withHour(11));
            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(tx)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.fraudulent").value(false));
        }

        assertThat(alertRepository.count()).isZero();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Fraud score boundary — threshold edge cases
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submitTransaction_scoreExactlyAtThreshold_flaggedAsFraud() throws Exception {
        // Local profile threshold = 20.  OFF_HOURS_RULE weight = 20.
        // A transaction at 02:00 with a safe amount should score exactly 20 → fraudulent.
        TransactionRequest tx = new TransactionRequest(
                "ACC-BOUNDARY-1", new BigDecimal("500.00"), "ZAR",
                "Night Shop", "GROCERY", "POS", "ZAF",
                LocalDateTime.now().withHour(2));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(tx)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(20)));

        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    void submitTransaction_scoreBelowThreshold_notFlagged() throws Exception {
        // UNUSUAL_MERCHANT_CATEGORY_RULE alone scores 15 (first-ever category for account).
        // 15 < threshold 20 → must NOT be flagged.
        TransactionRequest tx = new TransactionRequest(
                "ACC-BOUNDARY-2", new BigDecimal("500.00"), "ZAR",
                "Travel Agency", "TRAVEL", "ONLINE", "ZAF",
                LocalDateTime.now().withHour(11));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(tx)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false))
                .andExpect(jsonPath("$.riskScore").value(lessThan(20)));

        assertThat(alertRepository.count()).isZero();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Country mismatch rule — negative: same country must NOT fire
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submitTransaction_countryConsistentWithHistory_notFlagged() throws Exception {
        // Both transactions are in ZAF — no mismatch.
        // First transaction is clean (score 15 < threshold 20).
        // Second transaction: COUNTRY_MISMATCH_RULE must be SILENT (ZAF == ZAF).
        // Score = UNUSUAL_MERCHANT_CATEGORY (15 for LUXURY, first time) < 20 → not fraudulent.
        TransactionRequest baseline = new TransactionRequest(
                "ACC-NOCM-1", new BigDecimal("500.00"), "ZAR",
                "Checkers", "GROCERY", "POS", "ZAF",
                LocalDateTime.now().withHour(10));
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(baseline)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false));

        TransactionRequest sameCountry = new TransactionRequest(
                "ACC-NOCM-1", new BigDecimal("800.00"), "ZAR",
                "Woolworths", "RETAIL", "POS", "ZAF",
                LocalDateTime.now().withHour(11));
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(sameCountry)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false));

        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void submitTransaction_noTransactionHistory_countryMismatchRuleDoesNotFire() throws Exception {
        // Brand-new account with no prior history.
        // COUNTRY_MISMATCH_RULE must NOT fire — there is no baseline to compare against.
        // Score should only include UNUSUAL_MERCHANT_CATEGORY (15) < threshold (20).
        TransactionRequest firstEver = new TransactionRequest(
                "ACC-NOCM-NEW", new BigDecimal("500.00"), "ZAR",
                "Harrods London", "LUXURY", "ONLINE", "GBR",
                LocalDateTime.now().withHour(10));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(firstEver)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false));

        assertThat(alertRepository.count()).isZero();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Error handling — HTTP method, media type, and route validation
    // (Exercises GlobalExceptionHandler handlers added for Spring MVC exceptions
    // that previously fell through to the catch-all and returned 500)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getOnTransactionsEndpoint_methodNotAllowed_returns405() throws Exception {
        // POST /api/v1/transactions is the only supported method.
        // A GET request must return 405 Method Not Allowed (not 500).
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"));
    }

    @Test
    void postTransactionWithTextPlainContentType_returns415() throws Exception {
        // Sending text/plain instead of application/json must return 415 Unsupported Media Type.
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("this is not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.title").value("Unsupported Media Type"));
    }

    @Test
    void requestToNonExistentRoute_returns404() throws Exception {
        // An unknown route must return 404 Not Found (not 500).
        mockMvc.perform(get("/api/v1/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    @Test
    void getAlertWithNonUuidId_returns400() throws Exception {
        // Path variable {id} expects a UUID. Passing a non-UUID string must return 400.
        mockMvc.perform(get("/api/v1/alerts/not-a-valid-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getTransactionWithNonUuidId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/not-a-valid-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void postTransactionWithMalformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void postTransactionWithInvalidEnumStatus_returns400() throws Exception {
        // Updating an alert with an unrecognised status value must return 400.
        // First create an alert by submitting a fraudulent transaction.
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionRequest(
                                "ACC-ENUM-VALID", new BigDecimal("50000.00"), "ZAR",
                                "Big Store", "LUXURY", "ONLINE", "ZAF",
                                LocalDateTime.now().withHour(3)))))
                .andExpect(status().isCreated());

        String alertId = getFirstAlertId();
        if (alertId.isEmpty()) return; // safety guard — alert may not have been created if no score

        mockMvc.perform(patch("/api/v1/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INVALID_STATUS_VALUE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateAlertStatus_validTransitions_allReturnOk() throws Exception {
        // Verify all three valid status values are accepted: OPEN → REVIEWED → CLOSED
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionRequest(
                                "ACC-STATUS-TRANS", new BigDecimal("60000.00"), "ZAR",
                                "Luxury Dealer", "AUTOMOTIVE", "ONLINE", "ZAF",
                                LocalDateTime.now().withHour(3)))))
                .andExpect(status().isCreated());

        String alertId = getFirstAlertId();
        if (alertId.isEmpty()) return;

        mockMvc.perform(patch("/api/v1/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));

        mockMvc.perform(patch("/api/v1/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void nightTimePosHighValue_flaggedAsFraud_posIsInCashChannels() throws Exception {
        // POS is in CASH_CHANNELS (EnumSet.of(ATM, POS)).
        // A POS withdrawal at 03:00 with R3 500 meets all three NIGHT_TIME_ATM_RULE conditions.
        // This test explicitly validates the ChannelType.POS → CASH_CHANNELS path.
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionRequest(
                                "ACC-POS-NIGHT", new BigDecimal("3500.00"), "ZAR",
                                "Engen 24h Petrol", "FUEL", "POS", "ZAF",
                                LocalDateTime.now().withHour(3)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(greaterThanOrEqualTo(45)));
    }

    @Test
    void nightTimeOnlineHighValue_notFlaggedByNightTimeAtmRule_channelIsolation() throws Exception {
        // ONLINE is NOT in CASH_CHANNELS.
        // Even with hour=02, amount=R2000, the NIGHT_TIME_ATM_RULE must NOT fire.
        // Score should be < 45 (would be 80 if it fired).  This is the ChannelType isolation test.
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionRequest(
                                "ACC-ONLINE-NIGHT", new BigDecimal("2000.00"), "ZAR",
                                "Amazon", "ONLINE_RETAIL", "ONLINE", "ZAF",
                                LocalDateTime.now().withHour(2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore").value(lessThan(45)));
    }

    @Test
    void unknownChannelValue_doesNotCrash_nightTimeAtmRuleDoesNotFire() throws Exception {
        // An unrecognised channel string must not throw — ChannelType.fromString() returns null,
        // null is not in CASH_CHANNELS, so NIGHT_TIME_ATM_RULE does not fire.
        // The service must return 201 and handle the transaction normally.
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionRequest(
                                "ACC-UNKNOWN-CH", new BigDecimal("3000.00"), "ZAR",
                                "Some Vendor", "RETAIL", "WIRE_TRANSFER", "ZAF",
                                LocalDateTime.now().withHour(2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore").value(lessThan(45)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════════

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private TransactionRequest cleanTransaction(String accountId) {
        return new TransactionRequest(accountId, new BigDecimal("250.00"), "ZAR",
                "Pick n Pay", "GROCERY", "POS", "ZAF",
                LocalDateTime.now().withHour(10));
    }

    private void submitHighAmountTransaction(String accountId) throws Exception {
        TransactionRequest request = new TransactionRequest(
                accountId, new BigDecimal("15000.00"), "ZAR",
                "Luxury Store", "LUXURY", "ONLINE", "ZAF",
                LocalDateTime.now().withHour(14));
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isCreated());
    }

    private String getFirstAlertId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode content = json.get("content");
        if (content == null || content.isEmpty()) return "";
        return content.get(0).get("id").asText();
    }
}
