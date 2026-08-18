package za.co.fraudruleengine.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "rule_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleConfigEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "rule_name", nullable = false, unique = true)
    private String ruleName;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "risk_weight", nullable = false)
    private int riskWeight;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, String> parameters;
}
