package sh.vork.ssh.command;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TableFormatter}.
 */
class TableFormatterTest {

    @Nested
    class EmptyInput {

        @Test
        void emptyListReturnsNoRowsMessage() {
            String result = TableFormatter.format(List.of());
            assertEquals("(no rows)\r\n", result);
        }
    }

    @Nested
    class SingleRow {

        @Test
        void singleRowRendersCorrectly() {
            var row = new PersonRow("u1", "Alice", 30, true);
            String result = TableFormatter.format(List.of(row));
            assertTrue(result.contains("Alice"));
            assertTrue(result.contains("uuid"));
            assertTrue(result.contains("name"));
            assertTrue(result.contains("1 row\r\n"));
        }

        @Test
        void containsHeaderSeparator() {
            String result = TableFormatter.format(List.of(new PersonRow("u1", "Bob", 25, false)));
            // Should have at least three separator lines (top, after header, bottom)
            long separatorLines = result.lines().filter(l -> l.startsWith("+")).count();
            assertTrue(separatorLines >= 3, "Expected at least 3 separator lines, got: " + separatorLines);
        }
    }

    @Nested
    class MultiRow {

        @Test
        void multipleRowsAllAppear() {
            var rows = List.<Object>of(
                    new PersonRow("u1", "Alice", 30, true),
                    new PersonRow("u2", "Bob",   25, false),
                    new PersonRow("u3", "Carol", 40, true)
            );
            String result = TableFormatter.format(rows);
            assertTrue(result.contains("Alice"));
            assertTrue(result.contains("Bob"));
            assertTrue(result.contains("Carol"));
            assertTrue(result.contains("3 rows\r\n"));
        }
    }

    @Nested
    class Truncation {

        @Test
        void longValueTruncatedAt40Chars() {
            String longName = "A".repeat(50);
            var row = new PersonRow("u1", longName, 1, false);
            String result = TableFormatter.format(List.of(row));
            // Should contain truncated value ending with ellipsis
            assertTrue(result.contains("\u2026"), "Expected truncation ellipsis");
        }

        @Test
        void valueExactly40CharsNotTruncated() {
            String name40 = "B".repeat(40);
            var row = new PersonRow("u1", name40, 1, false);
            String result = TableFormatter.format(List.of(row));
            assertTrue(result.contains(name40), "40-char value should not be truncated");
        }
    }

    @Nested
    class NullValues {

        @Test
        void nullFieldRenderedAsNull() {
            var row = new PersonRow("u1", null, 0, false);
            String result = TableFormatter.format(List.of(row));
            assertTrue(result.contains("null"), "null field should render as 'null'");
        }
    }

    @Nested
    class PadRight {

        @Test
        void shortStringPaddedCorrectly() {
            assertEquals("hi   ", TableFormatter.padRight("hi", 5));
        }

        @Test
        void exactLengthUnchanged() {
            assertEquals("hello", TableFormatter.padRight("hello", 5));
        }

        @Test
        void nullTreatedAsEmpty() {
            assertEquals("     ", TableFormatter.padRight(null, 5));
        }
    }
}
