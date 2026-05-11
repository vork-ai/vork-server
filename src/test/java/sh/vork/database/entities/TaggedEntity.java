package sh.vork.database.entities;

import sh.vork.database.DatabaseEntity;

import java.util.List;
import java.util.Map;

/**
 * Entity with collection types.
 * Exercises: List&lt;String&gt;, Map&lt;String,String&gt;.
 */
public record TaggedEntity(
        String uuid,
        String name,
        List<String> tags,
        Map<String, String> metadata
) implements DatabaseEntity {}
