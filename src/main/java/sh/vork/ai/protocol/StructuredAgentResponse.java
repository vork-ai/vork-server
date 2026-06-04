package sh.vork.ai.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Structured output contract for every AI generation turn in the Vork multi-agent loop.
 *
 * <p>Every agent response in the orchestration cycle <em>must</em> be valid JSON
 * matching this schema.  {@link sh.vork.ai.service.ChatService} parses the model's
 * raw text output into this record and uses {@code status} to drive the handoff
 * state machine:
 *
 * <ul>
 *   <li>{@code "FINISHED_TURN"} — the agent has completed its goal.  If the stack
 *       depth is&nbsp;&gt;&nbsp;1 the orchestrator pops the agent and feeds
 *       {@code textResponse} back to the supervisor; otherwise {@code textResponse}
 *       is returned to the user as the final reply.</li>
 *   <li>{@code "DELEGATE_TURN"} — the agent needs to hand off work to a
 *       sub-agent.  The orchestrator resolves {@code targetAgent} by name,
 *       pushes the template onto the session stack, broadcasts {@code textResponse}
 *       to the user, and starts a new generation pass using
 *       {@code delegationInstructions} as the prompt.</li>
 * </ul>
 *
 * @param status                 {@code "FINISHED_TURN"} or {@code "DELEGATE_TURN"}
 * @param textResponse           human-readable progress or result message; surfaced
 *                               to the user on handoff and returned as the final
 *                               reply when the root agent finishes
 * @param targetAgent            exact display name of the
 *                               {@link sh.vork.ai.agent.AgentTemplate} to delegate
 *                               to; {@code null} when {@code FINISHED_TURN}
 * @param delegationInstructions self-contained task parameters for the sub-agent;
 *                               {@code null} when {@code FINISHED_TURN}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredAgentResponse(
        String status,
        String textResponse,
        String targetAgent,
        String delegationInstructions
) {}
