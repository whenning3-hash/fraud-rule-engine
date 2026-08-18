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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import za.co.fraudruleengine.api.dto.TransactionRequest;
import za.co.fraudruleengine.infrastructure.persistence.entity.RuleConfigEntity;
import za.co.fraudruleengine.infrastructure.persistence.repository.AlertJpaRepository;
import za.co.fraudruleengine.infrastructure.persistence.repository.RuleConfigJpaRepository;
import za.co.fraudruleengine.infrastructure.persistence.repository.TransactionJpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
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
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        alertRepository.deleteAll();
        transactionRepository.deleteAll();
        // Reset rule configs to seeded defaults so tests that modify rules don't bleed into each other.
        List<RuleConfigEntity> rules = ruleConfigRepository.findAll();
        rules.forEach(rule -> {
            rule.setEnabled(true);
            switch (rule.getRuleName()) {
                case "VELOCITY_RULE"              -> { rule.setRiskWeight(30);
                    rule.setParameters(Map.of("maxTransactions","5","windowMinutes","10")); }
                case "AMOUNT_THRESHOLD_RULE"      -> { rule.setRiskWeight(40);
                    rule.setParameters(Map.of("maxAmount","10000.00")); }
                case "OFF_HOURS_RULE"             -> { rule.setRiskWeight(20);
                    rule.setParameters(Map.of("startHour","0","endHour","5")); }
                case "DUPLICATE_TRANSACTION_RULE" -> { rule.setRiskWeight(35);
                    rule.setParameters(Map.of("windowSeconds","60")); }
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
                "Luxury Jewellers", "LUXURY", "ONLINE", "ZA",
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
                "ATM Sandton", "ATM", "ATM", "ZA",
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
                "Suspect Store", "ELECTRONICS", "ONLINE", "ZA",
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
    void listRules_returns200WithFourSeededRules() throws Exception {
        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].ruleName", containsInAnyOrder(
                        "VELOCITY_RULE", "AMOUNT_THRESHOLD_RULE",
                        "OFF_HOURS_RULE", "DUPLICATE_TRANSACTION_RULE")))
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
                "ATM Test", "ATM", "ATM", "ZA",
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
    // helpers
    // ══════════════════════════════════════════════════════════════════════════

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private TransactionRequest cleanTransaction(String accountId) {
        return new TransactionRequest(accountId, new BigDecimal("250.00"), "ZAR",
                "Pick n Pay", "GROCERY", "CARD_PRESENT", "ZA",
                LocalDateTime.now().withHour(10));
    }

    private void submitHighAmountTransaction(String accountId) throws Exception {
        TransactionRequest request = new TransactionRequest(
                accountId, new BigDecimal("15000.00"), "ZAR",
                "Luxury Store", "LUXURY", "ONLINE", "ZA",
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
