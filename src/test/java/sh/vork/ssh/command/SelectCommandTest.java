package sh.vork.ssh.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SelectCommand#execute}.
 */
class SelectCommandTest {

    TestServiceFixture fix;

    @BeforeEach
    void setup() {
        fix = new TestServiceFixture();
        fix.register(PersonRow.class);

        // Seed some data
        fix.svc.save(new PersonRow("u1", "Alice", 30, true));
        fix.svc.save(new PersonRow("u2", "Bob",   25, false));
        fix.svc.save(new PersonRow("u3", "Carol", 40, true));
    }

    @Nested
    class BasicSelect {

        @Test
        void selectAllReturnsAllRows() {
            StringBuilder sb = new StringBuilder();
            SelectCommand.execute(
                    new String[]{"select", "*", "from", PersonRow.class.getName()},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().contains("Alice"));
            assertTrue(sb.toString().contains("Bob"));
            assertTrue(sb.toString().contains("Carol"));
            assertTrue(sb.toString().contains("3 rows"));
        }

        @Test
        void selectWithWhereFilters() {
            StringBuilder sb = new StringBuilder();
            SelectCommand.execute(
                    new String[]{"select", "*", "from", PersonRow.class.getName(),
                                 "where", "uuid", "=", "'u1'"},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().contains("Alice"),
                    "Expected Alice in: " + sb);
            assertFalse(sb.toString().contains("Bob"),
                    "Did not expect Bob in: " + sb);
            assertTrue(sb.toString().contains("1 row"),
                    "Expected 1 row in: " + sb);
        }

        @Test
        void selectWithAgeFilter() {
            StringBuilder sb = new StringBuilder();
            SelectCommand.execute(
                    new String[]{"select", "*", "from", PersonRow.class.getName(),
                                 "where", "age", ">", "25"},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().contains("Alice"));
            assertTrue(sb.toString().contains("Carol"));
            assertFalse(sb.toString().contains("Bob"));
        }
    }

    @Nested
    class UnknownType {

        @Test
        void unknownFqnPrintsError() {
            StringBuilder sb = new StringBuilder();
            SelectCommand.execute(
                    new String[]{"select", "*", "from", "com.example.DoesNotExist"},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().toLowerCase().contains("error"));
        }
    }

    @Nested
    class EmptyResult {

        @Test
        void emptyResultShowsNoRows() {
            StringBuilder sb = new StringBuilder();
            SelectCommand.execute(
                    new String[]{"select", "*", "from", PersonRow.class.getName(),
                                 "where", "name", "=", "'Nobody'"},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().contains("(no rows)"));
        }
    }
}
