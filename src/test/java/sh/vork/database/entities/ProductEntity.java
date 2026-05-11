package sh.vork.database.entities;

import sh.vork.database.DatabaseEntity;

import java.util.List;
import java.util.Map;

/**
 * Entity with deep nesting: a nested record, a List of nested records,
 * a Map, and a long field.
 * Exercises: nested record, List&lt;NestedRecord&gt;, Map&lt;String,String&gt;, long.
 */
public record ProductEntity(
        String uuid,
        String sku,
        String description,
        DimensionsRecord dimensions,
        List<ContactInfo> contacts,
        Map<String, String> attributes,
        long stockCount
) implements DatabaseEntity {}
