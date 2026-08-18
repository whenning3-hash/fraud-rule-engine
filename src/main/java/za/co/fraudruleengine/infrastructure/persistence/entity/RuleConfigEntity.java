package za.co.fraudruleengine.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * JPA entity representing the runtime configuration for a single fraud detection rule, persisted
 * in the {@code rule_configs} table.
 *
 * <p>Each row corresponds to one {@link za.co.fraudruleengine.domain.rule.FraudRule} implementation
 * and is identified by its {@code rule_name}, which must exactly match the value returned by
 * {@link za.co.fraudruleengine.domain.rule.FraudRule#getRuleName()}.
 *
 * <p>The four initial rows are seeded by Flyway migration V4. Operators can tune rule behaviour
 * at runtime via the {@code /api/v1/rules} endpoint without restarting the service, because the
 * {@link za.co.fraudruleengine.domain.rule.RuleEngine} fetches configuration fresh on every
 * evaluation.
 *
 * <p>The {@code parameters} field is mapped to a PostgreSQL {@code jsonb} column and
 * deserialised to a {@code Map<String, String>} by Hibernate's {@link SqlTypes#JSON} type
 * handler. Using {@code jsonb} (binary JSON) rather than plain {@code json} allows PostgreSQL
 * to index and query individual parameter keys if needed in future.
 */
@Entity
@Table(name = "rule_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleConfigEntity {

    /** UUID primary key; assigned by the Flyway seed migration and never changed. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * The canonical rule identifier that links this config to its {@link za.co.fraudruleengine.domain.rule.FraudRule}
     * implementation (e.g. {@code "VELOCITY_RULE"}). Unique and immutable after seeding.
     */
    @Column(name = "rule_name", nullable = false, unique = true)
    private String ruleName;

    /**
     * Whether the rule is active. Disabled rules are skipped by the engine without logging a
     * warning, allowing rules to be parked without deleting their configuration.
     */
    @Column(nullable = false)
    private boolean enabled;

    /**
     * The maximum score contribution added to a transaction's aggregate risk total when this
     * rule fires. A higher weight makes a rule's signal more influential in the overall
     * fraud decision.
     */
    @Column(name = "risk_weight", nullable = false)
    private int riskWeight;

    /**
     * Rule-specific key/value configuration parameters stored as a PostgreSQL {@code jsonb} column.
     * Examples: {@code {"maxAmount": "10000.00"}} for the amount threshold rule, or
     * {@code {"maxTransactions": "5", "windowMinutes": "10"}} for the velocity rule.
     * Values are always stored as strings and parsed by {@link za.co.fraudruleengine.domain.rule.RuleParameters}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, String> parameters;
}
