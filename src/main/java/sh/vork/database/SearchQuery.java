package sh.vork.database;

import com.mongodb.client.model.Filters;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Composable query predicate for {@link DatabaseRepository#search} and
 * {@link DatabaseRepository#searchCount}.
 *
 * <h3>Creating predicates</h3>
 * Use the static factory methods:
 * <pre>{@code
 * SearchQuery.eq("status", "active")
 * SearchQuery.gt("age", 18)
 * SearchQuery.like("name", "ali")              // case-insensitive substring
 * SearchQuery.in("tag", "sale", "featured")
 * SearchQuery.in("score", new int[]{3, 5, 7})  // primitive-array overload
 * SearchQuery.exists("deletedAt", false)        // field must be absent
 * }</pre>
 *
 * <h3>Combining predicates</h3>
 * Multiple queries passed to {@code search()} are combined with AND.
 * Explicit logical operators are also available:
 * <pre>{@code
 * SearchQuery.and(SearchQuery.gt("age", 18), SearchQuery.eq("active", true))
 * SearchQuery.or(SearchQuery.eq("role", "admin"), SearchQuery.eq("role", "mod"))
 * SearchQuery.not(SearchQuery.gt("score", 100))
 * }</pre>
 *
 * <h3>Nested fields</h3>
 * Use dot notation to address nested record fields:
 * <pre>{@code
 * SearchQuery.eq("address.city", "London")
 * }</pre>
 *
 * <h3>MongoDB</h3>
 * Each predicate exposes {@link #toMongoFilter()} which returns the equivalent
 * MongoDB {@link Bson} filter for use by {@link sh.vork.database.mongo.MongoDBRepository}.
 * Note: {@link Not} should wrap an operator predicate (gt, gte, lt, lte, regex, in,
 * etc.) — wrapping a bare {@link Eq} is not valid MongoDB and should use {@link Ne}
 * instead.
 *
 * <h3>In-memory evaluation</h3>
 * Each predicate also implements {@link #test(Map)} which evaluates the predicate
 * against a deserialized entity document, used by the in-memory mock repository.
 */
public sealed interface SearchQuery
        permits SearchQuery.Eq, SearchQuery.Ne, SearchQuery.Gt, SearchQuery.Gte,
                SearchQuery.Lt, SearchQuery.Lte, SearchQuery.Like, SearchQuery.Regex,
                SearchQuery.In, SearchQuery.Exists, SearchQuery.And, SearchQuery.Or,
                SearchQuery.Not {

    /** Returns the equivalent MongoDB {@link Bson} filter for this predicate. */
    Bson toMongoFilter();

    /**
     * Evaluates this predicate against a deserialized entity document.
     * Used by the in-memory {@link sh.vork.database.mock.MapDatabaseRepository}.
     *
     * @param document the entity as a {@code Map<String, Object>} from Jackson
     * @return {@code true} if the document matches this predicate
     */
    boolean test(Map<String, Object> document);

    // ── Factory methods ───────────────────────────────────────────────────────

    /** Field equals {@code value}. */
    static SearchQuery eq(String field, Object value)  { return new Eq(field, value); }

    /** Field does not equal {@code value}. */
    static SearchQuery ne(String field, Object value)  { return new Ne(field, value); }

    /** Field is strictly greater than {@code value}. */
    static SearchQuery gt(String field, Object value)  { return new Gt(field, value); }

    /** Field is greater than or equal to {@code value}. */
    static SearchQuery gte(String field, Object value) { return new Gte(field, value); }

    /** Field is strictly less than {@code value}. */
    static SearchQuery lt(String field, Object value)  { return new Lt(field, value); }

    /** Field is less than or equal to {@code value}. */
    static SearchQuery lte(String field, Object value) { return new Lte(field, value); }

    /**
     * Case-insensitive substring match on a string field.
     * Equivalent to SQL {@code LIKE '%substring%'}.
     * For MongoDB: {@code {$regex: "\\Qsubstring\\E", $options: "i"}}.
     */
    static SearchQuery like(String field, String substring) { return new Like(field, substring); }

    /**
     * Full regular-expression match on a string field (case-sensitive by default).
     * The {@code pattern} is used verbatim as a MongoDB {@code $regex} pattern.
     */
    static SearchQuery regex(String field, String pattern) { return new Regex(field, pattern); }

    /** Field value is one of the provided {@code values} (varargs). */
    static SearchQuery in(String field, Object... values) { return new In(field, List.of(values)); }

    /** Field value is one of the provided {@code values} collection. */
    static SearchQuery in(String field, List<?> values)   { return new In(field, List.copyOf(values)); }

    /** Field value is one of the provided primitive {@code int} values. */
    static SearchQuery in(String field, int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int v : values) list.add(v);
        return new In(field, Collections.unmodifiableList(list));
    }

    /** Field value is one of the provided primitive {@code long} values. */
    static SearchQuery in(String field, long[] values) {
        List<Long> list = new ArrayList<>(values.length);
        for (long v : values) list.add(v);
        return new In(field, Collections.unmodifiableList(list));
    }

    /** Field value is one of the provided primitive {@code double} values. */
    static SearchQuery in(String field, double[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (double v : values) list.add(v);
        return new In(field, Collections.unmodifiableList(list));
    }

    /** Field exists in the document (i.e. is present, even if null). */
    static SearchQuery exists(String field) { return new Exists(field, true); }

    /**
     * Field existence check.
     *
     * @param exists {@code true} — field must be present; {@code false} — field must be absent
     */
    static SearchQuery exists(String field, boolean exists) { return new Exists(field, exists); }

    /** Negates the given predicate. Best used with operator predicates (gt, lt, regex, in, …). */
    static SearchQuery not(SearchQuery query)            { return new Not(query); }

    /** Both {@code a} and {@code b} must match. */
    static SearchQuery and(SearchQuery a, SearchQuery b) { return new And(List.of(a, b)); }

    /** All supplied predicates must match. */
    static SearchQuery and(SearchQuery... queries)       { return new And(List.of(queries)); }

    /** Either {@code a} or {@code b} (or both) must match. */
    static SearchQuery or(SearchQuery a, SearchQuery b)  { return new Or(List.of(a, b)); }

    /** At least one of the supplied predicates must match. */
    static SearchQuery or(SearchQuery... queries)        { return new Or(List.of(queries)); }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /**
     * Navigates a dot-notation field path through nested maps.
     * Returns {@code null} if any segment is absent or not a {@link Map}.
     */
    static Object resolve(Map<String, Object> doc, String field) {
        if (!field.contains(".")) {
            return doc.get(field);
        }
        int dot = field.indexOf('.');
        String head = field.substring(0, dot);
        String tail = field.substring(dot + 1);
        Object child = doc.get(head);
        if (child instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMap = (Map<String, Object>) nested;
            return resolve(nestedMap, tail);
        }
        return null;
    }

    /**
     * Normalises numeric values to {@code Double} so that cross-type comparisons
     * (e.g. {@code Integer} vs {@code Long}) work correctly.
     * Non-numeric values are returned unchanged.
     */
    static Object normalize(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return val;
    }

    /** Compares two values after normalisation. Returns negative / zero / positive. */
    @SuppressWarnings("unchecked")
    static int compareValues(Object a, Object b) {
        Object na = normalize(a);
        Object nb = normalize(b);
        if (na instanceof Comparable ca && nb instanceof Comparable cb) {
            try {
                return ca.compareTo(cb);
            } catch (ClassCastException ignored) {
                return -1;
            }
        }
        return -1;
    }

    // ── Implementations ───────────────────────────────────────────────────────

    record Eq(String field, Object value) implements SearchQuery {
        @Override public Bson toMongoFilter() { return Filters.eq(field, value); }
        @Override public boolean test(Map<String, Object> doc) {
            return Objects.equals(normalize(resolve(doc, field)), normalize(value));
        }
    }

    record Ne(String field, Object value) implements SearchQuery {
        @Override public Bson toMongoFilter() { return Filters.ne(field, value); }
        @Override public boolean test(Map<String, Object> doc) {
            return !Objects.equals(normalize(resolve(doc, field)), normalize(value));
        }
    }

    record Gt(String field, Object value) implements SearchQuery {
        @Override public Bson toMongoFilter() { return Filters.gt(field, value); }
        @Override public boolean test(Map<String, Object> doc) {
            return compareValues(resolve(doc, field), value) > 0;
        }
    }

    record Gte(String field, Object value) implements SearchQuery {
        @Override public Bson toMongoFilter() { return Filters.gte(field, value); }
        @Override public boolean test(Map<String, Object> doc) {
            return compareValues(resolve(doc, field), value) >= 0;
        }
    }

    record Lt(String field, Object value) implements SearchQuery {
        @Override public Bson toMongoFilter() { return Filters.lt(field, value); }
        @Override public boolean test(Map<String, Object> doc) {
            return compareValues(resolve(doc, field), value) < 0;
        }
    }

    record Lte(String field, Object value) implements SearchQuery {
        @Override public Bson toMongoFilter() { return Filters.lte(field, value); }
        @Override public boolean test(Map<String, Object> doc) {
            return compareValues(resolve(doc, field), value) <= 0;
        }
    }

    record Like(String field, String substring) implements SearchQuery {
        @Override public Bson toMongoFilter() {
            return Filters.regex(field, Pattern.quote(substring), "i");
        }
        @Override public boolean test(Map<String, Object> doc) {
            Object val = resolve(doc, field);
            if (val == null) return false;
            return val.toString().toLowerCase(Locale.ROOT)
                      .contains(substring.toLowerCase(Locale.ROOT));
        }
    }

    record Regex(String field, String pattern) implements SearchQuery {
        @Override public Bson toMongoFilter() {
            return Filters.regex(field, pattern);
        }
        @Override public boolean test(Map<String, Object> doc) {
            Object val = resolve(doc, field);
            if (val == null) return false;
            return Pattern.compile(pattern).matcher(val.toString()).find();
        }
    }

    record In(String field, List<?> values) implements SearchQuery {
        @Override public Bson toMongoFilter() { return Filters.in(field, values); }
        @Override public boolean test(Map<String, Object> doc) {
            Object fieldVal = resolve(doc, field);
            // If the document field is itself a collection (e.g. List<String> tags),
            // match if any element of that collection is in the provided values list.
            if (fieldVal instanceof Collection<?> col) {
                return col.stream().anyMatch(elem ->
                    values.stream().anyMatch(v -> Objects.equals(normalize(elem), normalize(v)))
                );
            }
            // Scalar field: match if the value equals any entry in the provided list.
            return values.stream().anyMatch(v -> Objects.equals(normalize(fieldVal), normalize(v)));
        }
    }

    record Exists(String field, boolean exists) implements SearchQuery {
        @Override public Bson toMongoFilter() { return Filters.exists(field, exists); }
        @Override public boolean test(Map<String, Object> doc) {
            boolean hasField = field.contains(".")
                ? resolve(doc, field) != null   // best-effort for nested paths
                : doc.containsKey(field);
            return this.exists == hasField;
        }
    }

    record And(List<SearchQuery> queries) implements SearchQuery {
        @Override public Bson toMongoFilter() {
            return Filters.and(queries.stream().map(SearchQuery::toMongoFilter).toList());
        }
        @Override public boolean test(Map<String, Object> doc) {
            return queries.stream().allMatch(q -> q.test(doc));
        }
    }

    record Or(List<SearchQuery> queries) implements SearchQuery {
        @Override public Bson toMongoFilter() {
            return Filters.or(queries.stream().map(SearchQuery::toMongoFilter).toList());
        }
        @Override public boolean test(Map<String, Object> doc) {
            return queries.stream().anyMatch(q -> q.test(doc));
        }
    }

    /**
     * Logical NOT.  In MongoDB, this wraps operator expressions with {@code $not}
     * (e.g. {@code not(gt("age", 18))} → {@code {age: {$not: {$gt: 18}}}}).
     * For negating a simple equality, prefer {@link Ne} rather than
     * {@code not(eq(…))}, which is not valid MongoDB syntax.
     */
    record Not(SearchQuery query) implements SearchQuery {
        @Override public Bson toMongoFilter() {
            return Filters.not(query.toMongoFilter());
        }
        @Override public boolean test(Map<String, Object> doc) {
            return !query.test(doc);
        }
    }
}
