package sh.vork.database.entities;

/**
 * Value-object record for three-dimensional measurements.
 * Embedded inside {@link ProductEntity} to test nested-record + double fields.
 */
public record DimensionsRecord(double width, double height, double depth) {}
