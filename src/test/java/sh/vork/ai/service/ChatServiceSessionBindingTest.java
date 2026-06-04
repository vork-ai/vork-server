package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import com.jadaptive.orm.mock.MapDatabaseRepository;
import sh.vork.scheduling.service.SystemNotificationService;
import sh.vork.storage.FileStorageService;

class ChatServiceSessionBindingTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getOrCreateSession_usesHttpSessionIdAsPersistentKey() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "pw"));

        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                mock(AiOrchestrationService.class),
                mock(FileStorageService.class),
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run);

        AiSession created = chatService.getOrCreateSession("http-session-123", AiProvider.GEMINI);
        assertEquals("http-session-123", created.uuid());
        assertEquals("alice", created.username());
        assertEquals(AiSessionStatus.RUNNING, created.status());
        assertEquals(SessionOriginMode.WEB, created.originMode());

        AiSession loaded = chatService.getOrCreateSession("http-session-123", AiProvider.GEMINI);
        assertEquals(created.uuid(), loaded.uuid());
        assertEquals(created.createdAt(), loaded.createdAt());
    }

    @Test
    void getOrCreateSession_rejectsCrossUserAccessToExistingSessionId() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        sessionRepo.save(new AiSession(
                "http-session-shared",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("bob", "pw"));

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                mock(AiOrchestrationService.class),
                mock(FileStorageService.class),
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run);

        assertThrows(IllegalStateException.class,
                () -> chatService.getOrCreateSession("http-session-shared", AiProvider.GEMINI));
    }
}
