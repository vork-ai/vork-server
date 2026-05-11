package sh.vork.database;

import sh.vork.database.entities.*;
import sh.vork.database.mock.MapDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link DatabaseRepository} contract using the
 * {@link MapDatabaseRepository} mock.
 *
 * <p>Tests are grouped by entity type, covering:
 * <ul>
 *   <li>Primitive and wrapper field types ({@link SimpleEntity})</li>
 *   <li>Nested records ({@link PersonEntity} → {@link AddressRecord})</li>
 *   <li>Collection types ({@link TaggedEntity})</li>
 *   <li>Deep nesting with List&lt;Record&gt; ({@link ProductEntity})</li>
 *   <li>Cross-cutting: update, delete, count, paging, stream lifecycle</li>
 * </ul>
 */
class DatabaseRepositoryTest {

    private MapDatabaseRepository<SimpleEntity> simpleRepo;
    private MapDatabaseRepository<PersonEntity> personRepo;
    private MapDatabaseRepository<TaggedEntity> taggedRepo;
    private MapDatabaseRepository<ProductEntity> productRepo;

    @BeforeEach
    void setup() {
        simpleRepo = new MapDatabaseRepository<>(SimpleEntity.class);
        personRepo = new MapDatabaseRepository<>(PersonEntity.class);
        taggedRepo = new MapDatabaseRepository<>(TaggedEntity.class);
        productRepo = new MapDatabaseRepository<>(ProductEntity.class);
    }

    // =========================================================================
    // SimpleEntity — primitive & wrapper fields
    // =========================================================================

    @Nested
    class SimpleEntityTests {

        @Test
        void saveAndGetRoundTrip() {
            String uuid = UUID.randomUUID().toString();
            SimpleEntity entity = new SimpleEntity(uuid, "Alice", 30, true, 9.5);

            simpleRepo.save(entity);
            SimpleEntity retrieved = simpleRepo.get(uuid);

            assertNotNull(retrieved);
            assertEquals(uuid, retrieved.uuid());
            assertEquals("Alice", retrieved.name());
            assertEquals(30, retrieved.age());
            assertTrue(retrieved.active());
            assertEquals(9.5, retrieved.score(), 0.001);
        }

        @Test
        void saveIsAnUpsert_updatesExistingEntity() {
            String uuid = UUID.randomUUID().toString();
            simpleRepo.save(new SimpleEntity(uuid, "Bob", 25, false, 5.0));
            simpleRepo.save(new SimpleEntity(uuid, "Bob Updated", 26, true, 7.5));

            assertEquals(1, simpleRepo.count(), "Upsert must not create a duplicate");
            SimpleEntity retrieved = simpleRepo.get(uuid);
            assertEquals("Bob Updated", retrieved.name());
            assertEquals(26, retrieved.age());
            assertTrue(retrieved.active());
        }

        @Test
        void deleteRemovesEntity() {
            String uuid = UUID.randomUUID().toString();
            simpleRepo.save(new SimpleEntity(uuid, "Charlie", 40, true, 3.0));

            simpleRepo.delete(uuid);

            assertNull(simpleRepo.get(uuid));
            assertEquals(0, simpleRepo.count());
        }

        @Test
        void deleteNonExistentIsNoOp() {
            assertDoesNotThrow(() -> simpleRepo.delete(UUID.randomUUID().toString()));
        }

        @Test
        void getNonExistentReturnsNull() {
            assertNull(simpleRepo.get(UUID.randomUUID().toString()));
        }

        @Test
        void jsonStoreIsKeyedOnUuid() {
            String uuid = UUID.randomUUID().toString();
            simpleRepo.save(new SimpleEntity(uuid, "Dave", 35, false, 6.0));

            Map<String, String> store = simpleRepo.getJsonStore();
            assertTrue(store.containsKey(uuid));
            assertTrue(store.get(uuid).contains("Dave"),
                    "Stored JSON should contain the entity's name field");
        }

        @Test
        void booleanAndDoublePrimitivesRoundTripCorrectly() {
            String uuid = UUID.randomUUID().toString();
            simpleRepo.save(new SimpleEntity(uuid, "Eve", 0, false, 0.0));

            SimpleEntity r = simpleRepo.get(uuid);
            assertFalse(r.active());
            assertEquals(0, r.age());
            assertEquals(0.0, r.score(), 0.0001);
        }
    }

    // =========================================================================
    // PersonEntity — nested record + List<String>
    // =========================================================================

    @Nested
    class PersonEntityTests {

        @Test
        void nestedRecordRoundTrip() {
            String uuid = UUID.randomUUID().toString();
            AddressRecord address = new AddressRecord("10 Main St", "Springfield", "USA", "12345");
            PersonEntity person = new PersonEntity(uuid, "Eve", "Smith", address,
                    List.of("+1234567890", "+0987654321"), 28);

            personRepo.save(person);
            PersonEntity retrieved = personRepo.get(uuid);

            assertNotNull(retrieved);
            assertEquals("Eve", retrieved.firstName());
            assertEquals("Smith", retrieved.lastName());
            assertEquals(28, retrieved.age());

            assertNotNull(retrieved.address());
            assertEquals("10 Main St", retrieved.address().street());
            assertEquals("Springfield", retrieved.address().city());
            assertEquals("USA", retrieved.address().country());
            assertEquals("12345", retrieved.address().postalCode());

            assertEquals(List.of("+1234567890", "+0987654321"), retrieved.phoneNumbers());
        }

        @Test
        void nullNestedRecordIsPreserved() {
            String uuid = UUID.randomUUID().toString();
            PersonEntity person = new PersonEntity(uuid, "Frank", "Jones", null, List.of(), 22);

            personRepo.save(person);
            PersonEntity retrieved = personRepo.get(uuid);

            assertNotNull(retrieved);
            assertNull(retrieved.address(), "Null nested record should deserialise as null");
            assertTrue(retrieved.phoneNumbers().isEmpty());
        }

        @Test
        void emptyPhoneListRoundTrips() {
            String uuid = UUID.randomUUID().toString();
            personRepo.save(new PersonEntity(uuid, "Grace", "Lee", null, List.of(), 19));

            PersonEntity r = personRepo.get(uuid);
            assertNotNull(r.phoneNumbers());
            assertTrue(r.phoneNumbers().isEmpty());
        }
    }

    // =========================================================================
    // TaggedEntity — List<String> + Map<String,String>
    // =========================================================================

    @Nested
    class TaggedEntityTests {

        @Test
        void listAndMapFieldsRoundTrip() {
            String uuid = UUID.randomUUID().toString();
            List<String> tags = List.of("spring", "mongodb", "java");
            Map<String, String> meta = Map.of("version", "1.0", "env", "test");
            TaggedEntity entity = new TaggedEntity(uuid, "Framework", tags, meta);

            taggedRepo.save(entity);
            TaggedEntity retrieved = taggedRepo.get(uuid);

            assertNotNull(retrieved);
            assertEquals("Framework", retrieved.name());
            assertEquals(tags, retrieved.tags());
            assertEquals("1.0", retrieved.metadata().get("version"));
            assertEquals("test", retrieved.metadata().get("env"));
        }

        @Test
        void emptyCollectionsRoundTrip() {
            String uuid = UUID.randomUUID().toString();
            taggedRepo.save(new TaggedEntity(uuid, "Empty", List.of(), Map.of()));

            TaggedEntity r = taggedRepo.get(uuid);
            assertTrue(r.tags().isEmpty());
            assertTrue(r.metadata().isEmpty());
        }
    }

    // =========================================================================
    // ProductEntity — deep nesting: nested record + List<NestedRecord> + Map + long
    // =========================================================================

    @Nested
    class ProductEntityTests {

        @Test
        void deepNestingRoundTrip() {
            String uuid = UUID.randomUUID().toString();
            DimensionsRecord dims = new DimensionsRecord(10.5, 5.0, 2.0);
            ContactInfo c1 = new ContactInfo("alice@example.com", "+1111111111");
            ContactInfo c2 = new ContactInfo("bob@example.com", "+2222222222");
            ProductEntity product = new ProductEntity(
                    uuid, "SKU-001", "A test product",
                    dims, List.of(c1, c2),
                    Map.of("colour", "red", "material", "steel"),
                    100L);

            productRepo.save(product);
            ProductEntity retrieved = productRepo.get(uuid);

            assertNotNull(retrieved);
            assertEquals("SKU-001", retrieved.sku());
            assertEquals("A test product", retrieved.description());
            assertEquals(100L, retrieved.stockCount());

            assertNotNull(retrieved.dimensions());
            assertEquals(10.5, retrieved.dimensions().width(), 0.001);
            assertEquals(5.0, retrieved.dimensions().height(), 0.001);
            assertEquals(2.0, retrieved.dimensions().depth(), 0.001);

            assertEquals(2, retrieved.contacts().size());
            assertEquals("alice@example.com", retrieved.contacts().get(0).email());
            assertEquals("+1111111111", retrieved.contacts().get(0).phone());
            assertEquals("bob@example.com", retrieved.contacts().get(1).email());

            assertEquals("red", retrieved.attributes().get("colour"));
            assertEquals("steel", retrieved.attributes().get("material"));
        }

        @Test
        void emptyContactListRoundTrips() {
            String uuid = UUID.randomUUID().toString();
            ProductEntity product = new ProductEntity(
                    uuid, "SKU-EMPTY", "No contacts",
                    new DimensionsRecord(1, 1, 1), List.of(), Map.of(), 0L);

            productRepo.save(product);
            ProductEntity r = productRepo.get(uuid);

            assertNotNull(r);
            assertTrue(r.contacts().isEmpty());
            assertEquals(0L, r.stockCount());
        }
    }

    // =========================================================================
    // Cross-cutting: count, paging, stream lifecycle
    // =========================================================================

    @Nested
    class PagingAndCountTests {

        @Test
        void countReflectsLiveState() {
            assertEquals(0, simpleRepo.count());

            simpleRepo.save(new SimpleEntity(UUID.randomUUID().toString(), "A", 1, true, 1.0));
            assertEquals(1, simpleRepo.count());

            simpleRepo.save(new SimpleEntity(UUID.randomUUID().toString(), "B", 2, false, 2.0));
            assertEquals(2, simpleRepo.count());

            String uuid = UUID.randomUUID().toString();
            simpleRepo.save(new SimpleEntity(uuid, "C", 3, true, 3.0));
            simpleRepo.delete(uuid);
            assertEquals(2, simpleRepo.count());
        }

        @Test
        void listPageZeroReturnsFirstItems() {
            for (int i = 0; i < 5; i++) {
                simpleRepo.save(new SimpleEntity(String.format("uuid-%02d", i), "Name-" + i, i, true, 0.0));
            }

            List<SimpleEntity> page;
            try (Stream<SimpleEntity> stream = simpleRepo.list(0, 5)) {
                page = stream.toList();
            }

            assertEquals(5, page.size());
        }

        @Test
        void listPagingReturnsCorrectSubsets() {
            for (int i = 0; i < 10; i++) {
                simpleRepo.save(new SimpleEntity(String.format("uuid-%02d", i), "Entity-" + i, i, true, i * 1.0));
            }

            try (Stream<SimpleEntity> page0 = simpleRepo.list(0, 3)) {
                assertEquals(3, page0.count());
            }
            try (Stream<SimpleEntity> page1 = simpleRepo.list(1, 3)) {
                assertEquals(3, page1.count());
            }
            try (Stream<SimpleEntity> page2 = simpleRepo.list(2, 3)) {
                assertEquals(3, page2.count());
            }
            // Page 3 has only the last item
            try (Stream<SimpleEntity> page3 = simpleRepo.list(3, 3)) {
                assertEquals(1, page3.count());
            }
            // Page 4 is empty
            try (Stream<SimpleEntity> page4 = simpleRepo.list(4, 3)) {
                assertEquals(0, page4.count());
            }
        }

        @Test
        void listWithPageSizeGreaterThanTotalReturnsAll() {
            for (int i = 0; i < 3; i++) {
                simpleRepo.save(new SimpleEntity(String.format("uuid-%02d", i), "X", i, false, 0.0));
            }

            List<SimpleEntity> result;
            try (Stream<SimpleEntity> stream = simpleRepo.list(0, 100)) {
                result = stream.toList();
            }
            assertEquals(3, result.size());
        }

        @Test
        void streamCanBeClosedWithTryWithResources() {
            simpleRepo.save(new SimpleEntity(UUID.randomUUID().toString(), "A", 1, true, 1.0));
            simpleRepo.save(new SimpleEntity(UUID.randomUUID().toString(), "B", 2, false, 2.0));

            List<SimpleEntity> collected;
            try (Stream<SimpleEntity> stream = simpleRepo.list(0, 10)) {
                collected = stream.toList();
            } // stream.close() — must not throw

            assertEquals(2, collected.size());
        }

        @Test
        void multipleRepositoriesAreScopedIndependently() {
            simpleRepo.save(new SimpleEntity(UUID.randomUUID().toString(), "S", 1, true, 1.0));
            personRepo.save(new PersonEntity(UUID.randomUUID().toString(), "P", "Q", null, List.of(), 20));

            assertEquals(1, simpleRepo.count());
            assertEquals(1, personRepo.count());
            assertEquals(0, taggedRepo.count());
        }
    }
}
