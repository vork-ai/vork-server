package sh.vork.ssh.command;

/**
 * Parsed representation of a SQL {@code SELECT} statement.
 *
 * <p>Supported syntax:
 * <pre>
 * SELECT * FROM &lt;fqn&gt;
 *     [WHERE &lt;sql-clause&gt;]
 *     [ORDER BY &lt;field&gt; [ASC | DESC]]
 *     [LIMIT &lt;n&gt;]
 *     [OFFSET &lt;n&gt;]
 * </pre>
 *
 * <p>Field projection ({@code SELECT a, b, c}) is not supported; the leading
 * projection before {@code FROM} is accepted but ignored — all fields are always
 * returned.
 *
 * <p>Keyword matching is case-insensitive and respects word boundaries, so a
 * field named {@code order_total} does not accidentally match the keyword {@code ORDER}.
 */
public record SelectStatement(
        String fqn,
        String whereClause,
        String orderByField,
        String orderByDir,
        int    limit,
        int    offset
) {

    /**
     * Parses a full {@code SELECT …} statement.
     *
     * @param sql the SQL string (leading/trailing whitespace is stripped)
     * @throws IllegalArgumentException if the statement is syntactically invalid
     */
    public static SelectStatement parse(String sql) {
        String normalized = sql.trim();
        String upper      = normalized.toUpperCase();

        if (!upper.startsWith("SELECT")) {
            throw new IllegalArgumentException("Expected SELECT statement, got: " + normalized);
        }

        int fromIdx = indexOfKeyword(upper, "FROM", 0);
        if (fromIdx < 0) {
            throw new IllegalArgumentException("Missing FROM clause in: " + normalized);
        }

        // Locate optional clause start positions
        int whereIdx  = indexOfKeyword(upper, "WHERE",  fromIdx + 4);
        int orderIdx  = indexOfKeyword(upper, "ORDER",  fromIdx + 4);
        int limitIdx  = indexOfKeyword(upper, "LIMIT",  fromIdx + 4);
        int offsetIdx = indexOfKeyword(upper, "OFFSET", fromIdx + 4);

        // FQN: from after FROM to the first of WHERE / ORDER / LIMIT / OFFSET
        int fqnStart = skipSpaces(upper, fromIdx + 4);
        int fqnEnd   = firstPositive(whereIdx, orderIdx, limitIdx, offsetIdx);
        if (fqnEnd < 0) fqnEnd = normalized.length();
        String fqn = normalized.substring(fqnStart, fqnEnd).trim();
        if (fqn.isEmpty()) {
            throw new IllegalArgumentException("Missing type FQN after FROM in: " + normalized);
        }

        // WHERE clause: from after WHERE keyword to next clause keyword
        String whereClause = null;
        if (whereIdx >= 0) {
            int wStart = skipSpaces(normalized, whereIdx + 5);
            int wEnd   = firstPositiveAfter(whereIdx, orderIdx, limitIdx, offsetIdx);
            if (wEnd < 0) wEnd = normalized.length();
            whereClause = normalized.substring(wStart, wEnd).trim();
            if (whereClause.isEmpty()) whereClause = null;
        }

        // ORDER BY: look for BY after ORDER, then parse field [dir]
        String orderByField = null;
        String orderByDir   = "ASC";
        if (orderIdx >= 0) {
            int byIdx = indexOfKeyword(upper, "BY", orderIdx + 5);
            if (byIdx >= 0) {
                int obStart = skipSpaces(normalized, byIdx + 2);
                int obEnd   = firstPositiveAfter(orderIdx, limitIdx, offsetIdx, -1);
                if (obEnd < 0) obEnd = normalized.length();
                String obClause = normalized.substring(obStart, obEnd).trim();
                String[] parts  = obClause.split("\\s+", 2);
                orderByField = parts[0];
                if (parts.length > 1) {
                    orderByDir = parts[1].trim().toUpperCase();
                }
            }
        }

        // LIMIT
        int limit = -1;
        if (limitIdx >= 0) {
            int ls = skipSpaces(upper, limitIdx + 5);
            int le = ls;
            while (le < upper.length() && Character.isDigit(upper.charAt(le))) le++;
            try { limit = Integer.parseInt(upper.substring(ls, le)); } catch (Exception ignored) {}
        }

        // OFFSET
        int offset = -1;
        if (offsetIdx >= 0) {
            int os = skipSpaces(upper, offsetIdx + 6);
            int oe = os;
            while (oe < upper.length() && Character.isDigit(upper.charAt(oe))) oe++;
            try { offset = Integer.parseInt(upper.substring(os, oe)); } catch (Exception ignored) {}
        }

        return new SelectStatement(fqn, whereClause, orderByField, orderByDir, limit, offset);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Finds the first occurrence of {@code keyword} in {@code upper} at or after
     * position {@code from} that is surrounded by non-word characters (word boundary
     * check prevents partial matches like ORDER in ORDER_ID).
     */
    static int indexOfKeyword(String upper, String keyword, int from) {
        int idx = from;
        int len = keyword.length();
        while (idx <= upper.length() - len) {
            int pos = upper.indexOf(keyword, idx);
            if (pos < 0) return -1;
            boolean beforeOk = (pos == 0) || !isWordChar(upper.charAt(pos - 1));
            boolean afterOk  = (pos + len >= upper.length()) || !isWordChar(upper.charAt(pos + len));
            if (beforeOk && afterOk) return pos;
            idx = pos + 1;
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int skipSpaces(String s, int from) {
        while (from < s.length() && s.charAt(from) == ' ') from++;
        return from;
    }

    /** Minimum of the positive values; -1 if none are positive. */
    private static int firstPositive(int... vals) {
        int min = -1;
        for (int v : vals) {
            if (v >= 0 && (min < 0 || v < min)) min = v;
        }
        return min;
    }

    /** Minimum positive value strictly greater than {@code after}. */
    private static int firstPositiveAfter(int after, int... vals) {
        int min = -1;
        for (int v : vals) {
            if (v > after && (min < 0 || v < min)) min = v;
        }
        return min;
    }
}
