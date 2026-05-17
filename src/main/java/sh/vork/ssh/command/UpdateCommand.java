package sh.vork.ssh.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.server.vsession.ShellCommand;
import com.sshtools.server.vsession.VirtualConsole;
import org.springframework.beans.factory.annotation.Autowired;
import sh.vork.database.SearchQuery;
import sh.vork.database.SortOrder;
import sh.vork.ssh.VirtualCommand;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.SqlParseException;
import sh.vork.typegen.TypeDatabaseService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * SSH shell command: {@code update}
 *
 * <p>Syntax:
 * <pre>
 * update &lt;fqn&gt; set col1 = val1 [, col2 = val2, …] [where &lt;clause&gt;]
 * </pre>
 *
 * <p>Loads all matching entities, applies the SET assignments, and re-saves each one.
 */
public class UpdateCommand extends VirtualCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Autowired
    TypeDatabaseService typeDatabaseService;

    @Autowired
    JavaTypeClassLoader classLoader;

    public UpdateCommand() {
        super("update",
              ShellCommand.SUBSYSTEM_SHELL,
              "update <fqn> set col1 = val1 [, col2 = val2, …] [where <clause>]",
              "Update matching database entities");
    }

    @Override
    protected void doRun(String[] args, VirtualConsole console)
            throws IOException, PermissionDeniedException {
        execute(args, console::print, typeDatabaseService, classLoader);
    }

    /**
     * Core UPDATE logic — extracted for testability.
     */
    static void execute(String[] args, Consumer<String> output,
                        TypeDatabaseService svc, JavaTypeClassLoader loader) {
        String sql   = String.join(" ", args);
        String upper = sql.trim().toUpperCase();
        try {
            if (!upper.startsWith("UPDATE")) {
                output.accept("Error: expected UPDATE statement\r\n");
                return;
            }

            // FQN: between UPDATE and SET
            int setIdx = indexOfKeyword(upper, "SET", 6);
            if (setIdx < 0) {
                output.accept("Error: missing SET clause\r\n");
                return;
            }
            String fqn = sql.substring(6, setIdx).trim();
            if (fqn.isEmpty()) {
                output.accept("Error: missing type FQN\r\n");
                return;
            }

            // WHERE index (must come after SET clause)
            int whereIdx = indexOfKeyword(upper, "WHERE", setIdx + 3);

            // SET assignments text
            int setEnd = whereIdx >= 0 ? whereIdx : sql.length();
            String setClause = sql.substring(setIdx + 3, setEnd).trim();
            Map<String, String> assignments = parseSetClause(setClause);
            if (assignments.isEmpty()) {
                output.accept("Error: SET clause contains no assignments\r\n");
                return;
            }

            String whereClause = whereIdx >= 0
                    ? sql.substring(whereIdx + 5).trim()
                    : null;

            Class<?> entityClass = loader.loadClass(fqn);

            // Fetch matching entities
            List<Object> entities;
            if (whereClause != null && !whereClause.isEmpty()) {
                try (Stream<Object> s = svc.searchBySql(
                        entityClass, whereClause, 0, Integer.MAX_VALUE, "uuid", SortOrder.ASC)) {
                    entities = s.toList();
                }
            } else {
                try (Stream<Object> s = svc.list(entityClass, 0, Integer.MAX_VALUE)) {
                    entities = s.toList();
                }
            }

            // Apply assignments and re-save
            int updated = 0;
            for (Object entity : entities) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) MAPPER.convertValue(
                        entity, LinkedHashMap.class);
                for (Map.Entry<String, String> entry : assignments.entrySet()) {
                    applyValue(map, entry.getKey(), entry.getValue());
                }
                Object updated_entity = MAPPER.convertValue(map, entityClass);
                svc.save(updated_entity);
                updated++;
            }
            output.accept(updated + " row(s) updated\r\n");

        } catch (ClassNotFoundException e) {
            output.accept("Error: type not found — " + e.getMessage() + "\r\n");
        } catch (SqlParseException e) {
            output.accept("Error: " + e.getMessage() + "\r\n");
        } catch (IllegalArgumentException e) {
            output.accept("Error: " + e.getMessage() + "\r\n");
        } catch (Exception e) {
            output.accept("Error: " + e.getMessage() + "\r\n");
        }
    }

    // ── SET clause parser ─────────────────────────────────────────────────────

    /**
     * Parses a SET clause such as {@code name = 'Alice', age = 30} into a map of
     * field → raw SQL literal. Respects single-quoted string values.
     */
    static Map<String, String> parseSetClause(String setClause) {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> pairs = splitSetPairs(setClause);
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq).trim();
            String val = pair.substring(eq + 1).trim();
            if (!key.isEmpty()) result.put(key, val);
        }
        return result;
    }

    /** Splits the SET clause on commas that are not inside single-quoted strings. */
    private static List<String> splitSetPairs(String input) {
        List<String> result   = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
            } else if (c == '\'') {
                inString = true;
                current.append(c);
            } else if (c == ',') {
                result.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) result.add(last);
        return result;
    }

    /** Applies a raw SQL literal to the map field {@code key}. */
    @SuppressWarnings("unchecked")
    private static void applyValue(Map<String, Object> map, String key, String rawValue) {
        String v = rawValue.trim();
        if ("null".equalsIgnoreCase(v)) {
            map.put(key, null);
        } else if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2) {
            map.put(key, v.substring(1, v.length() - 1).replace("''", "'"));
        } else if ("true".equalsIgnoreCase(v)) {
            map.put(key, Boolean.TRUE);
        } else if ("false".equalsIgnoreCase(v)) {
            map.put(key, Boolean.FALSE);
        } else {
            try { map.put(key, Long.parseLong(v)); return; } catch (NumberFormatException ignored) {}
            try { map.put(key, Double.parseDouble(v)); return; } catch (NumberFormatException ignored) {}
            map.put(key, v);
        }
    }

    /** Word-boundary-aware keyword search. */
    private static int indexOfKeyword(String upper, String keyword, int from) {
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
}
