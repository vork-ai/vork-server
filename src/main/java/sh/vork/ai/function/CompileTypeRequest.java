package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code compileJavaType} function tool.
 *
 * <p>The model supplies complete Java source code for a single compilation unit.
 * All AI-generated types should live in {@code sh.vork.generated} so they are
 * easy to identify and do not collide with application classes.
 */
public record CompileTypeRequest(
        @JsonProperty(required = true, value = "source")
        @JsonPropertyDescription(
                """
Complete Java source code for a single compilation unit, including a package declaration.
Use package sub-package of {@code sh.vork.generated} for all AI-generated types. " +
Supports record, class, interface, and enum declarations.
If the user asks to create a record or enum, this schema should be used.
         """)
        String source
) {}
