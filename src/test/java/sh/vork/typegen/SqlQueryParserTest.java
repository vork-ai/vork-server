package sh.vork.typegen;

import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.BsonRegularExpression;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import sh.vork.database.SearchQuery;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SqlQueryParser}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@link LikePatternTranslationTests} — direct unit tests of
 *       {@code likePatternToQuery()} for every pattern shape</li>
 *   <li>{@link LikeParseTests} — LIKE / NOT LIKE through the full
 *       {@code parse()} path, including in-memory evaluation</li>
 *   <li>{@link ComparisonTests} — {@code =}, {@code !=}, {@code <>},
 *       {@code >}, {@code >=}, {@code <}, {@code <=}</li>
 *   <li>{@link ValueParsingTests} — strings, numbers, booleans, null,
 *       escaped quotes</li>
 *   <li>{@link NullCheckTests} — {@code IS NULL}, {@code IS NOT NULL}</li>
 *   <li>{@link InTests} — {@code IN}, {@code NOT IN}</li>
 *   <li>{@link LogicalTests} — {@code AND}, {@code OR}, {@code NOT},
 *       operator precedence, grouping</li>
 *   <li>{@link DotNotationTests} — nested field paths</li>
 *   <li>{@link KeywordCaseTests} — keyword case-insensitivity</li>
 *   <li>{@link ErrorTests} — {@link SqlParseException} on malformed input</li>
 * </ul>
 *
 * <p>No Spring context, MongoDB server, or network connection required.
 */
class SqlQueryParserTest {

    // ── Helper: render a SearchQuery to a BsonDocument for assertions ─────────

    private static BsonDocument bson(SearchQuery q) {
        return q.toMongoFilter()
                .toBsonDocument(BsonDocument.class,
                        MongoClientSettings.getDefaultCodecRegistry());
    }

    // ── Helper: extract the BsonRegularExpression for a single-field filter ───

    private static BsonRegularExpression bsonRegex(SearchQuery q, String field) {
        return bson(q).getRegularExpression(field);
    }

    // =========================================================================
    // LIKE pattern translation — direct unit tests of likePatternToQuery()
    // =========================================================================

    /**
     * Tests every pattern shape handled by
     * {@link SqlQueryParser#likePatternToQuery}.
     *
     * <p>The two paths are:
     * <ol>
     *   <li><b>Optimised</b> — {@code %substring%} where the inner part contains
     *       no further wildcards → produces a {@link SearchQuery.Like} predicate
     *       (case-insensitive substring, backed by MongoDB {@code $regex} with
     *       the {@code i} option and a {@code \Q...\E}-quoted pattern).</li>
     *   <li><b>General regex</b> — all other shapes → produces a
     *       {@link SearchQuery.Regex} predicate whose pattern string starts with
     *       {@code (?i)} and uses anchors / {@code .*} / {@code .} as needed.</li>
     * </ol>
     */
    @Nested
    class LikePatternTranslationTests {

        // ── Optimised %x% path ────────────────────────────────────────────────

        @Test
        void percentSubstringPercent_returnsLikePredicate() {
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%ali%");
            assertInstanceOf(SearchQuery.Like.class, q);
        }

        @Test
        void percentSubstringPercent_substringSavedVerbatim() {
            SearchQuery.Like like = (SearchQuery.Like)
                    SqlQueryParser.likePatternToQuery("name", "%alice%");
            assertEquals("alice", like.substring());
        }

        @Test
        void percentSubstringPercent_uppercaseSubstring() {
            SearchQuery.Like like = (SearchQuery.Like)
                    SqlQueryParser.likePatternToQuery("name", "%HELLO%");
            assertEquals("HELLO", like.substring());
        }

        @Test
        void percentSubstringPercent_mixedCaseSubstring() {
            SearchQuery.Like like = (SearchQuery.Like)
                    SqlQueryParser.likePatternToQuery("name", "%FoO%");
            assertEquals("FoO", like.substring());
        }

        @Test
        void percentSubstringPercent_substringWithSpaces() {
            SearchQuery.Like like = (SearchQuery.Like)
                    SqlQueryParser.likePatternToQuery("bio", "%new york%");
            assertEquals("new york", like.substring());
        }

        @Test
        void percentSubstringPercent_substringWithSpecialRegexChars() {
            // Dot, plus, parens etc. in the inner part must NOT cause the regex
            // path to be selected — only % and _ in the inner disqualify it.
            SearchQuery.Like like = (SearchQuery.Like)
                    SqlQueryParser.likePatternToQuery("name", "%a.b+c%");
            assertEquals("a.b+c", like.substring());
        }

        @Test
        void singlePercent_emptyInnerSubstring_returnsLikePredicate() {
            // "%" → startsWith("%") && endsWith("%") && inner="" has no wildcards
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%");
            assertInstanceOf(SearchQuery.Like.class, q);
            assertEquals("", ((SearchQuery.Like) q).substring());
        }

        @Test
        void doublePercent_emptyInnerSubstring_returnsLikePredicate() {
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%%");
            assertInstanceOf(SearchQuery.Like.class, q);
            assertEquals("", ((SearchQuery.Like) q).substring());
        }

        @Test
        void likePredicate_mongoFilter_usesIOption() {
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%ali%");
            BsonRegularExpression re = bsonRegex(q, "name");
            assertEquals("i", re.getOptions());
        }

        @Test
        void likePredicate_mongoFilter_patternQuotesSubstring() {
            // SearchQuery.Like uses Pattern.quote(substring) as the regex pattern
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%ali%");
            BsonRegularExpression re = bsonRegex(q, "name");
            assertTrue(re.getPattern().contains("ali"),
                    "pattern should contain the substring; got: " + re.getPattern());
        }

        // ── General regex path ─────────────────────────────────────────────────

        @Test
        void innerContainsPercent_returnsRegexPredicate() {
            // %x%y% → inner="x%y" has a %, falls through to regex
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%x%y%");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void innerContainsUnderscore_returnsRegexPredicate() {
            // %x_y% → inner="x_y" has a _, falls through to regex
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%x_y%");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void trailingPercentOnly_returnsRegexPredicate() {
            // "x%" → startsWith("x") so optimised path is skipped
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "x%");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void leadingPercentOnly_returnsRegexPredicate() {
            // "%x" → endsWith("x"), not "%", so optimised path is skipped
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%x");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void noWildcards_returnsRegexPredicate() {
            // "hello" → neither starts nor ends with %
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "hello");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void underscoreOnly_returnsRegexPredicate() {
            // "a_b" → no leading/trailing %, falls through to regex
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "a_b");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        // Regex pattern content ────────────────────────────────────────────────

        @Test
        void regex_startsWithCaseInsensitiveFlag() {
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "x%");
            assertTrue(r.pattern().startsWith("(?i)"),
                    "pattern must start with (?i); got: " + r.pattern());
        }

        @Test
        void regex_trailingPercent_noEndAnchor() {
            // "x%" → anchor at start, no anchor at end
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "x%");
            String p = r.pattern();
            assertTrue(p.contains("^"), "should have start anchor; got: " + p);
            assertFalse(p.endsWith("$"), "should NOT have end anchor; got: " + p);
        }

        @Test
        void regex_leadingPercent_noStartAnchor() {
            // "%x" → no start anchor, anchor at end
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "%x");
            String p = r.pattern();
            assertFalse(p.contains("^"), "should NOT have start anchor; got: " + p);
            assertTrue(p.endsWith("$"), "should have end anchor; got: " + p);
        }

        @Test
        void regex_noWildcards_bothAnchors() {
            // "hello" → start and end anchors
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "hello");
            String p = r.pattern();
            assertTrue(p.contains("^"), "should have start anchor; got: " + p);
            assertTrue(p.endsWith("$"), "should have end anchor; got: " + p);
        }

        @Test
        void regex_percentTranslatedToStar() {
            // "a%b" → the % in the middle becomes .*
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "a%b");
            assertTrue(r.pattern().contains(".*"),
                    "% should translate to .*; got: " + r.pattern());
        }

        @Test
        void regex_underscoreTranslatedToDot() {
            // "a_b" → the _ becomes .
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "a_b");
            // The regex will have a bare . between the quoted a and b
            String p = r.pattern();
            // Verify the pattern actually contains a single dot (not inside \Q...\E)
            // We test indirectly: it should match "axb" but not "ab"
            assertTrue(java.util.regex.Pattern.compile(p).matcher("axb").find());
            assertFalse(java.util.regex.Pattern.compile(p).matcher("ab").find());
        }

        @Test
        void regex_specialCharsInLiteralPortion_areQuoted() {
            // "a.b" → the dot is a literal character and must be quoted in the regex
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "a.b");
            String p = r.pattern();
            // Should NOT match "axb" (dot is literal, not wildcard)
            assertFalse(java.util.regex.Pattern.compile(p).matcher("axb").find(),
                    "literal dot should not match any char; pattern: " + p);
            // Should match "a.b"
            assertTrue(java.util.regex.Pattern.compile(p).matcher("a.b").find(),
                    "literal dot should match a dot; pattern: " + p);
        }

        @Test
        void regex_plusInLiteralPortion_isQuoted() {
            // "a+b" → the + is literal
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "a+b");
            String p = r.pattern();
            assertTrue(java.util.regex.Pattern.compile(p).matcher("a+b").find());
            assertFalse(java.util.regex.Pattern.compile(p).matcher("aab").find());
        }

        @Test
        void regex_openParenInLiteralPortion_isQuoted() {
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("f", "a(b");
            String p = r.pattern();
            assertTrue(java.util.regex.Pattern.compile(p).matcher("a(b").find());
        }

        @Test
        void regex_multiplePercentsAndUnderscores() {
            // "%a_b%" → surrounds with .* and has a dot between a and b
            SearchQuery.Regex r = (SearchQuery.Regex)
                    SqlQueryParser.likePatternToQuery("name", "%a_b%");
            String p = r.pattern();
            // "xaXbz" should match (a, any char, b, surrounded by anything)
            assertTrue(java.util.regex.Pattern.compile(p).matcher("xaXbz").find());
            // "xabz" should NOT match (need exactly one char between a and b)
            assertFalse(java.util.regex.Pattern.compile(p).matcher("xabz").find());
        }

        // ── In-memory evaluation ──────────────────────────────────────────────

        @Test
        void likeInMemory_matchesCaseInsensitively() {
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%ali%");
            assertTrue(q.test(Map.of("name", "Alice")));
            assertTrue(q.test(Map.of("name", "ALI")));
            assertTrue(q.test(Map.of("name", "salience")));
        }

        @Test
        void likeInMemory_doesNotMatchMissingSubstring() {
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%ali%");
            assertFalse(q.test(Map.of("name", "Bob")));
        }

        @Test
        void likeInMemory_missingField_doesNotMatch() {
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%ali%");
            assertFalse(q.test(Map.of("other", "Alice")));
        }

        @Test
        void regexInMemory_prefixPattern_matches() {
            // "ali%" → matches anything starting with "ali" (case-insensitive)
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "ali%");
            assertTrue(q.test(Map.of("name", "Alice")));
            assertTrue(q.test(Map.of("name", "ALI_something")));
            assertFalse(q.test(Map.of("name", "salience")));  // doesn't start with ali
        }

        @Test
        void regexInMemory_suffixPattern_matches() {
            // "%ice" → matches anything ending with "ice" (case-insensitive)
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "%ice");
            assertTrue(q.test(Map.of("name", "Alice")));
            assertTrue(q.test(Map.of("name", "ICE")));
            assertFalse(q.test(Map.of("name", "Icy")));
        }

        @Test
        void regexInMemory_exactPattern_matches() {
            // "alice" → exact match (case-insensitive due to (?i))
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "alice");
            assertTrue(q.test(Map.of("name", "alice")));
            assertTrue(q.test(Map.of("name", "ALICE")));
            assertFalse(q.test(Map.of("name", "Alice Smith")));
        }

        @Test
        void regexInMemory_underscoreWildcard_matchesSingleChar() {
            // "ali_e" → matches "alice", "alixe" but not "alie" or "alixxe"
            SearchQuery q = SqlQueryParser.likePatternToQuery("name", "ali_e");
            assertTrue(q.test(Map.of("name", "alice")));
            assertTrue(q.test(Map.of("name", "ALIXE")));
            assertFalse(q.test(Map.of("name", "alie")));      // no char between i and e
            assertFalse(q.test(Map.of("name", "alixxe")));    // two chars
        }

        @Test
        void regexInMemory_multipleWildcards_matches() {
            // "%a%b%" → anything containing 'a' followed anywhere by 'b'
            SearchQuery q = SqlQueryParser.likePatternToQuery("s", "%a%b%");
            assertTrue(q.test(Map.of("s", "xaybz")));
            assertTrue(q.test(Map.of("s", "ab")));
            assertFalse(q.test(Map.of("s", "ba")));   // b before a
        }
    }

    // =========================================================================
    // LIKE / NOT LIKE through the full parse() path
    // =========================================================================

    @Nested
    class LikeParseTests {

        @Test
        void like_percentSubstringPercent_producesLikePredicate() {
            SearchQuery q = SqlQueryParser.parse("name LIKE '%ali%'");
            assertInstanceOf(SearchQuery.Like.class, q);
        }

        @Test
        void like_trailingPercent_producesRegexPredicate() {
            SearchQuery q = SqlQueryParser.parse("name LIKE 'ali%'");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void like_leadingPercent_producesRegexPredicate() {
            SearchQuery q = SqlQueryParser.parse("name LIKE '%ice'");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void like_noWildcards_producesRegexPredicate() {
            SearchQuery q = SqlQueryParser.parse("name LIKE 'alice'");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void like_underscoreWildcard_producesRegexPredicate() {
            SearchQuery q = SqlQueryParser.parse("name LIKE 'ali_e'");
            assertInstanceOf(SearchQuery.Regex.class, q);
        }

        @Test
        void notLike_wrapsInNotPredicate() {
            SearchQuery q = SqlQueryParser.parse("name NOT LIKE '%ali%'");
            assertInstanceOf(SearchQuery.Not.class, q);
        }

        @Test
        void notLike_innerIsLike() {
            SearchQuery.Not not = (SearchQuery.Not) SqlQueryParser.parse("name NOT LIKE '%ali%'");
            assertInstanceOf(SearchQuery.Like.class, not.query());
        }

        @Test
        void like_keywordCaseInsensitive_lower() {
            SearchQuery q = SqlQueryParser.parse("name like '%ali%'");
            assertInstanceOf(SearchQuery.Like.class, q);
        }

        @Test
        void like_keywordCaseInsensitive_mixed() {
            SearchQuery q = SqlQueryParser.parse("name Like '%ali%'");
            assertInstanceOf(SearchQuery.Like.class, q);
        }

        @Test
        void like_combinedWithAnd() {
            SearchQuery q = SqlQueryParser.parse("name LIKE '%ali%' AND age > 18");
            assertInstanceOf(SearchQuery.And.class, q);
        }

        @Test
        void like_inMemory_substringSurroundedByPercents_caseInsensitive() {
            SearchQuery q = SqlQueryParser.parse("name LIKE '%ali%'");
            assertTrue(q.test(Map.of("name", "Alice", "age", 30)));
            assertTrue(q.test(Map.of("name", "CALIBRATE")));
            assertFalse(q.test(Map.of("name", "Bob")));
        }

        @Test
        void like_inMemory_prefixWildcard() {
            SearchQuery q = SqlQueryParser.parse("name LIKE '%son'");
            assertTrue(q.test(Map.of("name", "Johnson")));
            assertTrue(q.test(Map.of("name", "SON")));
            assertFalse(q.test(Map.of("name", "Sonny")));
        }

        @Test
        void like_inMemory_suffixWildcard() {
            SearchQuery q = SqlQueryParser.parse("name LIKE 'John%'");
            assertTrue(q.test(Map.of("name", "Johnson")));
            assertTrue(q.test(Map.of("name", "JOHN")));
            assertFalse(q.test(Map.of("name", "Doe")));
        }

        @Test
        void like_inMemory_underscoreMatchesSingleChar() {
            SearchQuery q = SqlQueryParser.parse("code LIKE 'A_1'");
            assertTrue(q.test(Map.of("code", "AB1")));
            assertTrue(q.test(Map.of("code", "AZ1")));
            assertFalse(q.test(Map.of("code", "A1")));    // underscore needs 1 char
            assertFalse(q.test(Map.of("code", "ABB1")));  // two chars instead of one
        }

        @Test
        void notLike_inMemory_excludesMatches() {
            SearchQuery q = SqlQueryParser.parse("name NOT LIKE '%ali%'");
            assertFalse(q.test(Map.of("name", "Alice")));
            assertTrue(q.test(Map.of("name", "Bob")));
        }

        @Test
        void like_inMemory_escapedSingleQuote_inPattern() {
            // The SQL pattern 'it''s' is parsed as the string literal "it's"
            SearchQuery q = SqlQueryParser.parse("note LIKE '%it''s%'");
            assertTrue(q.test(Map.of("note", "it's a test")));
            assertFalse(q.test(Map.of("note", "its a test")));
        }

        @Test
        void like_inMemory_patternWithSpecialRegexChars_treatedAsLiterals() {
            // The dot in the pattern is a literal character
            SearchQuery q = SqlQueryParser.parse("email LIKE '%.com'");
            assertTrue(q.test(Map.of("email", "user@example.com")));
            // 'xcom' should NOT match because the dot must be a literal dot
            assertFalse(q.test(Map.of("email", "userxcom")));
        }

        @Test
        void like_inMemory_multipleUnderscores() {
            // "__ " — two underscores, must match exactly two characters
            SearchQuery q = SqlQueryParser.parse("code LIKE '__'");
            assertTrue(q.test(Map.of("code", "AB")));
            assertFalse(q.test(Map.of("code", "A")));
            assertFalse(q.test(Map.of("code", "ABC")));
        }
    }

    // =========================================================================
    // Comparison operators
    // =========================================================================

    @Nested
    class ComparisonTests {

        @Test
        void equals_string() {
            SearchQuery q = SqlQueryParser.parse("name = 'Alice'");
            assertInstanceOf(SearchQuery.Eq.class, q);
            assertTrue(q.test(Map.of("name", "Alice")));
            assertFalse(q.test(Map.of("name", "Bob")));
        }

        @Test
        void equals_integer() {
            SearchQuery q = SqlQueryParser.parse("age = 30");
            assertTrue(q.test(Map.of("age", 30)));
            assertFalse(q.test(Map.of("age", 25)));
        }

        @Test
        void notEquals_bangEquals() {
            SearchQuery q = SqlQueryParser.parse("status != 'deleted'");
            assertInstanceOf(SearchQuery.Ne.class, q);
            assertTrue(q.test(Map.of("status", "active")));
            assertFalse(q.test(Map.of("status", "deleted")));
        }

        @Test
        void notEquals_diamond() {
            // <> is an alias for !=
            SearchQuery q = SqlQueryParser.parse("status <> 'deleted'");
            assertInstanceOf(SearchQuery.Ne.class, q);
            assertTrue(q.test(Map.of("status", "active")));
        }

        @Test
        void greaterThan() {
            SearchQuery q = SqlQueryParser.parse("age > 18");
            assertInstanceOf(SearchQuery.Gt.class, q);
            assertTrue(q.test(Map.of("age", 19)));
            assertFalse(q.test(Map.of("age", 18)));
        }

        @Test
        void greaterThanOrEqual() {
            SearchQuery q = SqlQueryParser.parse("age >= 18");
            assertInstanceOf(SearchQuery.Gte.class, q);
            assertTrue(q.test(Map.of("age", 18)));
            assertFalse(q.test(Map.of("age", 17)));
        }

        @Test
        void lessThan() {
            SearchQuery q = SqlQueryParser.parse("age < 65");
            assertInstanceOf(SearchQuery.Lt.class, q);
            assertTrue(q.test(Map.of("age", 64)));
            assertFalse(q.test(Map.of("age", 65)));
        }

        @Test
        void lessThanOrEqual() {
            SearchQuery q = SqlQueryParser.parse("age <= 65");
            assertInstanceOf(SearchQuery.Lte.class, q);
            assertTrue(q.test(Map.of("age", 65)));
            assertFalse(q.test(Map.of("age", 66)));
        }

        @Test
        void equals_double() {
            SearchQuery q = SqlQueryParser.parse("score = 9.5");
            assertTrue(q.test(Map.of("score", 9.5)));
            assertFalse(q.test(Map.of("score", 9.0)));
        }

        @Test
        void greaterThan_double() {
            SearchQuery q = SqlQueryParser.parse("score > 8.0");
            assertTrue(q.test(Map.of("score", 8.1)));
            assertFalse(q.test(Map.of("score", 8.0)));
        }

        @Test
        void equals_boolean_true() {
            SearchQuery q = SqlQueryParser.parse("active = true");
            assertTrue(q.test(Map.of("active", true)));
            assertFalse(q.test(Map.of("active", false)));
        }

        @Test
        void equals_boolean_false() {
            SearchQuery q = SqlQueryParser.parse("active = false");
            assertTrue(q.test(Map.of("active", false)));
            assertFalse(q.test(Map.of("active", true)));
        }
    }

    // =========================================================================
    // Value parsing
    // =========================================================================

    @Nested
    class ValueParsingTests {

        @Test
        void string_parsedCorrectly() {
            SearchQuery q = SqlQueryParser.parse("name = 'hello world'");
            assertTrue(q.test(Map.of("name", "hello world")));
        }

        @Test
        void string_escapedSingleQuote() {
            // '' inside a string literal → literal '
            SearchQuery q = SqlQueryParser.parse("note = 'it''s fine'");
            assertTrue(q.test(Map.of("note", "it's fine")));
        }

        @Test
        void string_emptyString() {
            SearchQuery q = SqlQueryParser.parse("name = ''");
            assertTrue(q.test(Map.of("name", "")));
            assertFalse(q.test(Map.of("name", "x")));
        }

        @Test
        void integer_positive() {
            SearchQuery q = SqlQueryParser.parse("count = 42");
            assertTrue(q.test(Map.of("count", 42)));
        }

        @Test
        void integer_negative() {
            SearchQuery q = SqlQueryParser.parse("delta = -5");
            assertTrue(q.test(Map.of("delta", -5)));
        }

        @Test
        void double_value() {
            SearchQuery q = SqlQueryParser.parse("price = 3.14");
            assertTrue(q.test(Map.of("price", 3.14)));
        }

        @Test
        void double_negative() {
            SearchQuery q = SqlQueryParser.parse("temp = -1.5");
            assertTrue(q.test(Map.of("temp", -1.5)));
        }

        @Test
        void boolean_true_lowercase() {
            SearchQuery q = SqlQueryParser.parse("flag = true");
            assertTrue(q.test(Map.of("flag", true)));
        }

        @Test
        void boolean_true_uppercase() {
            SearchQuery q = SqlQueryParser.parse("flag = TRUE");
            assertTrue(q.test(Map.of("flag", true)));
        }

        @Test
        void boolean_false_lowercase() {
            SearchQuery q = SqlQueryParser.parse("flag = false");
            assertTrue(q.test(Map.of("flag", false)));
        }

        @Test
        void boolean_false_uppercase() {
            SearchQuery q = SqlQueryParser.parse("flag = FALSE");
            assertTrue(q.test(Map.of("flag", false)));
        }

        @Test
        void null_value_lowercase() {
            SearchQuery q = SqlQueryParser.parse("name = null");
            // null == null should be true
            Map<String, Object> doc = new java.util.HashMap<>();
            doc.put("name", null);
            assertTrue(q.test(doc));
        }
    }

    // =========================================================================
    // IS NULL / IS NOT NULL
    // =========================================================================

    @Nested
    class NullCheckTests {

        @Test
        void isNull_producesExistsFalse() {
            SearchQuery q = SqlQueryParser.parse("deletedAt IS NULL");
            assertInstanceOf(SearchQuery.Exists.class, q);
            SearchQuery.Exists e = (SearchQuery.Exists) q;
            assertFalse(e.exists());
        }

        @Test
        void isNotNull_producesExistsTrue() {
            SearchQuery q = SqlQueryParser.parse("createdAt IS NOT NULL");
            assertInstanceOf(SearchQuery.Exists.class, q);
            SearchQuery.Exists e = (SearchQuery.Exists) q;
            assertTrue(e.exists());
        }

        @Test
        void isNull_fieldCorrect() {
            SearchQuery.Exists e = (SearchQuery.Exists) SqlQueryParser.parse("myField IS NULL");
            assertEquals("myField", e.field());
        }

        @Test
        void isNull_caseInsensitiveKeyword() {
            SearchQuery q = SqlQueryParser.parse("deletedAt is null");
            assertInstanceOf(SearchQuery.Exists.class, q);
        }

        @Test
        void isNotNull_caseInsensitiveKeyword() {
            SearchQuery q = SqlQueryParser.parse("deletedAt IS NOT NULL");
            assertInstanceOf(SearchQuery.Exists.class, q);
        }

        @Test
        void isNull_inMemory_matchesAbsentField() {
            SearchQuery q = SqlQueryParser.parse("deletedAt IS NULL");
            assertTrue(q.test(Map.of("name", "Alice")));        // field absent → matches
            assertFalse(q.test(Map.of("deletedAt", "2024-01-01"))); // present → no match
        }

        @Test
        void isNotNull_inMemory_matchesPresentField() {
            SearchQuery q = SqlQueryParser.parse("createdAt IS NOT NULL");
            assertTrue(q.test(Map.of("createdAt", "2024-01-01"))); // present → matches
            assertFalse(q.test(Map.of("name", "Alice")));           // absent → no match
        }
    }

    // =========================================================================
    // IN / NOT IN
    // =========================================================================

    @Nested
    class InTests {

        @Test
        void in_strings_producesInPredicate() {
            SearchQuery q = SqlQueryParser.parse("status IN ('active', 'pending')");
            assertInstanceOf(SearchQuery.In.class, q);
        }

        @Test
        void in_inMemory_matchesMemberValue() {
            SearchQuery q = SqlQueryParser.parse("status IN ('active', 'pending')");
            assertTrue(q.test(Map.of("status", "active")));
            assertTrue(q.test(Map.of("status", "pending")));
            assertFalse(q.test(Map.of("status", "deleted")));
        }

        @Test
        void in_numbers_inMemory() {
            SearchQuery q = SqlQueryParser.parse("score IN (1, 2, 3)");
            assertTrue(q.test(Map.of("score", 2)));
            assertFalse(q.test(Map.of("score", 5)));
        }

        @Test
        void in_singleValue() {
            SearchQuery q = SqlQueryParser.parse("role IN ('admin')");
            assertInstanceOf(SearchQuery.In.class, q);
            assertTrue(q.test(Map.of("role", "admin")));
            assertFalse(q.test(Map.of("role", "user")));
        }

        @Test
        void notIn_producesNotWrappingIn() {
            SearchQuery q = SqlQueryParser.parse("status NOT IN ('banned', 'deleted')");
            assertInstanceOf(SearchQuery.Not.class, q);
            assertInstanceOf(SearchQuery.In.class, ((SearchQuery.Not) q).query());
        }

        @Test
        void notIn_inMemory_excludesMemberValues() {
            SearchQuery q = SqlQueryParser.parse("status NOT IN ('banned', 'deleted')");
            assertTrue(q.test(Map.of("status", "active")));
            assertFalse(q.test(Map.of("status", "banned")));
        }

        @Test
        void in_mixedStringAndNumber_inMemory() {
            // Single-element list with a quoted string
            SearchQuery q = SqlQueryParser.parse("tag IN ('sale')");
            assertTrue(q.test(Map.of("tag", "sale")));
        }
    }

    // =========================================================================
    // Logical operators — AND, OR, NOT
    // =========================================================================

    @Nested
    class LogicalTests {

        @Test
        void and_twoConditions_producesAndPredicate() {
            SearchQuery q = SqlQueryParser.parse("name = 'Alice' AND age > 18");
            assertInstanceOf(SearchQuery.And.class, q);
        }

        @Test
        void and_inMemory_bothMustMatch() {
            SearchQuery q = SqlQueryParser.parse("name = 'Alice' AND age > 18");
            assertTrue(q.test(Map.of("name", "Alice", "age", 30)));
            assertFalse(q.test(Map.of("name", "Alice", "age", 10)));
            assertFalse(q.test(Map.of("name", "Bob",   "age", 30)));
        }

        @Test
        void or_twoConditions_producesOrPredicate() {
            SearchQuery q = SqlQueryParser.parse("role = 'admin' OR role = 'mod'");
            assertInstanceOf(SearchQuery.Or.class, q);
        }

        @Test
        void or_inMemory_eitherMustMatch() {
            SearchQuery q = SqlQueryParser.parse("role = 'admin' OR role = 'mod'");
            assertTrue(q.test(Map.of("role", "admin")));
            assertTrue(q.test(Map.of("role", "mod")));
            assertFalse(q.test(Map.of("role", "user")));
        }

        @Test
        void not_invertsPredicate() {
            SearchQuery q = SqlQueryParser.parse("NOT active = false");
            assertTrue(q.test(Map.of("active", true)));
            assertFalse(q.test(Map.of("active", false)));
        }

        @Test
        void and_bindsMoreTightlyThanOr() {
            // a OR b AND c  ≡  a OR (b AND c)
            SearchQuery q = SqlQueryParser.parse(
                    "name = 'Alice' OR name = 'Bob' AND age > 18");
            // "Alice", age=5 → left side of OR is true → overall true
            assertTrue(q.test(Map.of("name", "Alice", "age", 5)));
            // "Bob", age=20 → right AND is true → overall true
            assertTrue(q.test(Map.of("name", "Bob", "age", 20)));
            // "Bob", age=5 → right AND is false, left OR is false → overall false
            assertFalse(q.test(Map.of("name", "Bob", "age", 5)));
        }

        @Test
        void parens_overridePrecedence() {
            // (a OR b) AND c — parens make OR bind first
            SearchQuery q = SqlQueryParser.parse(
                    "(name = 'Alice' OR name = 'Bob') AND age > 18");
            // "Alice", age=20 → true
            assertTrue(q.test(Map.of("name", "Alice", "age", 20)));
            // "Bob", age=20 → true
            assertTrue(q.test(Map.of("name", "Bob", "age", 20)));
            // "Alice", age=5 → OR is true but AND fails
            assertFalse(q.test(Map.of("name", "Alice", "age", 5)));
        }

        @Test
        void chainedAnds() {
            SearchQuery q = SqlQueryParser.parse(
                    "active = true AND age > 18 AND score >= 5.0");
            assertTrue(q.test(Map.of("active", true, "age", 25, "score", 8.0)));
            assertFalse(q.test(Map.of("active", true, "age", 25, "score", 3.0)));
        }

        @Test
        void chainedOrs() {
            SearchQuery q = SqlQueryParser.parse(
                    "status = 'a' OR status = 'b' OR status = 'c'");
            assertTrue(q.test(Map.of("status", "a")));
            assertTrue(q.test(Map.of("status", "c")));
            assertFalse(q.test(Map.of("status", "d")));
        }

        @Test
        void nestedParens() {
            SearchQuery q = SqlQueryParser.parse(
                    "((name = 'Alice') AND (age > 18))");
            assertTrue(q.test(Map.of("name", "Alice", "age", 25)));
            assertFalse(q.test(Map.of("name", "Alice", "age", 10)));
        }

        @Test
        void not_keywordCaseInsensitive() {
            SearchQuery q = SqlQueryParser.parse("not active = false");
            assertTrue(q.test(Map.of("active", true)));
        }

        @Test
        void and_keywordCaseInsensitive() {
            SearchQuery q = SqlQueryParser.parse("name = 'Alice' and age > 18");
            assertTrue(q.test(Map.of("name", "Alice", "age", 30)));
        }

        @Test
        void or_keywordCaseInsensitive() {
            SearchQuery q = SqlQueryParser.parse("role = 'admin' or role = 'mod'");
            assertTrue(q.test(Map.of("role", "mod")));
        }
    }

    // =========================================================================
    // Dot notation — nested field paths
    // =========================================================================

    @Nested
    class DotNotationTests {

        @Test
        void singleLevelNesting_fieldParsed() {
            SearchQuery q = SqlQueryParser.parse("address.city = 'London'");
            assertInstanceOf(SearchQuery.Eq.class, q);
            assertEquals("address.city", ((SearchQuery.Eq) q).field());
        }

        @Test
        void singleLevelNesting_inMemory() {
            SearchQuery q = SqlQueryParser.parse("address.city = 'London'");
            assertTrue(q.test(Map.of("address", Map.of("city", "London"))));
            assertFalse(q.test(Map.of("address", Map.of("city", "Paris"))));
        }

        @Test
        void twoLevelNesting_inMemory() {
            SearchQuery q = SqlQueryParser.parse("a.b.c = 'deep'");
            assertTrue(q.test(Map.of("a", Map.of("b", Map.of("c", "deep")))));
            assertFalse(q.test(Map.of("a", Map.of("b", Map.of("c", "shallow")))));
        }

        @Test
        void nestedField_withLike() {
            SearchQuery q = SqlQueryParser.parse("address.city LIKE '%on%'");
            assertTrue(q.test(Map.of("address", Map.of("city", "London"))));
            assertFalse(q.test(Map.of("address", Map.of("city", "Paris"))));
        }

        @Test
        void nestedField_withGreaterThan() {
            SearchQuery q = SqlQueryParser.parse("stats.score > 80");
            assertTrue(q.test(Map.of("stats", Map.of("score", 90))));
            assertFalse(q.test(Map.of("stats", Map.of("score", 70))));
        }
    }

    // =========================================================================
    // Keyword case-insensitivity
    // =========================================================================

    @Nested
    class KeywordCaseTests {

        @Test void and_uppercase()    { assertDoesNotThrow(() -> SqlQueryParser.parse("a = 1 AND b = 2")); }
        @Test void and_lowercase()    { assertDoesNotThrow(() -> SqlQueryParser.parse("a = 1 and b = 2")); }
        @Test void or_uppercase()     { assertDoesNotThrow(() -> SqlQueryParser.parse("a = 1 OR b = 2")); }
        @Test void or_lowercase()     { assertDoesNotThrow(() -> SqlQueryParser.parse("a = 1 or b = 2")); }
        @Test void not_uppercase()    { assertDoesNotThrow(() -> SqlQueryParser.parse("NOT a = 1")); }
        @Test void not_lowercase()    { assertDoesNotThrow(() -> SqlQueryParser.parse("not a = 1")); }
        @Test void in_uppercase()     { assertDoesNotThrow(() -> SqlQueryParser.parse("a IN ('x')")); }
        @Test void in_lowercase()     { assertDoesNotThrow(() -> SqlQueryParser.parse("a in ('x')")); }
        @Test void like_uppercase()   { assertDoesNotThrow(() -> SqlQueryParser.parse("a LIKE '%x%'")); }
        @Test void like_lowercase()   { assertDoesNotThrow(() -> SqlQueryParser.parse("a like '%x%'")); }
        @Test void is_uppercase()     { assertDoesNotThrow(() -> SqlQueryParser.parse("a IS NULL")); }
        @Test void is_lowercase()     { assertDoesNotThrow(() -> SqlQueryParser.parse("a is null")); }
        @Test void null_uppercase()   { assertDoesNotThrow(() -> SqlQueryParser.parse("a IS NULL")); }
        @Test void null_lowercase()   { assertDoesNotThrow(() -> SqlQueryParser.parse("a is null")); }
        @Test void true_uppercase()   { assertDoesNotThrow(() -> SqlQueryParser.parse("flag = TRUE")); }
        @Test void true_lowercase()   { assertDoesNotThrow(() -> SqlQueryParser.parse("flag = true")); }
        @Test void false_uppercase()  { assertDoesNotThrow(() -> SqlQueryParser.parse("flag = FALSE")); }
        @Test void false_lowercase()  { assertDoesNotThrow(() -> SqlQueryParser.parse("flag = false")); }
    }

    // =========================================================================
    // Error cases — SqlParseException
    // =========================================================================

    @Nested
    class ErrorTests {

        @Test
        void emptyInput_throws() {
            assertThrows(SqlParseException.class, () -> SqlQueryParser.parse(""));
        }

        @Test
        void unexpectedCharacter_throws() {
            assertThrows(SqlParseException.class, () -> SqlQueryParser.parse("name @ 'Alice'"));
        }

        @Test
        void missingClosingParen_throws() {
            assertThrows(SqlParseException.class, () -> SqlQueryParser.parse("(name = 'Alice'"));
        }

        @Test
        void extraTokensAfterExpression_throws() {
            assertThrows(SqlParseException.class,
                    () -> SqlQueryParser.parse("name = 'Alice' garbage"));
        }

        @Test
        void likeWithNonStringLiteral_throws() {
            assertThrows(SqlParseException.class,
                    () -> SqlQueryParser.parse("name LIKE 123"));
        }

        @Test
        void isWithoutNull_throws() {
            assertThrows(SqlParseException.class,
                    () -> SqlQueryParser.parse("name IS active"));
        }

        @Test
        void notFollowedByUnknownKeyword_throws() {
            assertThrows(SqlParseException.class,
                    () -> SqlQueryParser.parse("name NOT BETWEEN 1 AND 5"));
        }

        @Test
        void missingValueAfterEquals_throws() {
            assertThrows(SqlParseException.class,
                    () -> SqlQueryParser.parse("name ="));
        }

        @Test
        void missingClosingParenInInList_throws() {
            assertThrows(SqlParseException.class,
                    () -> SqlQueryParser.parse("status IN ('a', 'b'"));
        }

        @Test
        void bangWithoutEquals_throws() {
            assertThrows(SqlParseException.class,
                    () -> SqlQueryParser.parse("name ! 'Alice'"));
        }

        @Test
        void missingFieldName_throws() {
            // Starts with an operator, no field
            assertThrows(SqlParseException.class,
                    () -> SqlQueryParser.parse("= 'Alice'"));
        }
    }
}
