package sh.vork.scheduling.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.service.ChatService;
import sh.vork.skill.SkillFrame;
import sh.vork.ai.session.SessionToolStore;
import sh.vork.orm.DatabaseRepository;

@Service
public class BackgroundOrchestrationEngine {

    private static final Logger log = LoggerFactory.getLogger(BackgroundOrchestrationEngine.class);
    private static final int MAX_ROUNDS = 10;
    private static final String CONTINUE_PROMPT = "Continue autonomously toward completion. If objectives are fully satisfied, invoke completeBackgroundTask.";

    private final ChatService chatService;
    private final DatabaseRepository<AiSession> sessionRepository;
    private final BackgroundExecutionContext executionContext;
    private final SessionToolStore sessionToolStore;
    private final ToolCallback completeBackgroundTask;

    public BackgroundOrchestrationEngine(ChatService chatService,
                                         DatabaseRepository<AiSession> sessionRepository,
                                         BackgroundExecutionContext executionContext,
                                         SessionToolStore sessionToolStore,
                                         @Qualifier("completeBackgroundTask") ToolCallback completeBackgroundTask) {
        this.chatService = chatService;
        this.sessionRepository = sessionRepository;
        this.executionContext = executionContext;
        this.sessionToolStore = sessionToolStore;
        this.completeBackgroundTask = completeBackgroundTask;
    }

    public void executeBackgroundTurn(String sessionUuid, String initialPrompt) {
        String prompt = (initialPrompt == null || initialPrompt.isBlank()) ? CONTINUE_PROMPT : initialPrompt;
        boolean firstRound = true;

        // Register completeBackgroundTask for this session only; it is a hidden tool
        // that signals graceful termination and must not be globally available.
        if (sessionToolStore != null && completeBackgroundTask != null) {
            sessionToolStore.addTool(sessionUuid, completeBackgroundTask);
        }

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
                            session.environmentVariables(),
                            AiSessionStatus.FAILED_MAX_ROUNDS,
                            session.activeAgentTemplateId(),
                            session.modelId(),
                            session.skillStack(),
                            session.sessionSkillUuids(),
                            session.sessionToolIds()));
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
                        session.environmentVariables(),
                        AiSessionStatus.RUNNING,
                        session.activeAgentTemplateId(),
                        session.modelId(),
                        session.skillStack(),
                        session.sessionSkillUuids(),
                        session.sessionToolIds()));

                executionContext.clear();
                    ToolExecutionContext.bindSessionUuid(sessionUuid);
                    ToolExecutionContext.hydrate(session.environmentVariables());

                try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sessionUuid)) {
                    // Use the session's stored provider so job-level overrides take effect.
                    // Fall back to BACKGROUND_SCHEDULER for legacy/authorization-resume flows.
                    AiProvider provider = AiProvider.BACKGROUND_SCHEDULER;
                    try {
                        if (session.provider() != null && !session.provider().isBlank()) {
                            provider = AiProvider.valueOf(session.provider());
                        }
                    } catch (IllegalArgumentException ignored) {}

                                        // When a skill frame is active (e.g. resumed after authorization), the skill AI
                                        // must not see the background-job completion protocol. Background-task directives
                                        // are injected into the system prompt via ToolExecutionContext for parent rounds.
                    boolean skillFrameActive = session.skillStack() != null && !session.skillStack().isEmpty();
                    String effectivePrompt = skillFrameActive
                            ? "Continue executing the skill toward its objective. "
                              + "When the skill objective is fully satisfied, return FINISHED_TURN with your output."
                            : (firstRound ? prompt : CONTINUE_PROMPT);

                    log.debug("Background turn prompt [session={}, skillFrameActive={}, prompt={}]",
                            sessionUuid, skillFrameActive, effectivePrompt);

                    if (!skillFrameActive) {
                        ToolExecutionContext.put("__background_turn_instruction__", effectivePrompt);
                        String taskDirective = null;
                        if (session.environmentVariables() != null) {
                            taskDirective = session.environmentVariables().get("JOB_TASK_PROMPT");
                        }
                        if ((taskDirective == null || taskDirective.isBlank())
                                && initialPrompt != null && !initialPrompt.isBlank()) {
                            taskDirective = initialPrompt;
                        }
                        if (taskDirective != null && !taskDirective.isBlank()) {
                            ToolExecutionContext.put("__background_task_instruction__", taskDirective);
                        }
                    }

                    chatService.sendMessage(
                            sessionUuid,
                            "Proceed.",
                            List.of(),
                            provider,
                            false);
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

                if (afterTurn.status() == AiSessionStatus.COMPLETED) {
                    // If a skill frame is still on the stack, the skill just finished its
                    // FINISHED_TURN via executeAgentLoop on the authorization-resume path.
                    // executeAgentLoop sets COMPLETED but does not pop the frame.
                    // Pop the frame, label the skill output, and resume the parent agent loop.
                    List<SkillFrame> skillStack = afterTurn.skillStack();
                    if (skillStack != null && !skillStack.isEmpty()) {
                        String skillName = skillStack.getLast().skillName();
                        List<SkillFrame> poppedStack = new ArrayList<>(skillStack);
                        poppedStack.removeLast();
                        // Label the last ASSISTANT message as a skill result so the parent agent
                        // understands what the output represents when the loop resumes.
                        List<AiChatMessage> msgs = new ArrayList<>(afterTurn.messages());
                        for (int j = msgs.size() - 1; j >= 0; j--) {
                            if ("ASSISTANT".equals(msgs.get(j).role())) {
                                String labelled = "Skill '" + skillName + "' completed. Output:\n"
                                        + (msgs.get(j).content() != null ? msgs.get(j).content() : "");
                                msgs.set(j, new AiChatMessage(msgs.get(j).uuid(), "ASSISTANT",
                                        labelled, msgs.get(j).timestamp(), null));
                                break;
                            }
                        }
                        sessionRepository.save(new AiSession(
                                afterTurn.uuid(), afterTurn.provider(), afterTurn.originMode(),
                                afterTurn.username(), afterTurn.name(), afterTurn.createdAt(),
                                afterTurn.currentRoundCount(), List.copyOf(msgs),
                                afterTurn.environmentVariables(), AiSessionStatus.RUNNING,
                                afterTurn.activeAgentTemplateId(), afterTurn.modelId(),
                                List.copyOf(poppedStack), afterTurn.sessionSkillUuids(),
                                afterTurn.sessionToolIds()));
                        log.info("Skill completed on authorization-resume path — popping frame and resuming parent loop [session={}, skill={}]",
                                sessionUuid, skillName);
                        continue;
                    }
                    log.info("Background loop terminated by user [session={}]", sessionUuid);
                    return;
                }
                if (executionContext.isExecutionComplete()) {
                    log.info("Background loop completion flag detected [session={}]", sessionUuid);
                    return;
                }
            }
        } finally {
            if (sessionToolStore != null) {
                sessionToolStore.clearSession(sessionUuid);
            }
            executionContext.clear();
            ToolExecutionContext.clear();
        }
    }

    /**
     * Runs the orchestration loop for a skill session.
     *
     * @param sessionUuid   UUID of the {@code SKILL}-origin session
     * @param initialPrompt first user message (built from the skill input)
     */
    public void executeSkillTurn(String sessionUuid, String initialPrompt) {
        final String SKILL_CONTINUE_PROMPT =
                "Continue executing the skill toward completion. "
                + "When the objective is fully satisfied, return FINISHED_TURN with your result.";
        String prompt = (initialPrompt == null || initialPrompt.isBlank())
                ? SKILL_CONTINUE_PROMPT : initialPrompt;
        boolean firstRound = true;

        try {
            while (true) {
                AiSession session = sessionRepository.get(sessionUuid);
                if (session == null) {
                    log.warn("Skill loop aborted: session not found [session={}]", sessionUuid);
                    return;
                }

                if (session.currentRoundCount() >= MAX_ROUNDS) {
                    List<AiChatMessage> updated = new ArrayList<>(session.messages());
                    updated.add(new AiChatMessage(
                            java.util.UUID.randomUUID().toString(),
                            "ASSISTANT",
                            "Skill execution stopped after reaching max rounds (10).",
                            System.currentTimeMillis(),
                            null));
                    sessionRepository.save(new AiSession(
                            session.uuid(), session.provider(), session.originMode(),
                            session.username(), session.name(), session.createdAt(),
                            session.currentRoundCount(), List.copyOf(updated),
                            session.environmentVariables(), AiSessionStatus.FAILED_MAX_ROUNDS,
                            session.activeAgentTemplateId(), session.modelId(),
                            session.skillStack(), session.sessionSkillUuids(), session.sessionToolIds()));
                    log.warn("Skill loop stopped at max rounds [session={}]", sessionUuid);
                    return;
                }

                sessionRepository.save(new AiSession(
                        session.uuid(), session.provider(), session.originMode(),
                        session.username(), session.name(), session.createdAt(),
                        session.currentRoundCount() + 1, session.messages(),
                        session.environmentVariables(), AiSessionStatus.RUNNING,
                        session.activeAgentTemplateId(), session.modelId(),
                        session.skillStack(), session.sessionSkillUuids(), session.sessionToolIds()));

                executionContext.clear();
                ToolExecutionContext.bindSessionUuid(sessionUuid);
                ToolExecutionContext.hydrate(session.environmentVariables());

                try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sessionUuid)) {
                    AiProvider provider = AiProvider.BACKGROUND_SCHEDULER;
                    try {
                        if (session.provider() != null && !session.provider().isBlank()) {
                            provider = AiProvider.valueOf(session.provider());
                        }
                    } catch (IllegalArgumentException ignored) {}
                    chatService.sendMessage(
                            sessionUuid,
                            firstRound ? prompt : SKILL_CONTINUE_PROMPT,
                            List.of(),
                            provider,
                            false);
                } catch (ToolSuspensionException ex) {
                    log.info("Skill loop paused by authorization fence [session={}, tool={}]",
                            sessionUuid, ex.getToolName());
                    return;
                }

                firstRound = false;

                AiSession afterTurn = sessionRepository.get(sessionUuid);
                if (afterTurn == null) return;
                if (afterTurn.status() == AiSessionStatus.AWAITING_INPUT) {
                    log.info("Skill loop awaiting authorization [session={}]", sessionUuid);
                    return;
                }
                if (afterTurn.status() == AiSessionStatus.COMPLETED) {
                    log.info("Skill loop complete via FINISHED_TURN [session={}]", sessionUuid);
                    return;
                }
                if (executionContext.isExecutionComplete()) {
                    log.info("Skill loop completion flag detected [session={}]", sessionUuid);
                    return;
                }
            }
        } finally {
            if (sessionToolStore != null) {
                sessionToolStore.clearSession(sessionUuid);
            }
            executionContext.clear();
            ToolExecutionContext.clear();
        }
    }
}
