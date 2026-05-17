package sh.vork.ssh.command;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SelectStatement}.
 */
class SelectStatementTest {

    @Nested
    class BasicParsing {

        @Test
        void selectAllNoWhere() {
            SelectStatement s = SelectStatement.parse("SELECT * FROM com.example.Foo");
            assertEquals("com.example.Foo", s.fqn());
            assertNull(s.whereClause());
            assertNull(s.orderByField());
            assertEquals("ASC", s.orderByDir());
            assertEquals(-1, s.limit());
            assertEquals(-1, s.offset());
        }

        @Test
        void caseInsensitiveKeywords() {
            SelectStatement s = SelectStatement.parse("select * from com.example.Foo");
            assertEquals("com.example.Foo", s.fqn());
        }

        @Test
        void leadingWhitespaceHandled() {
            SelectStatement s = SelectStatement.parse("  SELECT * FROM   com.example.Foo  ");
            assertEquals("com.example.Foo", s.fqn());
        }
    }

    @Nested
    class WithWhere {

        @Test
        void simpleWhereClause() {
            SelectStatement s = SelectStatement.parse("SELECT * FROM com.Foo WHERE name = 'Alice'");
            assertEquals("name = 'Alice'", s.whereClause());
        }

        @Test
        void wherePreservesCaseOfValues() {
            SelectStatement s = SelectStatement.parse("SELECT * FROM com.Foo WHERE name = 'Alice'");
            assertTrue(s.whereClause().contains("Alice"));
        }

        @Test
        void whereWithAndExpression() {
            SelectStatement s = SelectStatement.parse(
                    "SELECT * FROM com.Foo WHERE age > 18 AND active = true");
            assertEquals("age > 18 AND active = true", s.whereClause());
        }
    }

    @Nested
    class WithOrderBy {

        @Test
        void orderByAscDefault() {
            SelectStatement s = SelectStatement.parse("SELECT * FROM com.Foo ORDER BY name");
            assertEquals("name", s.orderByField());
            assertEquals("ASC", s.orderByDir());
        }

        @Test
        void orderByDesc() {
            SelectStatement s = SelectStatement.parse("SELECT * FROM com.Foo ORDER BY age DESC");
            assertEquals("age", s.orderByField());
            assertEquals("DESC", s.orderByDir());
        }

        @Test
        void orderByAscExplicit() {
            SelectStatement s = SelectStatement.parse("SELECT * FROM com.Foo ORDER BY age ASC");
            assertEquals("age", s.orderByField());
            assertEquals("ASC", s.orderByDir());
        }

        @Test
        void orderByWithWhere() {
            SelectStatement s = SelectStatement.parse(
                    "SELECT * FROM com.Foo WHERE age > 18 ORDER BY name DESC");
            assertEquals("age > 18", s.whereClause());
            assertEquals("name", s.orderByField());
            assertEquals("DESC", s.orderByDir());
        }
    }

    @Nested
    class WithLimitOffset {

        @Test
        void limitOnly() {
            SelectStatement s = SelectStatement.parse("SELECT * FROM com.Foo LIMIT 10");
            assertEquals(10, s.limit());
            assertEquals(-1, s.offset());
        }

        @Test
        void limitAndOffset() {
            SelectStatement s = SelectStatement.parse("SELECT * FROM com.Foo LIMIT 10 OFFSET 20");
            assertEquals(10, s.limit());
            assertEquals(20, s.offset());
        }

        @Test
        void allClauses() {
            SelectStatement s = SelectStatement.parse(
                    "SELECT * FROM com.Foo WHERE age > 5 ORDER BY name ASC LIMIT 5 OFFSET 10");
            assertEquals("com.Foo", s.fqn());
            assertEquals("age > 5", s.whereClause());
            assertEquals("name", s.orderByField());
            assertEquals("ASC", s.orderByDir());
            assertEquals(5, s.limit());
            assertEquals(10, s.offset());
        }
    }

    @Nested
    class WordBoundaries {

        @Test
        void fieldNamedOrderTotalDoesNotMatchOrderKeyword() {
            // WHERE clause contains 'order_total' — should not be mistaken for ORDER BY
            SelectStatement s = SelectStatement.parse(
                    "SELECT * FROM com.Foo WHERE order_total > 100");
            assertEquals("order_total > 100", s.whereClause());
            assertNull(s.orderByField());
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void missingFrom() {
            assertThrows(IllegalArgumentException.class,
                    () -> SelectStatement.parse("SELECT *"));
        }

        @Test
        void missingFqn() {
            assertThrows(IllegalArgumentException.class,
                    () -> SelectStatement.parse("SELECT * FROM"));
        }

        @Test
        void notASelectStatement() {
            assertThrows(IllegalArgumentException.class,
                    () -> SelectStatement.parse("INSERT INTO com.Foo"));
        }
    }
}
