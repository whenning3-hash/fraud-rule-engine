package za.co.fraudruleengine.model;

import java.util.Set;

/**
 * Represents the lifecycle state of a {@link za.co.fraudruleengine.entity.FraudAlertEntity}.
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
 * <p>Valid forward transitions (enforced by
 * {@link za.co.fraudruleengine.service.AlertQueryService#updateStatus}):
 * <pre>
 *   OPEN  →  REVIEWED  (analyst picks up the alert)
 *   OPEN  →  CLOSED    (direct close — e.g. confirmed false positive during triage)
 *   REVIEWED  →  CLOSED  (investigation complete)
 * </pre>
 *
 * <p>All other transitions (e.g. {@code CLOSED → OPEN}, {@code REVIEWED → OPEN}) are illegal and
 * will throw {@link IllegalStateException}, which maps to HTTP 409 Conflict.
 */
public enum AlertStatus {

    /** Alert has been raised and is awaiting analyst review. */
    OPEN(Set.of("REVIEWED", "CLOSED")),

    /** Alert is actively under investigation by an analyst. */
    REVIEWED(Set.of("CLOSED")),

    /** Investigation is complete; no further action required. Terminal state — no transitions out. */
    CLOSED(Set.of());

    /**
     * Set of status names this state may legally transition to.
     * Uses names (not enum refs) to avoid forward-reference issues at enum initialisation time.
     */
    private final Set<String> allowedTargets;

    AlertStatus(Set<String> allowedTargets) {
        this.allowedTargets = allowedTargets;
    }

    /**
     * Returns {@code true} if transitioning from this status to {@code target} is a valid
     * forward move in the alert lifecycle.
     *
     * @param target the desired target status
     * @return {@code true} if the transition is permitted; {@code false} otherwise
     */
    public boolean canTransitionTo(AlertStatus target) {
        return allowedTargets.contains(target.name());
    }
}
