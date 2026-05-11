package sh.vork.database.entities;

/**
 * Value-object record for contact information.
 * Embedded inside {@link ProductEntity} to test List&lt;NestedRecord&gt; serialisation.
 */
public record ContactInfo(String email, String phone) {}
