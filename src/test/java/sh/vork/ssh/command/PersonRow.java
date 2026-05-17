package sh.vork.ssh.command;

import sh.vork.database.DatabaseEntity;

/**
 * Minimal entity used in command tests.
 */
public record PersonRow(String uuid, String name, int age, boolean active)
        implements DatabaseEntity {}
