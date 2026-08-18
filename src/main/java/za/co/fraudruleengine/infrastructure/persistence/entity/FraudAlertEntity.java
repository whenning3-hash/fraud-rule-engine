package za.co.fraudruleengine.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import za.co.fraudruleengine.domain.model.AlertStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "fraud_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudAlertEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "total_risk_score", nullable = false)
    private int totalRiskScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "fraud_alert_matched_rules",
            joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "rule_name")
    private List<String> matchedRules;

    @Column(name = "rule_details", columnDefinition = "text")
    private String ruleDetails;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
