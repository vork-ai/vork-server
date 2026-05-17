package sh.vork.ssh.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.server.vsession.ShellCommand;
import com.sshtools.server.vsession.VirtualConsole;
import org.springframework.beans.factory.annotation.Autowired;
import sh.vork.ssh.VirtualCommand;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SSH shell command: {@code insert}
 *
 * <p>Syntax:
 * <pre>
 * insert into &lt;fqn&gt; (&lt;col1&gt;, &lt;col2&gt;, …) values (&lt;val1&gt;, &lt;val2&gt;, …)
 * </pre>
 *
 * <p>String values must be single-quoted; {@code ''} escapes a literal single quote.
 * Numeric, boolean and {@code null} literals are also accepted.
 * A {@code uuid} field is required; the record will fail validation if omitted.
 */
public class InsertCommand extends VirtualCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Autowired
    TypeDatabaseService typeDatabaseService;

    @Autowired
    JavaTypeClassLoader classLoader;

    public InsertCommand() {
        super("insert",
              ShellCommand.SUBSYSTEM_SHELL,
              "insert into <fqn> (<col1>, <col2>, …) values (<val1>, <val2>, …)",
              "Insert a new entity into the database");
    }

    @Override
    protected void doRun(String[] args, VirtualConsole console)
            throws IOException, PermissionDeniedException {
        execute(args, console::print, typeDatabaseService, classLoader);
    }

    /**
     * Core INSERT logic — extracted for testability.
     */
    static void execute(String[] args, Consumer<String> output,
                        TypeDatabaseService svc, JavaTypeClassLoader loader) {
        String sql = String.join(" ", args);
        try {
            String upper = sql.trim().toUpperCase();

            // Expect: INSERT INTO <fqn> (<cols>) VALUES (<vals>)
            if (!upper.startsWith("INSERT")) {
                output.accept("Error: expected INSERT statement\r\n");
                return;
            }
            int intoIdx = upper.indexOf("INTO");
            if (intoIdx < 0) {
                output.accept("Error: missing INTO keyword\r\n");
                return;
            }

            // Extract FQN (between INTO and first '(')
            int fqnStart = skipSpaces(sql, intoIdx + 4);
            int fqnEnd   = sql.indexOf('(', fqnStart);
            if (fqnEnd < 0) {
                output.accept("Error: missing column list — expected '(' after type name\r\n");
                return;
            }
            String fqn = sql.substring(fqnStart, fqnEnd).trim();

            // Extract columns list
            int colsEnd = sql.indexOf(')', fqnEnd);
            if (colsEnd < 0) {
                output.accept("Error: unterminated column list\r\n");
                return;
            }
            String colsStr = sql.substring(fqnEnd + 1, colsEnd);
            List<String> columns = tokenizeSimple(colsStr);

            // Find VALUES keyword
            int valuesIdx = upper.indexOf("VALUES", colsEnd);
            if (valuesIdx < 0) {
                output.accept("Error: missing VALUES keyword\r\n");
                return;
            }
            int vStart = sql.indexOf('(', valuesIdx + 6);
            if (vStart < 0) {
                output.accept("Error: missing '(' after VALUES\r\n");
                return;
            }
            int vEnd = findClosingParen(sql, vStart);
            if (vEnd < 0) {
                output.accept("Error: unterminated VALUES list\r\n");
                return;
            }
            String valsStr = sql.substring(vStart + 1, vEnd);
            List<String> values = tokenizeValues(valsStr);

            if (columns.size() != values.size()) {
                output.accept("Error: column count (" + columns.size() +
                              ") does not match value count (" + values.size() + ")\r\n");
                return;
            }

            // Build JSON object
            ObjectNode node = MAPPER.createObjectNode();
            for (int i = 0; i < columns.size(); i++) {
                putValue(node, columns.get(i).trim(), values.get(i));
            }

            // Convert to entity and save
            Class<?> entityClass = loader.loadClass(fqn);
            Object entity = MAPPER.convertValue(node, entityClass);
            svc.save(entity);
            output.accept("1 row inserted\r\n");

        } catch (ClassNotFoundException e) {
            output.accept("Error: type not found — " + e.getMessage() + "\r\n");
        } catch (IllegalArgumentException e) {
            output.accept("Error: " + e.getMessage() + "\r\n");
        } catch (Exception e) {
            output.accept("Error: " + e.getMessage() + "\r\n");
        }
    }

    // ── SQL value tokenizer ───────────────────────────────────────────────────

    /**
     * Splits a comma-separated list of SQL values, respecting single-quoted strings.
     * Single-quoted strings may contain {@code ''} as an escape for a literal quote.
     */
    static List<String> tokenizeValues(String input) {
        List<String> result  = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++; // consume the second quote
                    } else {
                        inString = false;
                        current.append('\'');
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'') {
                    inString = true;
                    current.append('\'');
                } else if (c == ',') {
                    result.add(current.toString().trim());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty() || !result.isEmpty()) {
            result.add(last);
        }
        return result;
    }

    /**
     * Splits a simple comma-separated list of identifiers (no quoting).
     */
    static List<String> tokenizeSimple(String input) {
        List<String> result = new ArrayList<>();
        for (String token : input.split(",")) {
            result.add(token.trim());
        }
        return result;
    }

    /**
     * Sets the field {@code key} on {@code node} using the type inferred from
     * the raw SQL literal {@code rawValue}.
     */
    static void putValue(ObjectNode node, String key, String rawValue) {
        String v = rawValue.trim();
        if ("null".equalsIgnoreCase(v)) {
            node.putNull(key);
        } else if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2) {
            node.put(key, v.substring(1, v.length() - 1).replace("''", "'"));
        } else if ("true".equalsIgnoreCase(v)) {
            node.put(key, true);
        } else if ("false".equalsIgnoreCase(v)) {
            node.put(key, false);
        } else {
            try {
                node.put(key, Long.parseLong(v));
            } catch (NumberFormatException e1) {
                try {
                    node.put(key, Double.parseDouble(v));
                } catch (NumberFormatException e2) {
                    node.put(key, v); // treat as string
                }
            }
        }
    }

    /** Returns the index of the {@code )} that closes the {@code (} at {@code openIdx}. */
    static int findClosingParen(String s, int openIdx) {
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++; // escaped quote
                } else if (c == '\'') {
                    inStr = false;
                }
            } else if (c == '\'') {
                inStr = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int skipSpaces(String s, int from) {
        while (from < s.length() && s.charAt(from) == ' ') from++;
        return from;
    }
}
