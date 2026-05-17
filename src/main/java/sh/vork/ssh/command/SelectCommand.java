package sh.vork.ssh.command;

import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.server.vsession.ShellCommand;
import com.sshtools.server.vsession.VirtualConsole;
import org.springframework.beans.factory.annotation.Autowired;
import sh.vork.database.SortOrder;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.SqlParseException;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.ssh.VirtualCommand;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * SSH shell command: {@code select}
 *
 * <p>Syntax:
 * <pre>
 * select * from &lt;fqn&gt; [where &lt;clause&gt;] [order by &lt;field&gt; [asc|desc]] [limit &lt;n&gt;] [offset &lt;n&gt;]
 * </pre>
 *
 * <p>Results are rendered as an ASCII table using {@link TableFormatter}.
 */
public class SelectCommand extends VirtualCommand {

    @Autowired
    TypeDatabaseService typeDatabaseService;

    @Autowired
    JavaTypeClassLoader classLoader;

    public SelectCommand() {
        super("select",
              ShellCommand.SUBSYSTEM_SHELL,
              "select * from <fqn> [where <clause>] [order by <field> [asc|desc]] [limit <n>] [offset <n>]",
              "Query database entities using SQL SELECT syntax");
    }

    @Override
    protected void doRun(String[] args, VirtualConsole console)
            throws IOException, PermissionDeniedException {
        execute(args, console::print, typeDatabaseService, classLoader);
    }

    /**
     * Core SELECT logic — extracted for testability.
     *
     * @param args   raw command tokens (args[0] is "select")
     * @param output consumer that receives the output string (including {@code \r\n})
     * @param svc    service used to query entities
     * @param loader class loader used to resolve the entity class
     */
    static void execute(String[] args, Consumer<String> output,
                        TypeDatabaseService svc, JavaTypeClassLoader loader) {
        String sql = String.join(" ", args);
        try {
            SelectStatement stmt = SelectStatement.parse(sql);

            Class<?> entityClass = loader.loadClass(stmt.fqn());

            int    pageSize   = stmt.limit()  > 0 ? stmt.limit()  : 20;
            int    page       = stmt.offset() > 0 ? stmt.offset() / pageSize : 0;
            String sortField  = stmt.orderByField() != null ? stmt.orderByField() : "uuid";
            SortOrder sortOrd = parseSortOrder(stmt.orderByDir());

            List<Object> results;
            if (stmt.whereClause() != null) {
                try (Stream<Object> s = svc.searchBySql(
                        entityClass, stmt.whereClause(), page, pageSize, sortField, sortOrd)) {
                    results = s.toList();
                }
            } else {
                try (Stream<Object> s = svc.list(entityClass, page, pageSize)) {
                    results = s.toList();
                }
            }

            output.accept(TableFormatter.format(results));

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

    private static SortOrder parseSortOrder(String dir) {
        if (dir == null) return SortOrder.ASC;
        try { return SortOrder.valueOf(dir.toUpperCase()); } catch (Exception e) { return SortOrder.ASC; }
    }
}
