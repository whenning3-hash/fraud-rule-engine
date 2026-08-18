package za.co.fraudruleengine.domain.model;

/**
 * Represents the lifecycle state of a {@link za.co.fraudruleengine.infrastructure.persistence.entity.FraudAlertEntity}.
 *
 * <p>Alerts are created in the {@code OPEN} state whenever the fraud rule engine determines that a
 * transaction's aggregate risk score meets or exceeds the configured threshold. An analyst workflow
 * then drives the alert through the remaining states:
 *
 * <ul>
 *   <li>{@link #OPEN} – newly created; awaiting analyst triage</li>
 *   <li>{@link #REVIEWED} – an analyst has inspected the alert and is making a determination</li>
 *   <li>{@link #CLOSED} – investigation is complete (confirmed fraud or false positive)</li>
 * </ul>
 *
 * <p>Status transitions are managed by {@link za.co.fraudruleengine.application.AlertQueryService#updateStatus}
 * and exposed via {@link za.co.fraudruleengine.api.AlertController}.
 */
public enum AlertStatus {

    /** Alert has been raised and is awaiting analyst review. */
    OPEN,

    /** Alert is actively under investigation by an analyst. */
    REVIEWED,

    /** Investigation is complete; no further action required. */
    CLOSED
}
