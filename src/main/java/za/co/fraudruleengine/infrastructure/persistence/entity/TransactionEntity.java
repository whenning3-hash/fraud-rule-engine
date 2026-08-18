package za.co.fraudruleengine.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "merchant_name", nullable = false)
    private String merchantName;

    @Column(name = "merchant_category")
    private String merchantCategory;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(name = "country_code", length = 3)
    private String countryCode;

    @Column(name = "transaction_time", nullable = false)
    private LocalDateTime transactionTime;

    @Column(name = "risk_score")
    private int riskScore;

    @Column(name = "is_fraudulent", nullable = false)
    private boolean fraudulent;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
