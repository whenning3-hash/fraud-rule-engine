package za.co.fraudruleengine.model;

/**
 * Enumeration of recognised transaction origination channels.
 *
 * <p>Using an enum prevents raw string comparisons (and the associated {@code toUpperCase()}
 * workarounds) when rules need to inspect the channel field.  The {@link #fromString(String)}
 * factory provides a null-safe, case-insensitive lookup so that inbound API strings are still
 * accepted without validation friction.
 *
 * <p>Channel values are stored as strings in the database (via {@code @Enumerated(EnumType.STRING)})
 * and flow through the API as plain strings in {@link za.co.fraudruleengine.api.dto.TransactionRequest}.
 * The canonical uppercase names here must match the values stored in the {@code transactions} table.
 */
public enum ChannelType {

    /** Automated teller machine — physical card-present cash withdrawal. */
    ATM,

    /** Point-of-sale terminal — card-present purchase or cash-back transaction. */
    POS,

    /** Online / e-commerce — card-not-present digital purchase. */
    ONLINE,

    /** Mobile application — transaction initiated from a banking app on a mobile device. */
    MOBILE;

    /**
     * Returns the {@link ChannelType} whose name matches {@code value} (case-insensitive),
     * or {@code null} if the value is {@code null}, blank, or unrecognised.
     *
     * <p>This factory is used by rules that receive the raw string channel from the domain model
     * so that they can perform type-safe comparisons without throwing on unexpected values.
     *
     * @param value the raw channel string from the transaction
     * @return the matching {@link ChannelType}, or {@code null} for unknown/absent values
     */
    public static ChannelType fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ChannelType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
