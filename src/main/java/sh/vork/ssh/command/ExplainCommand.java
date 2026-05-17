package sh.vork.ssh.command;

import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.server.vsession.ShellCommand;
import com.sshtools.server.vsession.VirtualConsole;
import sh.vork.database.SearchQuery;
import sh.vork.ssh.VirtualCommand;
import sh.vork.typegen.SqlParseException;
import sh.vork.typegen.SqlQueryParser;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * SSH shell command: {@code explain}
 *
 * <p>Syntax:
 * <pre>
 * explain [where] &lt;sql-clause&gt;
 * </pre>
 *
 * <p>Parses the SQL WHERE clause using {@link SqlQueryParser} and prints the
 * resulting {@link SearchQuery} predicate tree as a human-readable indented string.
 */
public class ExplainCommand extends VirtualCommand {

    public ExplainCommand() {
        super("explain",
              ShellCommand.SUBSYSTEM_SHELL,
              "explain [where] <sql-where-clause>",
              "Show the SearchQuery predicate tree for a SQL WHERE clause");
    }

    @Override
    protected void doRun(String[] args, VirtualConsole console)
            throws IOException, PermissionDeniedException {
        execute(args, console::print);
    }

    /**
     * Core EXPLAIN logic — extracted for testability.
     *
     * @param args   raw command tokens (args[0] is "explain")
     * @param output consumer that receives the output string
     */
    static void execute(String[] args, Consumer<String> output) {
        if (args.length < 2) {
            output.accept("Usage: explain [where] <sql-clause>\r\n");
            return;
        }

        // Reconstruct everything after "explain"
        String clause = String.join(" ", args).trim();
        // Strip leading "explain" keyword (case-insensitive)
        clause = clause.substring("explain".length()).trim();
        // Optionally strip leading "where" keyword
        if (clause.toUpperCase().startsWith("WHERE")) {
            clause = clause.substring(5).trim();
        }

        if (clause.isEmpty()) {
            output.accept("Usage: explain [where] <sql-clause>\r\n");
            return;
        }

        try {
            SearchQuery query = SqlQueryParser.parse(clause);
            output.accept(formatTree(query, 0));
            output.accept("\r\n");
        } catch (SqlParseException e) {
            output.accept("Parse error: " + e.getMessage() + "\r\n");
        }
    }

    /**
     * Renders a {@link SearchQuery} predicate tree as an indented string.
     */
    static String formatTree(SearchQuery query, int depth) {
        String indent = "  ".repeat(depth);
        return switch (query) {
            case SearchQuery.And and -> {
                StringBuilder sb = new StringBuilder();
                sb.append(indent).append("AND\r\n");
                for (SearchQuery child : and.queries()) {
                    sb.append(formatTree(child, depth + 1));
                }
                yield sb.toString();
            }
            case SearchQuery.Or or -> {
                StringBuilder sb = new StringBuilder();
                sb.append(indent).append("OR\r\n");
                for (SearchQuery child : or.queries()) {
                    sb.append(formatTree(child, depth + 1));
                }
                yield sb.toString();
            }
            case SearchQuery.Not not -> {
                StringBuilder sb = new StringBuilder();
                sb.append(indent).append("NOT\r\n");
                sb.append(formatTree(not.query(), depth + 1));
                yield sb.toString();
            }
            case SearchQuery.Eq(String field, Object value) ->
                    indent + field + " = " + formatValue(value) + "\r\n";
            case SearchQuery.Ne(String field, Object value) ->
                    indent + field + " != " + formatValue(value) + "\r\n";
            case SearchQuery.Gt(String field, Object value) ->
                    indent + field + " > " + formatValue(value) + "\r\n";
            case SearchQuery.Gte(String field, Object value) ->
                    indent + field + " >= " + formatValue(value) + "\r\n";
            case SearchQuery.Lt(String field, Object value) ->
                    indent + field + " < " + formatValue(value) + "\r\n";
            case SearchQuery.Lte(String field, Object value) ->
                    indent + field + " <= " + formatValue(value) + "\r\n";
            case SearchQuery.Like(String field, String substring) ->
                    indent + field + " LIKE '%" + substring + "%'\r\n";
            case SearchQuery.Regex(String field, String pattern) ->
                    indent + field + " REGEX '" + pattern + "'\r\n";
            case SearchQuery.In(String field, java.util.List<?> values) ->
                    indent + field + " IN " + values + "\r\n";
            case SearchQuery.Exists(String field, boolean exists) ->
                    indent + field + (exists ? " IS NOT NULL" : " IS NULL") + "\r\n";
        };
    }

    private static String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "'" + s + "'";
        return value.toString();
    }
}
