package sh.vork.ai;

/**
 * Enumeration of supported AI providers.
 *
 * <p>Adding a new provider requires three steps:
 * <ol>
 *   <li>Add an entry here.</li>
 *   <li>Declare a {@code @Bean ChatClient} for it in {@code AiConfig}.</li>
 *   <li>Add the entry to the {@code chatClientRegistry} map bean.</li>
 * </ol>
 */
public enum AiProvider {
    GEMINI,
    OPENAI,
    ANTHROPIC
}
