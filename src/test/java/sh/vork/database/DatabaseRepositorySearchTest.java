package sh.vork.database;

import sh.vork.database.entities.PersonEntity;
import sh.vork.database.entities.AddressRecord;
import sh.vork.database.entities.SimpleEntity;
import sh.vork.database.entities.TaggedEntity;
import sh.vork.database.mock.MapDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for {@link DatabaseRepository#search} and
 * {@link DatabaseRepository#searchCount} using the in-memory
 * {@link MapDatabaseRepository}.
 *
 * <p>No Spring context or MongoDB server is required.
 *
 * <p>Test data is inserted fresh before each nested class via
 * {@link BeforeEach} so that every group is self-contained.
 */
class DatabaseRepositorySearchTest {

    // ── Test data ─────────────────────────────────────────────────────────────

    /**
     * Five {@link SimpleEntity} records used by most test groups.
     *
     * <pre>
     *  id  name      age  active  score
     *  1   Alice     30   true    9.5
     *  2   Bob       25   false   5.0
     *  3   Charlie   35   true    8.0
     *  4   Dave      28   true    7.5
     *  5   Alice     22   false   3.0
     * </pre>
     */
    private MapDatabaseRepository<SimpleEntity> simpleRepo;

    @BeforeEach
    void setupSimpleRepo() {
        simpleRepo = new MapDatabaseRepository<>(SimpleEntity.class);
        simpleRepo.save(new SimpleEntity("1", "Alice",   30, true,  9.5));
        simpleRepo.save(new SimpleEntity("2", "Bob",     25, false, 5.0));
        simpleRepo.save(new SimpleEntity("3", "Charlie", 35, true,  8.0));
        simpleRepo.save(new SimpleEntity("4", "Dave",    28, true,  7.5));
        simpleRepo.save(new SimpleEntity("5", "Alice",   22, false, 3.0));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private List<SimpleEntity> search(int page, int pageSize, String sortField,
                                      SortOrder order, SearchQuery... queries) {
        try (Stream<SimpleEntity> s = simpleRepo.search(page, pageSize, sortField, order, queries)) {
            return s.toList();
        }
    }

    // =========================================================================
    // Equality and basic predicates
    // =========================================================================

    @Nested
    class EqualitySearchTests {

        @Test
        void eq_booleanField_returnsMatchingEntities() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.eq("active", true));
            assertEquals(3, results.size());
            assertTrue(results.stream().allMatch(SimpleEntity::active));
        }

        @Test
        void eq_stringField_returnsMatchingEntities() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.eq("name", "Alice"));
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.name().equals("Alice")));
        }

        @Test
        void ne_excludesMatchingEntities() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.ne("name", "Alice"));
            assertEquals(3, results.size());
            assertTrue(results.stream().noneMatch(e -> e.name().equals("Alice")));
        }

        @Test
        void eq_noMatchReturnsEmpty() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.eq("name", "Zara"));
            assertTrue(results.isEmpty());
        }
    }

    // =========================================================================
    // Comparison predicates
    // =========================================================================

    @Nested
    class ComparisonSearchTests {

        @Test
        void gt_returnsEntitiesWithFieldStrictlyGreater() {
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.gt("age", 28));
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.age() > 28));
        }

        @Test
        void gte_includesBoundaryValue() {
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.gte("age", 28));
            // ages 28 (Dave), 30 (Alice), 35 (Charlie) = 3 entities
            assertEquals(3, results.size());
            assertTrue(results.stream().allMatch(e -> e.age() >= 28));
        }

        @Test
        void lt_returnsEntitiesStrictlyLess() {
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.lt("age", 25));
            assertEquals(1, results.size());
            assertEquals(22, results.get(0).age());
        }

        @Test
        void lte_includesBoundaryValue() {
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.lte("age", 25));
            // ages 22 and 25
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.age() <= 25));
        }

        @Test
        void gt_doubleField() {
            List<SimpleEntity> results = search(0, 10, "score", SortOrder.DESC,
                    SearchQuery.gt("score", 7.5));
            // scores 9.5 (Alice) and 8.0 (Charlie) = 2
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.score() > 7.5));
        }
    }

    // =========================================================================
    // String predicates — like and regex
    // =========================================================================

    @Nested
    class StringSearchTests {

        @Test
        void like_caseInsensitiveSubstring() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.like("name", "ali"));
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.name().equalsIgnoreCase("alice")));
        }

        @Test
        void like_noMatch() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.like("name", "xyz"));
            assertTrue(results.isEmpty());
        }

        @Test
        void regex_anchored_matchesStartsWith() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.regex("name", "^C"));
            assertEquals(1, results.size());
            assertEquals("Charlie", results.get(0).name());
        }

        @Test
        void regex_characterClass() {
            // Names starting with A or B
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.regex("name", "^[AB]"));
            assertEquals(3, results.size()); // Alice, Alice, Bob
        }
    }

    // =========================================================================
    // In predicate
    // =========================================================================

    @Nested
    class InSearchTests {

        @Test
        void in_stringVarargs_matchesMultipleValues() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.in("name", "Alice", "Dave"));
            // 2 Alices + 1 Dave
            assertEquals(3, results.size());
        }

        @Test
        void in_intArray_matchesCorrectRecords() {
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.in("age", new int[]{22, 30}));
            assertEquals(2, results.size());
            assertTrue(results.stream().anyMatch(e -> e.age() == 22));
            assertTrue(results.stream().anyMatch(e -> e.age() == 30));
        }

        @Test
        void in_noMatchReturnsEmpty() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.in("name", "Zara", "Max"));
            assertTrue(results.isEmpty());
        }
    }

    // =========================================================================
    // Exists predicate
    // =========================================================================

    @Nested
    class ExistsSearchTests {

        @Test
        void exists_true_returnsAllEntitiesWithField() {
            // All SimpleEntity records have 'name', so all 5 should match.
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.exists("name"));
            assertEquals(5, results.size());
        }

        @Test
        void exists_false_returnsEntitiesWithoutField() {
            // 'nonExistentField' is absent from all entities.
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.exists("nonExistentField", false));
            assertEquals(5, results.size());
        }
    }

    // =========================================================================
    // Logical combinators
    // =========================================================================

    @Nested
    class LogicalSearchTests {

        @Test
        void and_twoConditions() {
            // active=true AND age > 28
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.and(
                            SearchQuery.eq("active", true),
                            SearchQuery.gt("age", 28)));
            // Alice(30, active) + Charlie(35, active) = 2
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.active() && e.age() > 28));
        }

        @Test
        void and_varargs_threeConditions() {
            // active=true AND age >= 28 AND score > 7.5
            List<SimpleEntity> results = search(0, 10, "score", SortOrder.DESC,
                    SearchQuery.and(
                            SearchQuery.eq("active", true),
                            SearchQuery.gte("age", 28),
                            SearchQuery.gt("score", 7.5)));
            // Alice(age=30, score=9.5) + Charlie(age=35, score=8.0) = 2
            assertEquals(2, results.size());
        }

        @Test
        void or_twoConditions() {
            // age < 25 OR name = "Dave"
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.or(
                            SearchQuery.lt("age", 25),
                            SearchQuery.eq("name", "Dave")));
            // Alice(22) + Dave(28) = 2
            assertEquals(2, results.size());
        }

        @Test
        void or_varargs_threeConditions() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.or(
                            SearchQuery.eq("name", "Alice"),
                            SearchQuery.eq("name", "Bob"),
                            SearchQuery.eq("name", "Charlie")));
            // 2 Alices + 1 Bob + 1 Charlie = 4
            assertEquals(4, results.size());
        }

        @Test
        void not_negatesGt() {
            // NOT age > 28  ⟹  age <= 28  ⟹  Bob(25), Dave(28), Alice(22) = 3
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.not(SearchQuery.gt("age", 28)));
            assertEquals(3, results.size());
            assertTrue(results.stream().allMatch(e -> e.age() <= 28));
        }

        @Test
        void not_negatesLike() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.not(SearchQuery.like("name", "ali")));
            assertEquals(3, results.size());
            assertTrue(results.stream().noneMatch(e -> e.name().equalsIgnoreCase("alice")));
        }

        @Test
        void orInsideAnd_composedQuery() {
            // active=true AND (score > 8 OR age > 32)
            List<SimpleEntity> results = search(0, 10, "score", SortOrder.DESC,
                    SearchQuery.and(
                            SearchQuery.eq("active", true),
                            SearchQuery.or(
                                    SearchQuery.gt("score", 8.0),
                                    SearchQuery.gt("age", 32))));
            // Alice(active, score=9.5) + Charlie(active, age=35, score=8.0) = 2
            assertEquals(2, results.size());
        }

        @Test
        void andInsideOr_composedQuery() {
            // (active=true AND age < 30) OR name = "Charlie"
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.or(
                            SearchQuery.and(
                                    SearchQuery.eq("active", true),
                                    SearchQuery.lt("age", 30)),
                            SearchQuery.eq("name", "Charlie")));
            // Dave(active, age=28) + Charlie = 2
            assertEquals(2, results.size());
        }
    }

    // =========================================================================
    // Multiple top-level queries (AND semantics)
    // =========================================================================

    @Nested
    class MultiQueryAndTests {

        @Test
        void twoQueriesActAsAnd() {
            // Passing two separate queries: active=true, age > 28 — both must match
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.eq("active", true),
                    SearchQuery.gt("age", 28));
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.active() && e.age() > 28));
        }

        @Test
        void threeQueriesActAsAnd() {
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC,
                    SearchQuery.eq("active", true),
                    SearchQuery.gt("age", 28),
                    SearchQuery.gt("score", 8.0));
            // Only Alice(age=30, score=9.5, active=true)
            assertEquals(1, results.size());
            assertEquals("Alice", results.get(0).name());
        }

        @Test
        void noQueriesReturnsAll() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC);
            assertEquals(5, results.size());
        }
    }

    // =========================================================================
    // Sorting
    // =========================================================================

    @Nested
    class SortingTests {

        @Test
        void sortByName_asc_alphabeticalOrder() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.ASC);
            // Alice, Alice, Bob, Charlie, Dave
            assertEquals("Alice",   results.get(0).name());
            assertEquals("Alice",   results.get(1).name());
            assertEquals("Bob",     results.get(2).name());
            assertEquals("Charlie", results.get(3).name());
            assertEquals("Dave",    results.get(4).name());
        }

        @Test
        void sortByName_desc_reverseAlphabeticalOrder() {
            List<SimpleEntity> results = search(0, 10, "name", SortOrder.DESC);
            assertEquals("Dave",    results.get(0).name());
            assertEquals("Charlie", results.get(1).name());
            assertEquals("Bob",     results.get(2).name());
        }

        @Test
        void sortByAge_asc() {
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.ASC);
            for (int i = 0; i < results.size() - 1; i++) {
                assertTrue(results.get(i).age() <= results.get(i + 1).age());
            }
        }

        @Test
        void sortByAge_desc() {
            List<SimpleEntity> results = search(0, 10, "age", SortOrder.DESC);
            for (int i = 0; i < results.size() - 1; i++) {
                assertTrue(results.get(i).age() >= results.get(i + 1).age());
            }
        }

        @Test
        void sortByScore_desc_withFilter() {
            List<SimpleEntity> results = search(0, 10, "score", SortOrder.DESC,
                    SearchQuery.eq("active", true));
            assertEquals(3, results.size());
            for (int i = 0; i < results.size() - 1; i++) {
                assertTrue(results.get(i).score() >= results.get(i + 1).score());
            }
        }
    }

    // =========================================================================
    // Paging
    // =========================================================================

    @Nested
    class PagingTests {

        @Test
        void page0_returnsFirstTwoEntities() {
            List<SimpleEntity> page0 = search(0, 2, "age", SortOrder.ASC);
            assertEquals(2, page0.size());
            // youngest first: Alice(22), Bob(25)
            assertEquals(22, page0.get(0).age());
            assertEquals(25, page0.get(1).age());
        }

        @Test
        void page1_returnsNextTwoEntities() {
            List<SimpleEntity> page1 = search(1, 2, "age", SortOrder.ASC);
            assertEquals(2, page1.size());
            // Dave(28), Alice(30)
            assertEquals(28, page1.get(0).age());
            assertEquals(30, page1.get(1).age());
        }

        @Test
        void lastPage_returnsRemainder() {
            List<SimpleEntity> page2 = search(2, 2, "age", SortOrder.ASC);
            assertEquals(1, page2.size());
            assertEquals(35, page2.get(0).age());
        }

        @Test
        void pageBeyondEnd_returnsEmpty() {
            List<SimpleEntity> page = search(99, 10, "name", SortOrder.ASC);
            assertTrue(page.isEmpty());
        }

        @Test
        void pagingRespectsSortOrder() {
            // Sort DESC by age; page 0 of size 2 should be Charlie(35), Alice(30)
            List<SimpleEntity> page0 = search(0, 2, "age", SortOrder.DESC);
            assertEquals(35, page0.get(0).age());
            assertEquals(30, page0.get(1).age());

            List<SimpleEntity> page1 = search(1, 2, "age", SortOrder.DESC);
            assertEquals(28, page1.get(0).age());
            assertEquals(25, page1.get(1).age());
        }

        @Test
        void pagingWithFilter_correctTotalAcrossPages() {
            // active=true → 3 entities; page size 2
            List<SimpleEntity> page0 = search(0, 2, "age", SortOrder.ASC,
                    SearchQuery.eq("active", true));
            List<SimpleEntity> page1 = search(1, 2, "age", SortOrder.ASC,
                    SearchQuery.eq("active", true));

            assertEquals(2, page0.size());
            assertEquals(1, page1.size());
            assertTrue(page0.stream().allMatch(SimpleEntity::active));
            assertTrue(page1.stream().allMatch(SimpleEntity::active));
        }
    }

    // =========================================================================
    // searchCount
    // =========================================================================

    @Nested
    class SearchCountTests {

        @Test
        void noQueries_countEqualsTotal() {
            assertEquals(5, simpleRepo.searchCount());
        }

        @Test
        void singleQuery_correctCount() {
            assertEquals(3, simpleRepo.searchCount(SearchQuery.eq("active", true)));
        }

        @Test
        void multipleQueries_and_correctCount() {
            long count = simpleRepo.searchCount(
                    SearchQuery.eq("active", true),
                    SearchQuery.gt("age", 28));
            assertEquals(2, count);
        }

        @Test
        void orQuery_correctCount() {
            long count = simpleRepo.searchCount(
                    SearchQuery.or(
                            SearchQuery.eq("name", "Alice"),
                            SearchQuery.eq("name", "Bob")));
            assertEquals(3, count); // 2 Alices + 1 Bob
        }

        @Test
        void noMatchQuery_returnsZero() {
            assertEquals(0, simpleRepo.searchCount(SearchQuery.eq("name", "Nobody")));
        }

        @Test
        void countConsistentWithSearch() {
            SearchQuery q = SearchQuery.like("name", "ali");
            long count = simpleRepo.searchCount(q);
            List<SimpleEntity> results;
            try (Stream<SimpleEntity> s = simpleRepo.search(0, 100, "name", SortOrder.ASC, q)) {
                results = s.toList();
            }
            assertEquals(results.size(), count);
        }
    }

    // =========================================================================
    // TaggedEntity — In on List<String> field
    // =========================================================================

    @Nested
    class TaggedEntitySearchTests {

        private MapDatabaseRepository<TaggedEntity> tagRepo;

        @BeforeEach
        void setup() {
            tagRepo = new MapDatabaseRepository<>(TaggedEntity.class);
            tagRepo.save(new TaggedEntity("1", "Widget",    List.of("sale", "new"),              Map.of()));
            tagRepo.save(new TaggedEntity("2", "Gadget",    List.of("new", "featured"),          Map.of()));
            tagRepo.save(new TaggedEntity("3", "Doohickey", List.of("clearance"),                Map.of()));
            tagRepo.save(new TaggedEntity("4", "Thingamajig", List.of("sale", "clearance"),      Map.of()));
        }

        @Test
        void in_onListField_matchesWhenAnyElementInSet() {
            try (Stream<TaggedEntity> s = tagRepo.search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.in("tags", "sale", "clearance"))) {
                List<TaggedEntity> results = s.toList();
                // Widget(sale), Doohickey(clearance), Thingamajig(sale+clearance) = 3
                assertEquals(3, results.size());
            }
        }

        @Test
        void in_onListField_noMatchWhenNoElementInSet() {
            try (Stream<TaggedEntity> s = tagRepo.search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.in("tags", "exclusive", "premium"))) {
                assertTrue(s.toList().isEmpty());
            }
        }

        @Test
        void searchCount_onTagField() {
            long count = tagRepo.searchCount(SearchQuery.in("tags", "new"));
            assertEquals(2, count); // Widget + Gadget
        }

        @Test
        void andWithTagField() {
            try (Stream<TaggedEntity> s = tagRepo.search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.in("tags", "sale"),
                    SearchQuery.in("tags", "clearance"))) {
                List<TaggedEntity> results = s.toList();
                // Only Thingamajig has both
                assertEquals(1, results.size());
                assertEquals("Thingamajig", results.get(0).name());
            }
        }

        @Test
        void orAcrossTagAndName() {
            try (Stream<TaggedEntity> s = tagRepo.search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.or(
                            SearchQuery.in("tags", "featured"),
                            SearchQuery.eq("name", "Widget")))) {
                List<TaggedEntity> results = s.toList();
                // Gadget(featured) + Widget = 2
                assertEquals(2, results.size());
            }
        }
    }

    // =========================================================================
    // PersonEntity — dot-notation on nested record
    // =========================================================================

    @Nested
    class PersonEntitySearchTests {

        private MapDatabaseRepository<PersonEntity> personRepo;

        @BeforeEach
        void setup() {
            personRepo = new MapDatabaseRepository<>(PersonEntity.class);
            personRepo.save(new PersonEntity("1", "Alice", "Smith",
                    new AddressRecord("1 High St",   "London",     "UK", "SW1"),  List.of("555-0001"), 30));
            personRepo.save(new PersonEntity("2", "Bob",   "Jones",
                    new AddressRecord("2 Low Rd",    "Manchester",  "UK", "M1"),  List.of("555-0002"), 25));
            personRepo.save(new PersonEntity("3", "Claire", "Dupont",
                    new AddressRecord("3 Rue Neuve", "Paris",       "FR", "75001"), List.of("555-0003"), 35));
            personRepo.save(new PersonEntity("4", "Denis",  "Martin",
                    new AddressRecord("4 Hauptstr",  "Berlin",      "DE", "10115"), List.of("555-0004"), 28));
        }

        @Test
        void eq_dotNotation_city_matchesSingleEntity() {
            try (Stream<PersonEntity> s = personRepo.search(0, 10, "firstName", SortOrder.ASC,
                    SearchQuery.eq("address.city", "London"))) {
                List<PersonEntity> results = s.toList();
                assertEquals(1, results.size());
                assertEquals("Alice", results.get(0).firstName());
            }
        }

        @Test
        void eq_dotNotation_country_matchesMultiple() {
            try (Stream<PersonEntity> s = personRepo.search(0, 10, "firstName", SortOrder.ASC,
                    SearchQuery.eq("address.country", "UK"))) {
                List<PersonEntity> results = s.toList();
                assertEquals(2, results.size());
            }
        }

        @Test
        void like_dotNotation_citySubstring() {
            try (Stream<PersonEntity> s = personRepo.search(0, 10, "firstName", SortOrder.ASC,
                    SearchQuery.like("address.city", "man"))) {
                List<PersonEntity> results = s.toList();
                // Manchester
                assertEquals(1, results.size());
                assertEquals("Bob", results.get(0).firstName());
            }
        }

        @Test
        void in_dotNotation_country() {
            try (Stream<PersonEntity> s = personRepo.search(0, 10, "firstName", SortOrder.ASC,
                    SearchQuery.in("address.country", "FR", "DE"))) {
                List<PersonEntity> results = s.toList();
                assertEquals(2, results.size());
            }
        }

        @Test
        void andWithDotNotationAndTopLevel() {
            try (Stream<PersonEntity> s = personRepo.search(0, 10, "firstName", SortOrder.ASC,
                    SearchQuery.eq("address.country", "UK"),
                    SearchQuery.gt("age", 27))) {
                List<PersonEntity> results = s.toList();
                // Alice(UK, 30) — Bob(UK, 25) excluded
                assertEquals(1, results.size());
                assertEquals("Alice", results.get(0).firstName());
            }
        }

        @Test
        void sortByNestedField_city_asc() {
            try (Stream<PersonEntity> s = personRepo.search(0, 10, "address.city", SortOrder.ASC)) {
                List<PersonEntity> results = s.toList();
                // Berlin, London, Manchester, Paris
                assertEquals("Denis",  results.get(0).firstName());
                assertEquals("Alice",  results.get(1).firstName());
                assertEquals("Bob",    results.get(2).firstName());
                assertEquals("Claire", results.get(3).firstName());
            }
        }

        @Test
        void searchCount_dotNotation() {
            long count = personRepo.searchCount(SearchQuery.eq("address.country", "UK"));
            assertEquals(2, count);
        }
    }

    // =========================================================================
    // Stream lifecycle
    // =========================================================================

    @Nested
    class StreamLifecycleTests {

        @Test
        void stream_canBeClosedBeforeFullConsumption() {
            // Verify try-with-resources works and doesn't throw
            assertDoesNotThrow(() -> {
                try (Stream<SimpleEntity> s = simpleRepo.search(0, 10, "name", SortOrder.ASC)) {
                    s.findFirst();
                }
            });
        }

        @Test
        void search_doesNotMutateInternalState() {
            // Run the same search twice; both should return the same count
            long first, second;
            try (Stream<SimpleEntity> s = simpleRepo.search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.eq("active", true))) {
                first = s.count();
            }
            try (Stream<SimpleEntity> s = simpleRepo.search(0, 10, "name", SortOrder.ASC,
                    SearchQuery.eq("active", true))) {
                second = s.count();
            }
            assertEquals(first, second);
        }
    }
}
