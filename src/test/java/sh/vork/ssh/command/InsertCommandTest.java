package sh.vork.ssh.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InsertCommand#execute} and its helper tokenizers.
 */
class InsertCommandTest {

    TestServiceFixture fix;

    @BeforeEach
    void setup() {
        fix = new TestServiceFixture();
        fix.register(PersonRow.class);
    }

    @Nested
    class BasicInsert {

        @Test
        void insertsNewRow() {
            String uuid = UUID.randomUUID().toString();
            StringBuilder sb = new StringBuilder();
            InsertCommand.execute(
                    new String[]{"insert", "into", PersonRow.class.getName(),
                                 "(uuid,", "name,", "age,", "active)",
                                 "values", "('" + uuid + "',", "'Alice',", "30,", "true)"},
                    sb::append, fix.svc, fix.loader);
            assertEquals("1 row inserted\r\n", sb.toString());
            PersonRow loaded = (PersonRow) fix.svc.get(PersonRow.class, uuid);
            assertNotNull(loaded, "Row should have been saved");
            assertEquals("Alice", loaded.name());
            assertEquals(30, loaded.age());
            assertTrue(loaded.active());
        }

        @Test
        void insertsWithNullName() {
            String uuid = UUID.randomUUID().toString();
            StringBuilder sb = new StringBuilder();
            InsertCommand.execute(
                    new String[]{"insert", "into", PersonRow.class.getName(),
                                 "(uuid,", "name,", "age,", "active)",
                                 "values", "('" + uuid + "',", "null,", "0,", "false)"},
                    sb::append, fix.svc, fix.loader);
            assertEquals("1 row inserted\r\n", sb.toString());
            PersonRow loaded = (PersonRow) fix.svc.get(PersonRow.class, uuid);
            assertNull(loaded.name());
        }
    }

    @Nested
    class ColumnValueMismatch {

        @Test
        void mismatchPrintsError() {
            StringBuilder sb = new StringBuilder();
            InsertCommand.execute(
                    new String[]{"insert", "into", PersonRow.class.getName(),
                                 "(uuid,", "name)", "values", "('u1')"},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().toLowerCase().contains("error"));
        }
    }

    @Nested
    class UnknownType {

        @Test
        void unknownFqnPrintsError() {
            StringBuilder sb = new StringBuilder();
            InsertCommand.execute(
                    new String[]{"insert", "into", "com.example.Unknown",
                                 "(uuid)", "values", "('u1')"},
                    sb::append, fix.svc, fix.loader);
            assertTrue(sb.toString().toLowerCase().contains("error"));
        }
    }

    @Nested
    class TokenizerTests {

        @Test
        void tokenizeSimpleSplitsOnComma() {
            List<String> cols = InsertCommand.tokenizeSimple("a, b, c");
            assertEquals(List.of("a", "b", "c"), cols);
        }

        @Test
        void tokenizeValuesHandlesQuotedComma() {
            List<String> vals = InsertCommand.tokenizeValues("'hello, world', 42");
            assertEquals(2, vals.size());
            assertEquals("'hello, world'", vals.get(0));
            assertEquals("42", vals.get(1));
        }

        @Test
        void tokenizeValuesHandlesEscapedQuote() {
            List<String> vals = InsertCommand.tokenizeValues("'it''s fine'");
            assertEquals(1, vals.size());
            // Tokenizer converts '' escape to single ' inside the token
            assertEquals("'it's fine'", vals.get(0));
        }

        @Test
        void putValueNull() {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            InsertCommand.putValue(node, "f", "null");
            assertTrue(node.get("f").isNull());
        }

        @Test
        void putValueBoolean() {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            InsertCommand.putValue(node, "f", "true");
            assertTrue(node.get("f").asBoolean());
        }

        @Test
        void putValueLong() {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            InsertCommand.putValue(node, "f", "42");
            assertEquals(42L, node.get("f").asLong());
        }

        @Test
        void putValueDouble() {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            InsertCommand.putValue(node, "f", "3.14");
            assertEquals(3.14, node.get("f").asDouble(), 1e-10);
        }

        @Test
        void putValueString() {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            InsertCommand.putValue(node, "f", "'hello'");
            assertEquals("hello", node.get("f").asText());
        }
    }
}
