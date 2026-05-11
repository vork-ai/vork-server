package sh.vork.database.entities;

import sh.vork.database.DatabaseEntity;

import java.util.List;

/**
 * Entity with a nested record field ({@link AddressRecord}) and a {@link List} of Strings.
 * Exercises: nested record, List&lt;String&gt;, nullable nested record.
 */
public record PersonEntity(
        String uuid,
        String firstName,
        String lastName,
        AddressRecord address,
        List<String> phoneNumbers,
        int age
) implements DatabaseEntity {}
