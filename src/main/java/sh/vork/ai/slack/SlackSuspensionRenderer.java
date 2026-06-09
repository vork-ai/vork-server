package sh.vork.ai.slack;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadaptive.orm.DatabaseRepository;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.telegram.InputFormTokenService;
import sh.vork.ai.telegram.TelegramChatResumptionService;
import sh.vork.notification.slack.SlackApiClient;
import sh.vork.relay.RelayEncryptionService;
import sh.vork.relay.RelayHttpClient;
import sh.vork.relay.RelaySubmission;
import sh.vork.setup.SystemSettingsService;

/**
 * Decides how to render a {@link ToolSuspensionException} prompt for a Slack DM user
 * and sends the appropriate message via the Slack Web API.
 *
 * <h3>Form classification rules</h3>
 * <ul>
 *   <li><b>SIMPLE</b> – no visible user-input fields: sends a numbered list of actions;
 *       the consumer stores a pending action choice and treats the next digit as the pick.</li>
 *   <li><b>SINGLE_TEXT</b> – exactly one visible, non-password text field: sends a plain
 *       prompt asking the user to type the value.</li>
 *   <li><b>WEB_FORM</b> – password field or multiple visible fields: sends a link to
 *       the self-hosted {@code /input-form} endpoint, or to the zero-knowledge relay.</li>
 * </ul>
 */
@Service
public class SlackSuspensionRenderer {

    private static final String DEFAULT_RELAY_BASE_URL = "https://relay.vork.sh";

    private static final Logger log = LoggerFactory.getLogger(SlackSuspensionRenderer.class);

    private final SlackApiClient                slackApiClient;
    private final InputFormTokenService         formTokenService;
    private final SystemSettingsService         systemSettingsService;
    private final RelayEncryptionService        relayEncryption;
    private final RelayHttpClient               relayHttpClient;
    private final TelegramChatResumptionService resumptionService;
    private final DatabaseRepository<AiSession> sessionRepo;
    private final ObjectMapper                  objectMapper;

    @Value("${vork.app.base-url:}")
    private String propertyBaseUrl;

    public SlackSuspensionRenderer(SlackApiClient slackApiClient,
                                    InputFormTokenService formTokenService,
                                    SystemSettingsService systemSettingsService,
                                    RelayEncryptionService relayEncryption,
                                    RelayHttpClient relayHttpClient,
                                    TelegramChatResumptionService resumptionService,
                                    DatabaseRepository<AiSession> sessionRepo,
                                    ObjectMapper objectMapper) {
        this.slackApiClient     = slackApiClient;
        this.formTokenService   = formTokenService;
        this.systemSettingsService = systemSettingsService;
        this.relayEncryption    = relayEncryption;
        this.relayHttpClient    = relayHttpClient;
        this.resumptionService  = resumptionService;
        this.sessionRepo        = sessionRepo;
        this.objectMapper       = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Renders the pending form prompt to the user's Slack DM.
     *
     * @param channelId   Slack DM channel ID
     * @param botToken    bot token
     * @param session     the AWAITING_INPUT session
     * @param promptEvent the PROMPT_REQUIRED event frame from the latest AWAITING_INPUT message
     * @return the classification used, exposed for the consumer to decide whether to set up
     *         a pending field-capture or action-choice slot
     */
    public FormClass render(String channelId, String botToken, AiSession session,
                             UiEventFrame promptEvent) {
        InteractionFormSchema schema  = promptEvent.formSchema();
        String                heading = promptEvent.textResponse();
        String                title   = schema != null ? schema.title() : "Action required";
        String description = (heading != null && !heading.isBlank()) ? heading
                : (schema != null && schema.description() != null ? schema.description() : "");

        FormClass formClass = classify(schema);
        log.debug("Slack suspension render [session={}, class={}, title={}]",
                session.uuid(), formClass, title);

        switch (formClass) {
            case SIMPLE      -> renderSimple(channelId, botToken, session, schema, title, description);
            case SINGLE_TEXT -> renderSingleText(channelId, botToken, schema, title, description);
            case WEB_FORM    -> renderWebForm(channelId, botToken, session, promptEvent, title, description);
        }
        return formClass;
    }

    // ── Classification ────────────────────────────────────────────────────────

    public enum FormClass { SIMPLE, SINGLE_TEXT, WEB_FORM }

    private FormClass classify(InteractionFormSchema schema) {
        if (schema == null || schema.fields() == null || schema.fields().isEmpty())
            return FormClass.SIMPLE;
        List<FormField> visible = schema.fields().stream()
                .filter(f -> f != null && !isInvisibleType(f.type()))
                .toList();
        if (visible.isEmpty())
            return FormClass.SIMPLE;
        if (visible.size() == 1 && !isPasswordType(visible.get(0).type()))
            return FormClass.SINGLE_TEXT;
        return FormClass.WEB_FORM;
    }

    private static boolean isInvisibleType(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return "HIDDEN".equals(t) || "MARKDOWN".equals(t);
    }

    private static boolean isPasswordType(String type) {
        return "password".equalsIgnoreCase(type);
    }

    // ── Render strategies ─────────────────────────────────────────────────────

    /**
     * Sends a numbered list of actions.  The consumer stores the action list so
     * the user's next reply (a digit) can be mapped back to the action name.
     */
    private void renderSimple(String channelId, String botToken, AiSession session,
                               InteractionFormSchema schema, String title, String description) {
        StringBuilder sb = new StringBuilder();
        if (!title.isBlank()) sb.append("*").append(title).append("*\n");
        if (!description.isBlank()) sb.append(description).append("\n");

        String codeContent = extractCodeContent(schema);
        if (codeContent != null && !codeContent.isBlank()) {
            sb.append("```\n").append(codeContent).append("\n```\n");
        }

        List<FormAction> actions = (schema != null && schema.actions() != null)
                ? schema.actions() : List.of();
        if (!actions.isEmpty()) {
            sb.append("\nChoose an option by replying with the number:\n");
            for (int i = 0; i < actions.size(); i++) {
                sb.append(i + 1).append(". ").append(actions.get(i).label()).append("\n");
            }
        }

        slackApiClient.sendMessage(botToken, channelId, sb.toString().trim());
    }

    private void renderSingleText(String channelId, String botToken, InteractionFormSchema schema,
                                   String title, String description) {
        FormField field = schema.fields().stream()
                .filter(f -> f != null && !isInvisibleType(f.type()))
                .findFirst().orElseThrow();
        StringBuilder sb = new StringBuilder();
        if (!title.isBlank()) sb.append("*").append(title).append("*\n");
        if (!description.isBlank()) sb.append(description).append("\n");
        String codeContent = extractCodeContent(schema);
        if (codeContent != null && !codeContent.isBlank()) {
            sb.append("```\n").append(codeContent).append("\n```\n");
        }
        sb.append("\nPlease reply with: ").append(field.label());
        if (field.placeholder() != null && !field.placeholder().isBlank()) {
            sb.append(" (").append(field.placeholder()).append(")");
        }
        slackApiClient.sendMessage(botToken, channelId, sb.toString().trim());
    }

    private void renderWebForm(String channelId, String botToken, AiSession session,
                                UiEventFrame promptEvent, String title, String description) {
        String configuredUrl = resolveConfiguredBaseUrl();
        if (configuredUrl != null) {
            renderWebFormSelfHosted(channelId, botToken, session, promptEvent,
                    title, description, configuredUrl);
        } else {
            renderWebFormRelay(channelId, botToken, session, promptEvent, title, description);
        }
    }

    private void renderWebFormSelfHosted(String channelId, String botToken, AiSession session,
                                          UiEventFrame promptEvent, String title, String description,
                                          String baseUrl) {
        String token = formTokenService.generateToken(
                session.uuid(), promptEvent.eventId(), session.username());
        String url = baseUrl + "/input-form/" + session.uuid() + "/" + promptEvent.eventId()
                + "?token=" + token;
        StringBuilder sb = new StringBuilder();
        if (!title.isBlank()) sb.append("*").append(title).append("*\n");
        if (!description.isBlank()) sb.append(description).append("\n");
        sb.append("\n🔗 Please complete the form: ").append(url);
        slackApiClient.sendMessage(botToken, channelId, sb.toString().trim());
        log.debug("Self-hosted form URL sent to Slack [session={}, event={}]",
                session.uuid(), promptEvent.eventId());
    }

    private void renderWebFormRelay(String channelId, String botToken, AiSession session,
                                     UiEventFrame promptEvent, String title, String description) {
        String relayBaseUrl   = DEFAULT_RELAY_BASE_URL;
        String relaySessionId = promptEvent.eventId();

        InteractionFormSchema schema = promptEvent.formSchema();
        RelayEncryptionService.EncryptionResult enc;
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            enc = relayEncryption.encrypt(schemaJson);
        } catch (Exception e) {
            log.error("Failed to encrypt form schema for relay [session={}, event={}]: {}",
                    session.uuid(), relaySessionId, e.getMessage(), e);
            slackApiClient.sendMessage(botToken, channelId,
                    "⚠️ Form preparation failed. Please try again or use the Vork web app.");
            return;
        }

        int oobTimeoutMins = systemSettingsService.getDefaultOobTimeoutMinutes();
        try {
            relayHttpClient.upload(relayBaseUrl, relaySessionId,
                    enc.ciphertext(), enc.nonce(), enc.authTag(), oobTimeoutMins);
        } catch (Exception e) {
            log.error("Relay upload failed [session={}, event={}]: {}",
                    session.uuid(), relaySessionId, e.getMessage(), e);
            slackApiClient.sendMessage(botToken, channelId,
                    "⚠️ Could not reach the relay server. Please try again later.");
            return;
        }

        String authUrl = relayBaseUrl + "/auth/" + relaySessionId + "#k=" + enc.keyBase64Url();
        StringBuilder sb = new StringBuilder();
        if (!title.isBlank()) sb.append("*").append(title).append("*\n");
        if (!description.isBlank()) sb.append(description).append("\n");
        sb.append("\n🔒 Please complete the secure form: ").append(authUrl);
        slackApiClient.sendMessage(botToken, channelId, sb.toString().trim());
        log.info("Relay form dispatched to Slack [session={}, event={}]",
                session.uuid(), relaySessionId);

        final javax.crypto.SecretKey sessionKey = enc.key();
        Thread.ofVirtual().name("slack-relay-poll-" + relaySessionId).start(() ->
                pollAndResume(relayBaseUrl, relaySessionId, sessionKey,
                              session.username(), session.uuid(), channelId, botToken));
    }

    private void pollAndResume(String relayBaseUrl, String relaySessionId,
                                javax.crypto.SecretKey key,
                                String username, String sessionUuid,
                                String channelId, String botToken) {
        log.debug("ENTER pollAndResume [sessionId={}, sessionUuid={}]", relaySessionId, sessionUuid);
        int consecutiveErrors = 0;
        while (true) {
            AiSession current = sessionRepo.get(sessionUuid);
            if (current == null || current.status() != AiSessionStatus.AWAITING_INPUT) {
                log.debug("Session no longer awaiting input — stopping relay poll [session={}]", sessionUuid);
                return;
            }

            RelaySubmission submission;
            try {
                submission = relayHttpClient.pollForResponse(relayBaseUrl, relaySessionId, 25_000);
                consecutiveErrors = 0;
            } catch (Exception e) {
                consecutiveErrors++;
                log.warn("Relay poll error [sessionId={}, attempt={}]: {}",
                        relaySessionId, consecutiveErrors, e.getMessage());
                if (consecutiveErrors >= 5) {
                    log.error("Relay poll giving up [sessionId={}]", relaySessionId);
                    slackApiClient.sendMessage(botToken, channelId,
                            "The relay connection was lost. Please try submitting the form again.");
                    return;
                }
                try { Thread.sleep(3_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); return;
                }
                continue;
            }

            if (submission == null) continue;

            String responseJson;
            try {
                responseJson = relayEncryption.decrypt(
                        key, submission.encryptedResponse(), submission.nonce(), submission.authTag());
            } catch (Exception e) {
                log.error("Failed to decrypt relay response [sessionId={}]: {}",
                        relaySessionId, e.getMessage(), e);
                slackApiClient.sendMessage(botToken, channelId,
                        "Could not process the form response. Please try again.");
                return;
            }

            String action;
            Map<String, String> fields;
            try {
                Map<String, Object> responseMap = objectMapper.readValue(responseJson,
                        new TypeReference<>() {});
                action = String.valueOf(responseMap.getOrDefault("action", "ONCE"));
                @SuppressWarnings("unchecked")
                Map<String, Object> rawFields =
                        (Map<String, Object>) responseMap.getOrDefault("fields", Map.of());
                fields = new java.util.HashMap<>();
                rawFields.forEach((k, v) -> fields.put(k, v == null ? "" : String.valueOf(v)));
            } catch (Exception e) {
                log.error("Failed to parse relay response JSON [sessionId={}]: {}",
                        relaySessionId, e.getMessage(), e);
                slackApiClient.sendMessage(botToken, channelId,
                        "Could not read the form response. Please try again.");
                return;
            }

            log.info("Relay response received — resuming session [session={}, action={}]",
                    sessionUuid, action);
            try {
                String result = resumptionService.resumeAndRun(
                        username, sessionUuid, relaySessionId, action, fields);
                if (result != null && !result.isBlank()) {
                    slackApiClient.sendMessage(botToken, channelId, result);
                }
            } catch (ToolSuspensionException ex) {
                log.debug("Session re-suspended after relay response [session={}]", sessionUuid);
                AiSession fresh = sessionRepo.get(sessionUuid);
                if (fresh == null) return;
                UiEventFrame nextPrompt = findLatestPromptEvent(fresh);
                if (nextPrompt == null) return;
                try {
                    render(channelId, botToken, fresh, nextPrompt);
                } catch (Exception re) {
                    log.error("Re-render after relay re-suspension failed [session={}]: {}",
                            sessionUuid, re.getMessage(), re);
                }
            } catch (Exception ex) {
                log.error("Session resumption failed [session={}]: {}",
                        sessionUuid, ex.getMessage(), ex);
                slackApiClient.sendMessage(botToken, channelId,
                        "An error occurred while processing your response. Please try again.");
            }
            return;
        }
    }

    // ── Helper: resolve a pending action name from its 1-based index ──────────

    /**
     * Maps a user's numeric reply to the action name at the given 1-based index.
     *
     * @param schema the current form schema
     * @param choice 1-based index string (e.g. "1")
     * @return the action name, or {@code null} if the choice is out of range
     */
    public String resolveActionByIndex(InteractionFormSchema schema, String choice) {
        if (schema == null || schema.actions() == null) return null;
        int idx;
        try { idx = Integer.parseInt(choice.trim()) - 1; }
        catch (NumberFormatException e) { return null; }
        if (idx < 0 || idx >= schema.actions().size()) return null;
        return schema.actions().get(idx).name();
    }

    /**
     * Returns the list of action labels from the schema, for storing alongside
     * a pending action choice entry.
     */
    public List<FormAction> getActions(InteractionFormSchema schema) {
        if (schema == null || schema.actions() == null) return List.of();
        return schema.actions();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public UiEventFrame findLatestPromptEvent(AiSession session) {
        java.util.List<AiChatMessage> msgs = session.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            AiChatMessage m = msgs.get(i);
            if ("PROMPT_REQUIRED".equals(m.role())) {
                try {
                    return objectMapper.readValue(m.content(), UiEventFrame.class);
                } catch (Exception e) {
                    log.warn("Could not parse PROMPT_REQUIRED content [session={}]: {}",
                            session.uuid(), e.getMessage());
                }
            }
        }
        return null;
    }

    private String resolveConfiguredBaseUrl() {
        sh.vork.setup.SystemSettings settings = systemSettingsService.getGlobal();
        if (settings != null && settings.appBaseUrl() != null && !settings.appBaseUrl().isBlank()) {
            String url = settings.appBaseUrl();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        if (propertyBaseUrl != null && !propertyBaseUrl.isBlank()) {
            String url = propertyBaseUrl;
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return null;
    }

    private static String extractCodeContent(InteractionFormSchema schema) {
        if (schema == null || schema.fields() == null) return null;
        for (FormField field : schema.fields()) {
            if (field == null || !"MARKDOWN".equalsIgnoreCase(field.type())) continue;
            String value = field.placeholder();
            if (value == null || value.isBlank()) continue;
            return stripCodeFences(value);
        }
        return null;
    }

    private static String stripCodeFences(String text) {
        if (!text.startsWith("```")) return text;
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) return text;
        String content = text.substring(firstNewline + 1);
        if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
        if (content.endsWith("\n")) content = content.substring(0, content.length() - 1);
        return content;
    }
}
