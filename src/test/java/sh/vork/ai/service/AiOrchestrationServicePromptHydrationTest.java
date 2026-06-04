package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.ai.AiProvider;
import sh.vork.ai.config.AiConfig;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.memory.SessionEnvironmentService;

class AiOrchestrationServicePromptHydrationTest {

    @AfterEach
    void clearThreadLocalContext() {
        ToolExecutionContext.clear();
    }

    @Test
    void composeSystemPrompt_whenNoSessionBound_returnsBasePromptOnly() throws Exception {
        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);
        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
                Map.of(), envService, mock(DatabaseRepository.class), mock(DatabaseRepository.class), Map.of());

        String prompt = invokeComposeSystemPrompt(service);

        assertEquals(AiConfig.BASE_SYSTEM_PROMPT, prompt);
    }

    @Test
    void composeSystemPrompt_whenSessionBoundButEnvEmpty_returnsBasePromptOnly() throws Exception {
        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);
        when(envService.getEnv("session-1")).thenReturn(Map.of());
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        when(sessionRepo.get("session-1")).thenReturn(null);

        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
                Map.of(AiProvider.GEMINI, mock(ChatClient.class)), envService,
                sessionRepo, mock(DatabaseRepository.class), Map.of());
        ToolExecutionContext.bindSessionUuid("session-1");

        String prompt = invokeComposeSystemPrompt(service);

        assertEquals(AiConfig.BASE_SYSTEM_PROMPT, prompt);
        verify(envService).getEnv("session-1");
    }

    @Test
    void composeSystemPrompt_whenEnvPresent_appendsEnvHeaderAndKeyValueLines() throws Exception {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("activeTargetAnchor", "local");
        env.put("selectedProfile", "prod");

        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);
        when(envService.getEnv("session-2")).thenReturn(env);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        when(sessionRepo.get("session-2")).thenReturn(null);

        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
                Map.of(AiProvider.GEMINI, mock(ChatClient.class)), envService,
                sessionRepo, mock(DatabaseRepository.class), Map.of());
        ToolExecutionContext.bindSessionUuid("session-2");

        String prompt = invokeComposeSystemPrompt(service);

        String expected = AiConfig.BASE_SYSTEM_PROMPT
                + "\n### ACTIVE SESSION ENVIRONMENT VARIABLES\n"
                + "activeTargetAnchor=local\n"
                + "selectedProfile=prod\n";

        assertEquals(expected, prompt);
        verify(envService).getEnv("session-2");
    }

    private static String invokeComposeSystemPrompt(AiOrchestrationService service) throws Exception {
        Method compose = AiOrchestrationService.class.getDeclaredMethod("composeSystemPrompt");
        compose.setAccessible(true);
        return (String) compose.invoke(service);
    }
}
