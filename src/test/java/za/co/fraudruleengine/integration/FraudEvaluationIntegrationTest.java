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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TransactionJpaRepository transactionRepository;

    @Autowired
    AlertJpaRepository alertRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        alertRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    @Test
    void shouldProcessLowRiskTransactionWithoutAlert() throws Exception {
        TransactionRequest request = new TransactionRequest(
                "ACC-100", new BigDecimal("500.00"), "ZAR",
                "Woolworths", "GROCERY", "POS", "ZA",
                LocalDateTime.now().withHour(14));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(false));

        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void shouldFlagHighAmountOffHoursTransactionAsFraud() throws Exception {
        // Amount (R50,000 > threshold R10,000) = score 40
        // Off-hours (03:30 is between 00:00–05:00) = score 20
        // Total = 60 >= threshold — should be flagged
        TransactionRequest request = new TransactionRequest(
                "ACC-200", new BigDecimal("50000.00"), "ZAR",
                "Luxury Cars", "AUTOMOTIVE", "ONLINE", "ZA",
                LocalDateTime.now().withHour(3));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(60));

        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnTransactionById() throws Exception {
        TransactionRequest request = new TransactionRequest(
                "ACC-300", new BigDecimal("100.00"), "ZAR",
                "Checkers", "GROCERY", "POS", "ZA",
                LocalDateTime.now().withHour(10));

        String responseJson = mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(responseJson).get("id").asText();

        mockMvc.perform(get("/api/v1/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ACC-300"));
    }
}
