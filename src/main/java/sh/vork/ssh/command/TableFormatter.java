package sh.vork.ssh.command;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a list of objects as a fixed-width ASCII table suitable for terminal
 * display (CRLF line endings for compatibility with PTY sessions).
 *
 * <p>Objects are converted to field maps via Jackson, so any Jackson-serialisable
 * type (records, POJOs, Maps) is supported.  Column order follows the field
 * declaration order of the first row.  Long values are truncated with an ellipsis
 * to keep columns readable.
 */
public class TableFormatter {

    static final int MAX_COL_WIDTH = 40;
    private static final String ELLIPSIS = "\u2026";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private TableFormatter() {}

    /**
     * Formats {@code rows} as an ASCII table string.
     * Returns {@code "(no rows)\r\n"} when the list is null or empty.
     */
    public static String format(List<Object> rows) {
        if (rows == null || rows.isEmpty()) {
            return "(no rows)\r\n";
        }

        List<String>             columns = new ArrayList<>();
        List<Map<String, String>> data   = new ArrayList<>();

        for (Object row : rows) {
            Map<?, ?> rawMap;
            try {
                rawMap = MAPPER.convertValue(row, Map.class);
            } catch (Exception e) {
                data.add(Map.of("(error)", String.valueOf(e.getMessage())));
                continue;
            }

            if (columns.isEmpty()) {
                for (Object k : rawMap.keySet()) {
                    columns.add(String.valueOf(k));
                }
            }

            Map<String, String> row2 = new LinkedHashMap<>();
            for (String col : columns) {
                Object v = rawMap.get(col);
                row2.put(col, v == null ? "null" : String.valueOf(v));
            }
            data.add(row2);
        }

        if (columns.isEmpty()) return "(no rows)\r\n";

        // Calculate column widths
        Map<String, Integer> widths = new LinkedHashMap<>();
        for (String col : columns) {
            widths.put(col, Math.min(col.length(), MAX_COL_WIDTH));
        }
        for (Map<String, String> row : data) {
            for (String col : columns) {
                String val = row.getOrDefault(col, "null");
                widths.put(col, Math.max(widths.get(col), Math.min(val.length(), MAX_COL_WIDTH)));
            }
        }

        // Build separator row
        StringBuilder sep = new StringBuilder("+");
        for (String col : columns) {
            sep.append("-".repeat(widths.get(col) + 2)).append("+");
        }
        sep.append("\r\n");

        // Build header row
        StringBuilder header = new StringBuilder("|");
        for (String col : columns) {
            header.append(" ").append(padRight(col, widths.get(col))).append(" |");
        }
        header.append("\r\n");

        // Build output
        StringBuilder sb = new StringBuilder();
        sb.append(sep).append(header).append(sep);

        for (Map<String, String> row : data) {
            sb.append("|");
            for (String col : columns) {
                String val = row.getOrDefault(col, "null");
                if (val.length() > MAX_COL_WIDTH) {
                    val = val.substring(0, MAX_COL_WIDTH - 1) + ELLIPSIS;
                }
                sb.append(" ").append(padRight(val, widths.get(col))).append(" |");
            }
            sb.append("\r\n");
        }

        sb.append(sep);
        int n = data.size();
        sb.append(n).append(n == 1 ? " row\r\n" : " rows\r\n");
        return sb.toString();
    }

    /** Right-pads {@code s} to exactly {@code width} characters. */
    static String padRight(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }
}
