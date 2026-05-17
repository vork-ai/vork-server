package sh.vork.database;

import com.mongodb.MongoClientSettings;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SearchQuery}.
 *
 * <p>Two aspects are verified for each predicate type:
 * <ol>
 *   <li><b>MongoDB Bson output</b> — the filter produced by {@link SearchQuery#toMongoFilter()}
 *       is checked by rendering it to a {@link BsonDocument} and asserting on its
 *       structure.  This validates that every predicate maps to the correct MongoDB
 *       query operator.</li>
 *   <li><b>In-memory evaluation</b> — {@link SearchQuery#test(Map)} is exercised
 *       with documents that should match and documents that should not.  This
 *       validates the mock-repository path and serves as documentation of the
 *       predicate semantics.</li>
 * </ol>
 *
 * <p>No Spring context, no MongoDB server, and no network connection are required.
 */
class SearchQueryTest {

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Renders a {@link SearchQuery}'s filter to a {@link BsonDocument} for assertions. */
    private static BsonDocument bson(SearchQuery q) {
        return q.toMongoFilter()
                .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    // ── Eq ────────────────────────────────────────────────────────────────────

    @Nested
    class EqTests {

        @Test
        void mongoFilter_stringField() {
            BsonDocument doc = bson(SearchQuery.eq("name", "Alice"));
            assertEquals("Alice", doc.getString("name").getValue());
        }

        @Test
        void mongoFilter_intField() {
            BsonDocument doc = bson(SearchQuery.eq("age", 30));
            assertEquals(30, doc.getInt32("age").getValue());
        }

        @Test
        void mongoFilter_booleanField() {
            BsonDocument doc = bson(SearchQuery.eq("active", true));
            assertTrue(doc.getBoolean("active").getValue());
        }

        @Test
        void inMemory_matchesExact() {
            SearchQuery q = SearchQuery.eq("name", "Alice");
            assertTrue(q.test(Map.of("name", "Alice", "age", 30)));
        }

        @Test
        void inMemory_doesNotMatchDifferentValue() {
            SearchQuery q = SearchQuery.eq("name", "Alice");
            assertFalse(q.test(Map.of("name", "Bob", "age", 25)));
        }

        @Test
        void inMemory_numericCrossType_integerFromJson() {
            // Jackson deserialises JSON integers as Integer; query uses int literal.
            SearchQuery q = SearchQuery.eq("age", 30);
            assertTrue(q.test(Map.of("age", 30)));   // Integer(30) == Integer(30)
        }

        @Test
        void inMemory_missingField_doesNotMatch() {
            SearchQuery q = SearchQuery.eq("missing", "x");
            assertFalse(q.test(Map.of("name", "Alice")));
        }
    }

    // ── Ne ────────────────────────────────────────────────────────────────────

    @Nested
    class NeTests {

        @Test
        void mongoFilter_producesNeOperator() {
            BsonDocument doc = bson(SearchQuery.ne("status", "deleted"));
            assertEquals("deleted",
                    doc.getDocument("status").getString("$ne").getValue());
        }

        @Test
        void inMemory_matchesWhenNotEqual() {
            SearchQuery q = SearchQuery.ne("status", "deleted");
            assertTrue(q.test(Map.of("status", "active")));
        }

        @Test
        void inMemory_doesNotMatchWhenEqual() {
            SearchQuery q = SearchQuery.ne("status", "deleted");
            assertFalse(q.test(Map.of("status", "deleted")));
        }
    }

    // ── Gt ────────────────────────────────────────────────────────────────────

    @Nested
    class GtTests {

        @Test
        void mongoFilter_producesGtOperator() {
            BsonDocument doc = bson(SearchQuery.gt("age", 18));
            assertEquals(18, doc.getDocument("age").getInt32("$gt").getValue());
        }

        @Test
        void inMemory_matchesWhenGreater() {
            assertTrue(SearchQuery.gt("age", 18).test(Map.of("age", 25)));
        }

        @Test
        void inMemory_doesNotMatchWhenEqual() {
            assertFalse(SearchQuery.gt("age", 25).test(Map.of("age", 25)));
        }

        @Test
        void inMemory_doesNotMatchWhenLess() {
            assertFalse(SearchQuery.gt("age", 25).test(Map.of("age", 20)));
        }

        @Test
        void inMemory_doubleField() {
            assertTrue(SearchQuery.gt("score", 7.0).test(Map.of("score", 9.5)));
            assertFalse(SearchQuery.gt("score", 9.5).test(Map.of("score", 7.0)));
        }
    }

    // ── Gte ───────────────────────────────────────────────────────────────────

    @Nested
    class GteTests {

        @Test
        void mongoFilter_producesGteOperator() {
            BsonDocument doc = bson(SearchQuery.gte("age", 18));
            assertEquals(18, doc.getDocument("age").getInt32("$gte").getValue());
        }

        @Test
        void inMemory_matchesWhenEqual() {
            assertTrue(SearchQuery.gte("age", 25).test(Map.of("age", 25)));
        }

        @Test
        void inMemory_matchesWhenGreater() {
            assertTrue(SearchQuery.gte("age", 18).test(Map.of("age", 25)));
        }

        @Test
        void inMemory_doesNotMatchWhenLess() {
            assertFalse(SearchQuery.gte("age", 30).test(Map.of("age", 25)));
        }
    }

    // ── Lt ────────────────────────────────────────────────────────────────────

    @Nested
    class LtTests {

        @Test
        void mongoFilter_producesLtOperator() {
            BsonDocument doc = bson(SearchQuery.lt("age", 65));
            assertEquals(65, doc.getDocument("age").getInt32("$lt").getValue());
        }

        @Test
        void inMemory_matchesWhenLess() {
            assertTrue(SearchQuery.lt("age", 65).test(Map.of("age", 30)));
        }

        @Test
        void inMemory_doesNotMatchWhenEqual() {
            assertFalse(SearchQuery.lt("age", 30).test(Map.of("age", 30)));
        }

        @Test
        void inMemory_doesNotMatchWhenGreater() {
            assertFalse(SearchQuery.lt("age", 18).test(Map.of("age", 30)));
        }
    }

    // ── Lte ───────────────────────────────────────────────────────────────────

    @Nested
    class LteTests {

        @Test
        void mongoFilter_producesLteOperator() {
            BsonDocument doc = bson(SearchQuery.lte("age", 65));
            assertEquals(65, doc.getDocument("age").getInt32("$lte").getValue());
        }

        @Test
        void inMemory_matchesWhenEqual() {
            assertTrue(SearchQuery.lte("age", 30).test(Map.of("age", 30)));
        }

        @Test
        void inMemory_matchesWhenLess() {
            assertTrue(SearchQuery.lte("age", 65).test(Map.of("age", 30)));
        }

        @Test
        void inMemory_doesNotMatchWhenGreater() {
            assertFalse(SearchQuery.lte("age", 18).test(Map.of("age", 30)));
        }
    }

    // ── Like ──────────────────────────────────────────────────────────────────

    @Nested
    class LikeTests {

        @Test
        void mongoFilter_producesRegexWithCaseInsensitiveFlag() {
            BsonDocument doc = bson(SearchQuery.like("name", "alice"));
            // MongoDB driver stores regex as BsonRegularExpression, not a sub-document
            org.bson.BsonRegularExpression re = doc.getRegularExpression("name");
            assertEquals("i", re.getOptions());
            // The regex pattern must contain the literal substring (via Pattern.quote)
            assertTrue(re.getPattern().contains("alice"));
        }

        @Test
        void mongoFilter_specialCharsAreQuoted() {
            BsonDocument doc = bson(SearchQuery.like("name", "a.b*c"));
            String regex = doc.getRegularExpression("name").getPattern();
            // Pattern.quote wraps with \Q...\E — the raw chars must appear verbatim
            assertTrue(regex.contains("a.b*c"));
        }

        @Test
        void inMemory_caseInsensitiveSubstringMatch() {
            SearchQuery q = SearchQuery.like("name", "ali");
            assertTrue(q.test(Map.of("name", "Alice")));
            assertTrue(q.test(Map.of("name", "ALICE SMITH")));
            assertTrue(q.test(Map.of("name", "ali")));
        }

        @Test
        void inMemory_doesNotMatchWhenSubstringAbsent() {
            assertFalse(SearchQuery.like("name", "bob").test(Map.of("name", "Alice")));
        }

        @Test
        void inMemory_nullFieldDoesNotMatch() {
            assertFalse(SearchQuery.like("name", "x").test(Map.of("age", 5)));
        }
    }

    // ── Regex ─────────────────────────────────────────────────────────────────

    @Nested
    class RegexTests {

        @Test
        void mongoFilter_producesRegexOperator() {
            BsonDocument doc = bson(SearchQuery.regex("email", ".*@example\\.com$"));
            // MongoDB driver stores regex as BsonRegularExpression, not a sub-document
            assertEquals(".*@example\\.com$",
                    doc.getRegularExpression("email").getPattern());
        }

        @Test
        void inMemory_matchesPattern() {
            SearchQuery q = SearchQuery.regex("email", ".*@example\\.com$");
            assertTrue(q.test(Map.of("email", "alice@example.com")));
        }

        @Test
        void inMemory_doesNotMatchNonMatchingPattern() {
            SearchQuery q = SearchQuery.regex("email", ".*@example\\.com$");
            assertFalse(q.test(Map.of("email", "alice@other.org")));
        }

        @Test
        void inMemory_startsWithPattern() {
            SearchQuery q = SearchQuery.regex("code", "^[A-Z]{3}");
            assertTrue(q.test(Map.of("code", "ABC123")));
            assertFalse(q.test(Map.of("code", "123ABC")));
        }
    }

    // ── In ────────────────────────────────────────────────────────────────────

    @Nested
    class InTests {

        @Test
        void mongoFilter_producesInOperatorForStringVarargs() {
            BsonDocument doc = bson(SearchQuery.in("status", "active", "pending"));
            BsonArray arr = doc.getDocument("status").getArray("$in");
            assertEquals(2, arr.size());
            assertEquals("active",  arr.get(0).asString().getValue());
            assertEquals("pending", arr.get(1).asString().getValue());
        }

        @Test
        void mongoFilter_producesInOperatorForIntArray() {
            BsonDocument doc = bson(SearchQuery.in("score", new int[]{3, 5, 7}));
            BsonArray arr = doc.getDocument("score").getArray("$in");
            assertEquals(3, arr.size());
            assertEquals(3, arr.get(0).asInt32().getValue());
            assertEquals(5, arr.get(1).asInt32().getValue());
            assertEquals(7, arr.get(2).asInt32().getValue());
        }

        @Test
        void mongoFilter_producesInOperatorForLongArray() {
            BsonDocument doc = bson(SearchQuery.in("id", new long[]{100L, 200L}));
            BsonArray arr = doc.getDocument("id").getArray("$in");
            assertEquals(2, arr.size());
        }

        @Test
        void mongoFilter_producesInOperatorForDoubleArray() {
            BsonDocument doc = bson(SearchQuery.in("score", new double[]{1.5, 2.5}));
            BsonArray arr = doc.getDocument("score").getArray("$in");
            assertEquals(2, arr.size());
        }

        @Test
        void mongoFilter_producesInOperatorForListParam() {
            BsonDocument doc = bson(SearchQuery.in("role", List.of("admin", "mod")));
            BsonArray arr = doc.getDocument("role").getArray("$in");
            assertEquals(2, arr.size());
        }

        @Test
        void inMemory_scalarField_matchesWhenValueInList() {
            SearchQuery q = SearchQuery.in("status", "active", "pending");
            assertTrue(q.test(Map.of("status", "active")));
            assertTrue(q.test(Map.of("status", "pending")));
        }

        @Test
        void inMemory_scalarField_doesNotMatchWhenValueNotInList() {
            SearchQuery q = SearchQuery.in("status", "active", "pending");
            assertFalse(q.test(Map.of("status", "deleted")));
        }

        @Test
        void inMemory_listField_matchesWhenAnyElementInList() {
            // Simulates a tags field that is a List<String> (as Jackson would produce)
            SearchQuery q = SearchQuery.in("tags", "sale", "featured");
            assertTrue(q.test(Map.of("tags", List.of("new", "sale"))));
            assertTrue(q.test(Map.of("tags", List.of("featured"))));
        }

        @Test
        void inMemory_listField_doesNotMatchWhenNoElementInList() {
            SearchQuery q = SearchQuery.in("tags", "sale", "featured");
            assertFalse(q.test(Map.of("tags", List.of("clearance", "old"))));
        }

        @Test
        void inMemory_intArrayOverload_matchesCorrectly() {
            SearchQuery q = SearchQuery.in("score", new int[]{3, 5, 7});
            assertTrue(q.test(Map.of("score", 5)));
            assertFalse(q.test(Map.of("score", 4)));
        }
    }

    // ── Exists ────────────────────────────────────────────────────────────────

    @Nested
    class ExistsTests {

        @Test
        void mongoFilter_producesExistsTrueOperator() {
            BsonDocument doc = bson(SearchQuery.exists("deletedAt"));
            assertTrue(doc.getDocument("deletedAt").getBoolean("$exists").getValue());
        }

        @Test
        void mongoFilter_producesExistsFalseOperator() {
            BsonDocument doc = bson(SearchQuery.exists("deletedAt", false));
            assertFalse(doc.getDocument("deletedAt").getBoolean("$exists").getValue());
        }

        @Test
        void inMemory_existsTrue_matchesWhenFieldPresent() {
            SearchQuery q = SearchQuery.exists("deletedAt");
            assertTrue(q.test(Map.of("deletedAt", "2024-01-01")));
        }

        @Test
        void inMemory_existsTrue_doesNotMatchWhenFieldAbsent() {
            SearchQuery q = SearchQuery.exists("deletedAt");
            assertFalse(q.test(Map.of("name", "Alice")));
        }

        @Test
        void inMemory_existsFalse_matchesWhenFieldAbsent() {
            SearchQuery q = SearchQuery.exists("deletedAt", false);
            assertTrue(q.test(Map.of("name", "Alice")));
        }

        @Test
        void inMemory_existsFalse_doesNotMatchWhenFieldPresent() {
            SearchQuery q = SearchQuery.exists("deletedAt", false);
            assertFalse(q.test(Map.of("deletedAt", "2024-01-01")));
        }
    }

    // ── And ───────────────────────────────────────────────────────────────────

    @Nested
    class AndTests {

        @Test
        void mongoFilter_twoArg_producesAndOperator() {
            BsonDocument doc = bson(SearchQuery.and(
                    SearchQuery.eq("name", "Alice"),
                    SearchQuery.gt("age", 18)));
            BsonArray and = doc.getArray("$and");
            assertEquals(2, and.size());
        }

        @Test
        void mongoFilter_varargs_producesAndWithThreeTerms() {
            BsonDocument doc = bson(SearchQuery.and(
                    SearchQuery.eq("a", 1),
                    SearchQuery.eq("b", 2),
                    SearchQuery.eq("c", 3)));
            assertEquals(3, doc.getArray("$and").size());
        }

        @Test
        void inMemory_matchesWhenAllQueriesPass() {
            SearchQuery q = SearchQuery.and(
                    SearchQuery.eq("active", true),
                    SearchQuery.gt("age", 18));
            assertTrue(q.test(Map.of("active", true, "age", 25)));
        }

        @Test
        void inMemory_doesNotMatchWhenOneQueryFails() {
            SearchQuery q = SearchQuery.and(
                    SearchQuery.eq("active", true),
                    SearchQuery.gt("age", 18));
            assertFalse(q.test(Map.of("active", true, "age", 10)));
            assertFalse(q.test(Map.of("active", false, "age", 25)));
        }

        @Test
        void inMemory_nested_andInsideAnd() {
            SearchQuery q = SearchQuery.and(
                    SearchQuery.and(SearchQuery.gt("age", 18), SearchQuery.lt("age", 65)),
                    SearchQuery.eq("active", true));
            assertTrue(q.test(Map.of("age", 30, "active", true)));
            assertFalse(q.test(Map.of("age", 10, "active", true)));
            assertFalse(q.test(Map.of("age", 30, "active", false)));
        }
    }

    // ── Or ────────────────────────────────────────────────────────────────────

    @Nested
    class OrTests {

        @Test
        void mongoFilter_twoArg_producesOrOperator() {
            BsonDocument doc = bson(SearchQuery.or(
                    SearchQuery.eq("role", "admin"),
                    SearchQuery.eq("role", "mod")));
            BsonArray or = doc.getArray("$or");
            assertEquals(2, or.size());
        }

        @Test
        void mongoFilter_varargs_producesOrWithThreeTerms() {
            BsonDocument doc = bson(SearchQuery.or(
                    SearchQuery.eq("a", 1),
                    SearchQuery.eq("b", 2),
                    SearchQuery.eq("c", 3)));
            assertEquals(3, doc.getArray("$or").size());
        }

        @Test
        void inMemory_matchesWhenAnyQueryPasses() {
            SearchQuery q = SearchQuery.or(
                    SearchQuery.eq("role", "admin"),
                    SearchQuery.eq("role", "mod"));
            assertTrue(q.test(Map.of("role", "admin")));
            assertTrue(q.test(Map.of("role", "mod")));
        }

        @Test
        void inMemory_doesNotMatchWhenNoQueryPasses() {
            SearchQuery q = SearchQuery.or(
                    SearchQuery.eq("role", "admin"),
                    SearchQuery.eq("role", "mod"));
            assertFalse(q.test(Map.of("role", "viewer")));
        }
    }

    // ── Not ───────────────────────────────────────────────────────────────────

    @Nested
    class NotTests {

        @Test
        void mongoFilter_wrapsGtWithNotOperator() {
            BsonDocument doc = bson(SearchQuery.not(SearchQuery.gt("age", 65)));
            // {age: {$not: {$gt: 65}}}
            BsonDocument ageDoc = doc.getDocument("age");
            BsonDocument notDoc = ageDoc.getDocument("$not");
            assertEquals(65, notDoc.getInt32("$gt").getValue());
        }

        @Test
        void mongoFilter_wrapsRegexWithNotOperator() {
            BsonDocument doc = bson(SearchQuery.not(SearchQuery.regex("email", ".*@spam\\.com")));
            BsonDocument emailDoc = doc.getDocument("email");
            assertTrue(emailDoc.containsKey("$not"));
        }

        @Test
        void inMemory_negatesGt() {
            SearchQuery q = SearchQuery.not(SearchQuery.gt("age", 65));
            assertTrue(q.test(Map.of("age", 30)));
            assertFalse(q.test(Map.of("age", 70)));
        }

        @Test
        void inMemory_negatesLike() {
            SearchQuery q = SearchQuery.not(SearchQuery.like("name", "admin"));
            assertTrue(q.test(Map.of("name", "Alice")));
            assertFalse(q.test(Map.of("name", "super-admin")));
        }

        @Test
        void inMemory_negatesIn() {
            SearchQuery q = SearchQuery.not(SearchQuery.in("status", "banned", "deleted"));
            assertTrue(q.test(Map.of("status", "active")));
            assertFalse(q.test(Map.of("status", "banned")));
        }
    }

    // ── Nested / dot-notation ─────────────────────────────────────────────────

    @Nested
    class NestedFieldTests {

        private final Map<String, Object> doc = Map.of(
                "uuid", "abc",
                "name", "Alice",
                "address", Map.of("city", "London", "country", "UK")
        );

        @Test
        void inMemory_eq_dotNotation_matchesNestedField() {
            assertTrue(SearchQuery.eq("address.city", "London").test(doc));
        }

        @Test
        void inMemory_eq_dotNotation_doesNotMatchWrongValue() {
            assertFalse(SearchQuery.eq("address.city", "Paris").test(doc));
        }

        @Test
        void inMemory_like_dotNotation() {
            assertTrue(SearchQuery.like("address.country", "uk").test(doc));
        }

        @Test
        void inMemory_missingIntermediateKey_doesNotThrow() {
            assertFalse(SearchQuery.eq("address.zip", "SW1").test(doc));
        }

        @Test
        void inMemory_deeplyNested_twoLevels() {
            Map<String, Object> deep = Map.of(
                    "a", Map.of("b", Map.of("c", "found"))
            );
            assertTrue(SearchQuery.eq("a.b.c", "found").test(deep));
            assertFalse(SearchQuery.eq("a.b.c", "missing").test(deep));
        }
    }

    // ── Complex nested queries ────────────────────────────────────────────────

    @Nested
    class ComplexQueryTests {

        @Test
        void mongoFilter_orInsideAnd() {
            SearchQuery q = SearchQuery.and(
                    SearchQuery.eq("active", true),
                    SearchQuery.or(SearchQuery.gt("score", 8.0), SearchQuery.eq("vip", true)));
            BsonDocument doc = bson(q);
            BsonArray and = doc.getArray("$and");
            assertEquals(2, and.size());
            // Second element is the OR clause
            assertTrue(and.get(1).asDocument().containsKey("$or"));
        }

        @Test
        void mongoFilter_andInsideOr() {
            SearchQuery q = SearchQuery.or(
                    SearchQuery.and(SearchQuery.eq("type", "A"), SearchQuery.gt("val", 10)),
                    SearchQuery.eq("type", "B"));
            BsonDocument doc = bson(q);
            BsonArray or = doc.getArray("$or");
            assertEquals(2, or.size());
            assertTrue(or.get(0).asDocument().containsKey("$and"));
        }

        @Test
        void inMemory_orInsideAnd_matches() {
            SearchQuery q = SearchQuery.and(
                    SearchQuery.eq("active", true),
                    SearchQuery.or(SearchQuery.gt("score", 8.0), SearchQuery.eq("vip", true)));
            // active=true, score=9 → passes gt branch
            assertTrue(q.test(Map.of("active", true, "score", 9.0, "vip", false)));
            // active=true, vip=true → passes eq branch
            assertTrue(q.test(Map.of("active", true, "score", 5.0, "vip", true)));
            // active=false → fails AND
            assertFalse(q.test(Map.of("active", false, "score", 9.0, "vip", false)));
        }

        @Test
        void inMemory_notInsideOr() {
            SearchQuery q = SearchQuery.or(
                    SearchQuery.eq("role", "admin"),
                    SearchQuery.not(SearchQuery.gt("age", 18)));
            // admin → matches first branch
            assertTrue(q.test(Map.of("role", "admin", "age", 30)));
            // not admin, age <= 18 → matches second branch
            assertTrue(q.test(Map.of("role", "viewer", "age", 15)));
            // not admin, age > 18 → no match
            assertFalse(q.test(Map.of("role", "viewer", "age", 25)));
        }

        @Test
        void resolve_returnsNullForMissingKey() {
            assertNull(SearchQuery.resolve(Map.of("a", "b"), "missing"));
        }

        @Test
        void normalize_convertsIntegerToDouble() {
            assertEquals(30.0, SearchQuery.normalize(30));
        }

        @Test
        void normalize_convertsLongToDouble() {
            assertEquals(1000000.0, SearchQuery.normalize(1_000_000L));
        }

        @Test
        void normalize_leavesStringUnchanged() {
            assertEquals("hello", SearchQuery.normalize("hello"));
        }

        @Test
        void compareValues_integerVsLong_treatedAsEqualWhenSameNumericValue() {
            assertEquals(0, SearchQuery.compareValues(30, 30L));
        }
    }
}
