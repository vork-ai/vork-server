package sh.vork.scheduling.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.service.ChatService;
import sh.vork.database.DatabaseRepository;

@Service
public class BackgroundOrchestrationEngine {

    private static final Logger log = LoggerFactory.getLogger(BackgroundOrchestrationEngine.class);
    private static final int MAX_ROUNDS = 10;
    private static final String CONTINUE_PROMPT = "Continue autonomously toward completion. If objectives are fully satisfied, invoke completeBackgroundTask.";

    private final ChatService chatService;
    private final DatabaseRepository<AiSession> sessionRepository;
    private final BackgroundExecutionContext executionContext;

    public BackgroundOrchestrationEngine(ChatService chatService,
                                         DatabaseRepository<AiSession> sessionRepository,
                                         BackgroundExecutionContext executionContext) {
        this.chatService = chatService;
        this.sessionRepository = sessionRepository;
        this.executionContext = executionContext;
    }

    public void executeBackgroundTurn(String sessionUuid, String initialPrompt) {
        String prompt = (initialPrompt == null || initialPrompt.isBlank()) ? CONTINUE_PROMPT : initialPrompt;
        boolean firstRound = true;

        try {
            while (true) {
                AiSession session = sessionRepository.get(sessionUuid);
                if (session == null) {
                    log.warn("Background loop aborted: session not found [session={}]", sessionUuid);
                    return;
                }

                if (session.currentRoundCount() >= MAX_ROUNDS) {
                    List<AiChatMessage> updated = new ArrayList<>(session.messages());
                    updated.add(new AiChatMessage(
                            java.util.UUID.randomUUID().toString(),
                            "ASSISTANT",
                            "Background execution stopped after reaching max rounds (10).",
                            System.currentTimeMillis(),
                            null));

                    sessionRepository.save(new AiSession(
                            session.uuid(),
                            session.provider(),
                            session.originMode(),
                            session.username(),
                            session.name(),
                            session.createdAt(),
                            session.currentRoundCount(),
                            List.copyOf(updated),
                            AiSessionStatus.FAILED_MAX_ROUNDS));
                    log.warn("Background loop stopped at max rounds [session={}]", sessionUuid);
                    return;
                }

                sessionRepository.save(new AiSession(
                        session.uuid(),
                        session.provider(),
                        session.originMode(),
                        session.username(),
                    session.name(),
                        session.createdAt(),
                        session.currentRoundCount() + 1,
                        session.messages(),
                        AiSessionStatus.RUNNING));

                executionContext.clear();

                try (MDC.MDCCloseable sid = MDC.putCloseable("sessionUuid", sessionUuid)) {
                    chatService.sendMessage(
                            sessionUuid,
                            firstRound ? prompt : CONTINUE_PROMPT,
                            List.of(),
                            AiProvider.BACKGROUND_SCHEDULER);
                } catch (ToolSuspensionException ex) {
                    log.info("Background loop paused by authorization fence [session={}, tool={}]", sessionUuid,
                            ex.getToolName());
                    return;
                }

                firstRound = false;

                AiSession afterTurn = sessionRepository.get(sessionUuid);
                if (afterTurn == null) {
                    return;
                }
                if (afterTurn.status() == AiSessionStatus.AWAITING_INPUT) {
                    log.info("Background loop awaiting authorization [session={}]", sessionUuid);
                    return;
                }
                if (executionContext.isExecutionComplete()) {
                    log.info("Background loop completion flag detected [session={}]", sessionUuid);
                    return;
                }
            }
        } finally {
            executionContext.clear();
        }
    }
}
