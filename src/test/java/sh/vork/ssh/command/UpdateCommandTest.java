package sh.vork.ssh.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UpdateCommand#execute} and {@link UpdateCommand#parseSetClause}.
 */
class UpdateCommandTest {

    TestServiceFixture fix;

    @BeforeEach
    void setup() {
        fix = new TestServiceFixture();
        fix.register(PersonRow.class);

        fix.svc.save(new PersonRow("u1", "Alice", 30, true));
        fix.svc.save(new PersonRow("u2", "Bob",   25, false));
        fix.svc.save(new PersonRow("u3", "Carol", 40, true));
    }

    @Nested
    class UpdateWithWhere {

        @Test
        void updatesMatchingRow() {
            StringBuilder sb = new StringBuilder();
            UpdateCommand.execute(
                    new String[]{"update", PersonRow.class.getName(),
                                 "set", "name", "=", "'Alicia'",
                                 "where", "uuid", "=", "'u1'"},
                    sb::append, fix.svc, fix.loader);
            assertEquals("1 row(s) updated\r\n", sb.toString());

            PersonRow updated = (PersonRow) fix.svc.get(PersonRow.class, "u1");
            assertEquals("Alicia", updated.name());
        }

        @Test
        void noMatchUpdateZeroRows() {
            StringBuilder sb = new StringBuilder();
            UpdateCommand.execute(
                    new String[]{"update", PersonRow.class.getName(),
                                 "set", "name", "=", "'X'",
                                 "where", "uuid", "=", "'no-such-uuid'"},
                    sb::append, fix.svc, fix.loader);
            assertEquals("0 row(s) updated\r\n", sb.toString());
        }
    }

    @Nested
    class UpdateAllRows {

        @Test
        void updateWithoutWhereUpdatesAll() {
            StringBuilder sb = new StringBuilder();
            UpdateCommand.execute(
                    new String[]{"update", PersonRow.class.getName(),
                                 "set", "active", "=", "false"},
                    sb::append, fix.svc, fix.loader);
            assertEquals("3 row(s) updated\r\n", sb.toString());

            PersonRow u1 = (PersonRow) fix.svc.get(PersonRow.class, "u1");
            assertFalse(u1.active());
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void missingSetKeyword() {
            StringBuilder sb = new StringBuilder();
            UpdateCommand.execute(
                    new String[]{"update", PersonRow.class.getName(), "name", "=", "'X'"},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().toLowerCase().contains("error"));
        }

        @Test
        void unknownFqnPrintsError() {
            StringBuilder sb = new StringBuilder();
            UpdateCommand.execute(
                    new String[]{"update", "com.example.Unknown", "set", "name", "=", "'X'"},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().toLowerCase().contains("error"));
        }
    }

    @Nested
    class ParseSetClause {

        @Test
        void singleAssignment() {
            Map<String, String> m = UpdateCommand.parseSetClause("name = 'Alice'");
            assertEquals("'Alice'", m.get("name"));
        }

        @Test
        void multipleAssignments() {
            Map<String, String> m = UpdateCommand.parseSetClause("name = 'Alice', age = 30");
            assertEquals("'Alice'", m.get("name"));
            assertEquals("30", m.get("age"));
        }

        @Test
        void quotedValueWithComma() {
            Map<String, String> m = UpdateCommand.parseSetClause("name = 'Smith, Jr'");
            assertEquals(1, m.size());
            assertEquals("'Smith, Jr'", m.get("name"));
        }

        @Test
        void emptySetClauseReturnsEmptyMap() {
            Map<String, String> m = UpdateCommand.parseSetClause("   ");
            assertTrue(m.isEmpty());
        }
    }
}
