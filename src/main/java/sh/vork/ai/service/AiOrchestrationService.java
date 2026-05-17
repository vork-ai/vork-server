package sh.vork.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;

import sh.vork.ai.AiProvider;

/**
 * Routes AI generation requests to the appropriate {@link ChatClient} at runtime.
 *
 * <h3>Dynamic routing</h3>
 * The injected {@code Map<AiProvider, ChatClient>} is the single source of truth
 * for which backend backs which enum value.  Adding a new provider only requires
 * updating {@code AiConfig} — this class never changes.
 *
 * <h3>The {@code mutate()} pattern</h3>
 * Each call goes through {@link ChatClient#mutate()} which returns a fresh
 * {@link ChatClient.Builder} pre-seeded with the shared client's configuration
 * (default functions, options, system prompt, etc.).  Building a new instance
 * from that builder gives a per-request {@link ChatClient} with an isolated
 * call chain, so:
 * <ul>
 *   <li>The shared base client is never modified between concurrent calls.</li>
 *   <li>Per-request overrides (extra system instructions, option tweaks, additional
 *       tools) can be applied to the mutated builder before building, without
 *       leaking to other in-flight calls.</li>
 * </ul>
 */
@Service
public class AiOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestrationService.class);

        private final Map<AiProvider, ChatClient> registry;

        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry) {
                this.registry = chatClientRegistry;
    }

    /**
     * Generates a text response for {@code userPrompt} using the specified provider.
     *
     * @param userPrompt the user's prompt text
     * @param provider   the AI backend to route to
     * @return the model's response as a plain string
     * @throws IllegalArgumentException if the provider has no registered client
     */
    public String generate(String userPrompt, AiProvider provider) {
        ChatClient base = registry.get(provider);
        if (base == null) {
            throw new IllegalArgumentException(
                    "No ChatClient configured for provider: " + provider
                    + ". Add a ChatClient @Bean and an entry in AiConfig.chatClientRegistry().");
        }

        // mutate() seeds a fresh builder from the shared client's config so
        // per-request changes (e.g. additional tools, system prompt override)
        // never bleed into other concurrent calls.
        log.info("Generating response [provider={}] prompt=\"{}\"...",
                provider, userPrompt.length() > 120 ? userPrompt.substring(0, 120) + "…" : userPrompt);

        String response = base.mutate()
                .build()
                .prompt()
                .user(userPrompt)
                .call()
                .content();

        log.info("Response received [provider={}, length={}]: {}",
                provider,
                response == null ? 0 : response.length(),
                response == null ? "<null>" : (response.length() > 200 ? response.substring(0, 200) + "…" : response));

        return response;
    }

    /**
     * Generates a response using prior conversation history for context.
     *
     * @param conversationHistory previous turns as Spring AI {@link Message} objects
     * @param newUserMessage      the latest user input
     * @param provider            the AI backend to route to
     * @return the model's response as a plain string
     */
    public String generateWithHistory(List<Message> conversationHistory, String newUserMessage, AiProvider provider) {
        ChatClient base = registry.get(provider);
        if (base == null) {
            throw new IllegalArgumentException(
                    "No ChatClient configured for provider: " + provider
                    + ". Add a ChatClient @Bean and an entry in AiConfig.chatClientRegistry().");
        }

        log.info("Generating chat response [provider={}, history={} msgs]...", provider, conversationHistory.size());

        String response = base.mutate()
                .build()
                .prompt()
                .messages(conversationHistory.toArray(Message[]::new))
                .user(newUserMessage)
                .call()
                .content();

        log.info("Chat response received [provider={}, length={}]",
                provider, response == null ? 0 : response.length());

        return response;
    }

    /**
     * Generates a response with conversation history and media attachments.
     *
     * <p>The {@code media} list is attached to the current user turn so that
     * vision / multimodal models can reason over the provided files.  Pass an
     * empty list (never {@code null}) when there are no attachments.
     *
     * @param conversationHistory previous turns
     * @param userText            the user's text message (may be blank if only media)
     * @param media               Spring AI {@link Media} objects to attach
     * @param provider            the AI backend to route to
     * @return the model's response as a plain string
     */
    public String generateWithHistoryAndMedia(List<Message> conversationHistory,
                                              String userText,
                                              List<Media> media,
                                              AiProvider provider) {
        ChatClient base = registry.get(provider);
        if (base == null) {
            throw new IllegalArgumentException(
                    "No ChatClient configured for provider: " + provider
                    + ". Add a ChatClient @Bean and an entry in AiConfig.chatClientRegistry().");
        }

        log.info("Generating chat response with media [provider={}, history={} msgs, media={}]",
                provider, conversationHistory.size(), media.size());

        List<Message> allMessages = new ArrayList<>(conversationHistory);
        String effectiveText = (userText == null || userText.isBlank()) ? "Please analyse the attached file(s)." : userText;
        allMessages.add(UserMessage.builder().text(effectiveText).media(media).build());

        String response = base.mutate()
                .build()
                .prompt()
                .messages(allMessages.toArray(Message[]::new))
                .call()
                .content();

        log.info("Chat response with media received [provider={}, length={}]",
                provider, response == null ? 0 : response.length());

        return response;
    }

}
