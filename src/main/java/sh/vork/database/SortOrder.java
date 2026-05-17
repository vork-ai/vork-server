package sh.vork.database;

/**
 * Direction for {@link DatabaseRepository#search} sort ordering.
 */
public enum SortOrder {
    /** Ascending — smallest value first (A → Z, 0 → 9). */
    ASC,
    /** Descending — largest value first (Z → A, 9 → 0). */
    DESC
}
