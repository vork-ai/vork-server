package sh.vork.database.entities;

import sh.vork.database.DatabaseEntity;

/**
 * Flat entity with primitive and wrapper field types.
 * Exercises: String, int, boolean, double.
 */
public record SimpleEntity(
        String uuid,
        String name,
        int age,
        boolean active,
        double score
) implements DatabaseEntity {}
