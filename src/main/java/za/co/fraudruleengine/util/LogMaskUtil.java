package za.co.fraudruleengine.util;

/**
 * Utility methods for masking personally identifiable and sensitive financial data
 * in log statements.
 *
 * <p>Banking regulations (POPIA, PCI-DSS) and general security hygiene require that account
 * numbers, card numbers, and transaction amounts are never written to log files in plain text,
 * even at DEBUG level. Log files are often stored for extended periods, accessible to a wider
 * set of personnel than production data, and may be shipped to external log aggregators.
 *
 * <p>Masking rules applied:
 * <ul>
 *   <li><b>Account identifiers</b> — first 3 chars + {@code ***} + last 3 chars retained for
 *       correlation; middle masked. e.g. {@code ACC-001234567} → {@code ACC***567}.</li>
 *   <li><b>Monetary amounts</b> — fully masked to {@code [MASKED]}. Even the magnitude of a
 *       transaction is considered sensitive financial data under POPIA.</li>
 * </ul>
 *
 * <p>All methods are null-safe and return a safe placeholder string for null inputs.
 */
public final class LogMaskUtil {

    /** Placeholder used when amount masking is applied. */
    public static final String MASKED = "[MASKED]";

    private LogMaskUtil() {
        // Utility class — no instances
    }

    /**
     * Masks an account identifier, retaining enough characters for log correlation
     * without exposing the full account number.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "ACC-001"} → {@code "ACC***"}</li>
     *   <li>{@code "9876543210"} → {@code "987***210"}</li>
     *   <li>{@code "AB"} → {@code "AB***"}</li>
     * </ul>
     *
     * @param accountId the raw account identifier; may be {@code null}
     * @return masked account string safe for inclusion in log messages
     */
    public static String maskAccount(String accountId) {
        if (accountId == null) {
            return "null";
        }
        int len = accountId.length();
        if (len <= 6) {
            // Short IDs: show up to first 3 chars, mask the rest
            return accountId.substring(0, Math.min(3, len)) + "***";
        }
        // Longer IDs: show first 3 + *** + last 3
        return accountId.substring(0, 3) + "***" + accountId.substring(len - 3);
    }

    /**
     * Masks a monetary amount entirely.
     *
     * <p>Transaction amounts — even in aggregate or trend form — constitute sensitive
     * financial data under POPIA and must not appear in log output.
     *
     * @param amount the raw amount value (as string or numeric {@code toString()}); may be {@code null}
     * @return the literal string {@code "[MASKED]"}
     */
    public static String maskAmount(Object amount) {
        return MASKED;
    }
}
