package sh.vork.database.entities;

/**
 * Value-object record — not a {@code DatabaseEntity} itself.
 * Embedded inside {@link PersonEntity} to test nested-record serialisation.
 */
public record AddressRecord(
        String street,
        String city,
        String country,
        String postalCode
) {}
