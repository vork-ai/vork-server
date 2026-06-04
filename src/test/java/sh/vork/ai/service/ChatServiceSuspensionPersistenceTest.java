package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.security.VisualizableToolCallback;
import com.jadaptive.orm.mock.MapDatabaseRepository;
import sh.vork.storage.FileStorageService;
import sh.vork.scheduling.service.SystemNotificationService;

class ChatServiceSuspensionPersistenceTest {

    @Test
    void sendMessage_whenToolSuspended_persistsAwaitingAuthorizationSnapshot() throws Exception {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        String sessionId = "session-1";
        AiSession initial = new AiSession(
            sessionId,
            AiProvider.GEMINI.name(),
            SessionOriginMode.WEB,
                "anonymous",
            "Untitled",
            123L,
            0,
            List.of(),
            AiSession.defaultEnvironmentVariables(),
            AiSessionStatus.RUNNING,
            null);
        sessionRepo.save(initial);

        when(aiService.generateWithHistory(org.mockito.ArgumentMatchers.<org.springframework.ai.chat.messages.Message>anyList(),
            anyString(), any(AiProvider.class)))
                .thenThrow(new ToolSuspensionException("compileJavaType", "{\"source\":\"class Demo {}\"}"));

        // Ensure media path is not accidentally used in this scenario.
        when(aiService.generateWithHistoryAndMedia(
            org.mockito.ArgumentMatchers.<org.springframework.ai.chat.messages.Message>anyList(),
            anyString(),
            org.mockito.ArgumentMatchers.<org.springframework.ai.content.Media>anyList(),
            any(AiProvider.class)))
                .thenReturn("unused");

        ToolCallback compileDelegate = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn("compileJavaType");
        when(compileDelegate.getToolDefinition()).thenReturn(def);
        ToolCallback compileTool = new VisualizableToolCallback(
            compileDelegate,
            args -> "```java\nclass Demo {}\n```"
        );

        ChatService chatService = new ChatService(
            sessionRepo,
            null,
            aiService,
            fileStorageService,
            messaging,
            objectMapper,
            List.of(compileTool),
            mock(SystemNotificationService.class),
            Runnable::run);

        AiChatMessage out = chatService.sendMessage(sessionId, "please compile", null, AiProvider.GEMINI);

        assertNull(out, "Chat turn should terminate with null when authorization is required");

        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals(AiSessionStatus.AWAITING_INPUT, saved.status());
        assertEquals(2, saved.messages().size(), "Expected persisted USER + PROMPT_REQUIRED messages");

        AiChatMessage user = saved.messages().get(0);
        assertEquals("USER", user.role());
        assertEquals("please compile", user.content());

        AiChatMessage awaiting = saved.messages().get(1);
        assertEquals("PROMPT_REQUIRED", awaiting.role());
        UiEventFrame frame = objectMapper.readValue(awaiting.content(), UiEventFrame.class);
        assertEquals("PROMPT_REQUIRED", frame.type());
        assertEquals("AUTHORIZE_TOOL", frame.intent());
        assertEquals("Approval is required to compile and register a new Java type so it can be used in later steps.",
            frame.textResponse());
        assertNotNull(frame.formSchema());
        assertEquals("AUTHORIZE_TOOL", frame.formSchema().intent());
        assertEquals(List.of("ONCE", "SESSION", "ALWAYS", "DENIED"),
            frame.formSchema().actions().stream().map(a -> a.name()).toList());
        assertEquals("Confirm whether this protected tool call should run.",
            frame.formSchema().description());
        assertNotNull(awaiting.toolCalls());
        assertEquals(1, awaiting.toolCalls().size());

        AiChatMessage.ToolCallRef tool = awaiting.toolCalls().get(0);
        assertEquals("FUNCTION", tool.type());
        assertEquals("compileJavaType", tool.name());
        assertEquals("{\"source\":\"class Demo {}\"}", tool.arguments());
        assertTrue(tool.id().startsWith("pending-"));
        assertEquals(tool.id(), awaiting.toolCallId());
        assertEquals("compileJavaType", awaiting.toolName());

        verify(messaging).convertAndSend(anyString(), any(UiEventFrame.class));
    }
}
