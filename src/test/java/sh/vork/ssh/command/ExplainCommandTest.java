package sh.vork.ssh.command;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import sh.vork.database.SearchQuery;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExplainCommand#execute} and {@link ExplainCommand#formatTree}.
 */
class ExplainCommandTest {

    @Nested
    class UsageErrors {

        @Test
        void noArgsShowsUsage() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(new String[]{"explain"}, sb::append);
            assertTrue(sb.toString().contains("Usage"));
        }

        @Test
        void emptyAfterWhereShowsUsage() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(new String[]{"explain", "where"}, sb::append);
            assertTrue(sb.toString().contains("Usage"));
        }
    }

    @Nested
    class SimplePredicates {

        @Test
        void eqPredicateFormatted() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(new String[]{"explain", "name", "=", "'Alice'"}, sb::append);
            String out = sb.toString();
            assertTrue(out.contains("name"));
            assertTrue(out.contains("="));
            assertTrue(out.contains("Alice"));
        }

        @Test
        void gtPredicateFormatted() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(new String[]{"explain", "age", ">", "18"}, sb::append);
            String out = sb.toString();
            assertTrue(out.contains("age"));
            assertTrue(out.contains(">"));
        }

        @Test
        void likePredicateFormatted() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(new String[]{"explain", "name", "LIKE", "'%ali%'"}, sb::append);
            String out = sb.toString();
            assertTrue(out.contains("LIKE"));
        }

        @Test
        void inPredicateFormatted() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(
                    new String[]{"explain", "status", "IN", "('active',", "'pending')"},
                    sb::append);
            String out = sb.toString();
            assertTrue(out.contains("IN"), "Expected IN in output: " + out);
        }

        @Test
        void isNullPredicateFormatted() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(new String[]{"explain", "deletedAt", "IS", "NULL"}, sb::append);
            String out = sb.toString();
            assertTrue(out.contains("IS NULL"), "Expected IS NULL in output: " + out);
        }
    }

    @Nested
    class CompoundPredicates {

        @Test
        void andPredicateShowsChildren() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(
                    new String[]{"explain", "name", "=", "'Alice'", "AND", "age", ">", "18"},
                    sb::append);
            String out = sb.toString();
            assertTrue(out.contains("AND"));
            assertTrue(out.contains("name"));
            assertTrue(out.contains("age"));
        }

        @Test
        void orPredicateShowsChildren() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(
                    new String[]{"explain", "age", "=", "18", "OR", "age", "=", "25"},
                    sb::append);
            String out = sb.toString();
            assertTrue(out.contains("OR"));
        }

        @Test
        void notPredicateFormatted() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(
                    new String[]{"explain", "NOT", "active", "=", "false"},
                    sb::append);
            String out = sb.toString();
            assertTrue(out.contains("NOT"));
        }
    }

    @Nested
    class WhereKeywordStripped {

        @Test
        void whereKeywordIsStrippedBeforeParsing() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(
                    new String[]{"explain", "where", "name", "=", "'Bob'"},
                    sb::append);
            String out = sb.toString();
            assertFalse(out.toLowerCase().contains("error"), "Should not be a parse error: " + out);
            assertTrue(out.contains("name"));
        }
    }

    @Nested
    class ParseError {

        @Test
        void invalidClauseReturnsError() {
            StringBuilder sb = new StringBuilder();
            ExplainCommand.execute(
                    new String[]{"explain", "&&&&"},
                    sb::append);
            String out = sb.toString();
            assertTrue(out.toLowerCase().contains("error") || out.toLowerCase().contains("parse"),
                    "Expected parse error, got: " + out);
        }
    }

    @Nested
    class FormatTreeDirect {

        @Test
        void eqNodeFormatted() {
            String s = ExplainCommand.formatTree(SearchQuery.eq("field", "val"), 0);
            assertEquals("field = 'val'\r\n", s);
        }

        @Test
        void neNodeFormatted() {
            String s = ExplainCommand.formatTree(SearchQuery.ne("field", "val"), 0);
            assertEquals("field != 'val'\r\n", s);
        }

        @Test
        void gtNodeFormatted() {
            String s = ExplainCommand.formatTree(SearchQuery.gt("age", 18), 0);
            assertEquals("age > 18\r\n", s);
        }

        @Test
        void existsNotNull() {
            String s = ExplainCommand.formatTree(SearchQuery.exists("x", true), 0);
            assertEquals("x IS NOT NULL\r\n", s);
        }

        @Test
        void existsNull() {
            String s = ExplainCommand.formatTree(SearchQuery.exists("x", false), 0);
            assertEquals("x IS NULL\r\n", s);
        }

        @Test
        void andNodeIndentsChildren() {
            String s = ExplainCommand.formatTree(
                    SearchQuery.and(SearchQuery.eq("a", 1), SearchQuery.eq("b", 2)), 0);
            assertTrue(s.startsWith("AND\r\n"));
            assertTrue(s.contains("  a = 1\r\n"));
            assertTrue(s.contains("  b = 2\r\n"));
        }

        @Test
        void orNodeIndentsChildren() {
            String s = ExplainCommand.formatTree(
                    SearchQuery.or(SearchQuery.eq("a", 1), SearchQuery.eq("b", 2)), 0);
            assertTrue(s.startsWith("OR\r\n"));
        }

        @Test
        void notNodeWrapsChild() {
            String s = ExplainCommand.formatTree(
                    SearchQuery.not(SearchQuery.eq("a", 1)), 0);
            assertTrue(s.startsWith("NOT\r\n"));
            assertTrue(s.contains("  a = 1\r\n"));
        }

        @Test
        void likeNodeFormatted() {
            String s = ExplainCommand.formatTree(SearchQuery.like("name", "ali"), 0);
            assertTrue(s.contains("name") && s.contains("LIKE") && s.contains("ali"));
        }

        @Test
        void nullValueFormatted() {
            String s = ExplainCommand.formatTree(SearchQuery.eq("x", null), 0);
            assertEquals("x = null\r\n", s);
        }
    }
}
