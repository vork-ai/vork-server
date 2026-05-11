package sh.vork.ai.function;

/**
 * Input schema for the {@code listJavaTypes} function tool.
 *
 * <p>This tool takes no arguments — the record is empty and exists solely to
 * satisfy the Spring AI {@code FunctionToolCallback} API which requires an
 * explicit input type.
 */
public record ListJavaTypesRequest() {}
