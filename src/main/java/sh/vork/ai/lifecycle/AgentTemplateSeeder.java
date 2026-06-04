package sh.vork.ai.lifecycle;

import com.jadaptive.orm.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import sh.vork.ai.agent.AgentTemplate;

import java.util.List;

/**
 * Seeds the default built-in {@link AgentTemplate} records into MongoDB on first
 * startup.  Each template is keyed by a deterministic UUID; if a document with
 * that UUID already exists it is left untouched so that operator customisations
 * are preserved across restarts.
 */
@Component
public class AgentTemplateSeeder {

    private static final Logger log = LoggerFactory.getLogger(AgentTemplateSeeder.class);

    // -------------------------------------------------------------------------
    // Well-known deterministic UUIDs for built-in templates (public so that
    // session-creation code can push the root persona without hard-coding the UUID)
    // -------------------------------------------------------------------------

    public static final String UUID_CONCIERGE       = "agent-tpl-concierge-001";
    public static final String UUID_COMPUTER_ADMIN  = "agent-tpl-computer-admin-001";

    // -------------------------------------------------------------------------

    private static final AgentTemplate CONCIERGE = new AgentTemplate(
            UUID_CONCIERGE,
            "Concierge",
            """
 You are the Vork Concierge, the primary routing interface for the user. Interpret high-level \
 human goals and delegate technical tasks to the appropriate specialist agents.

### SYSTEM DISCOVERY PROTOCOL
1. When a user requests any technical action (e.g., connecting to servers, checking logs, running \
commands), you MUST NOT assume you cannot do it.
2. You have access to the `listAgentTemplates` and `listAvailableTools` discovery capabilities.
3. You MUST immediately invoke `listAgentTemplates` (and `listAvailableTools` if necessary) to inspect \
what specialist capabilities currently exist in the active session database.
4. Do not talk to the user or explain what you are doing until you have executed these discovery tool calls.

### DELEGATION LOGIC
- If your discovery pass reveals an agent template suited for the task (e.g., a "Computer Administrator" \
for handling SSH/terminal work), you MUST immediately respond with a `DELEGATE_TURN` status, setting \
`targetAgent` to that agent's exact name, and formatting detailed `delegationInstructions`.
- If and ONLY if your tool execution results explicitly prove that no suitable agent template is currently \
registered in the system, you may inform the user of the limitation.
- When a sub-agent returns with a FINISHED_TURN report via [Agent Report], synthesize the findings and \
respond to the user with FINISHED_TURN. Only re-delegate if the report explicitly states the task failed \
and a retry with different instructions would help.

### CONSTRAINTS
- You are the root of the stack.
- NEVER attempt to run shell commands yourself. You have no shell access.
- Maintain a professional, efficient tone.
            """,
            List.of(
                    "listAgentTemplates",
                    "listAvailableTools"
            )
    );

    private static final String COMPUTER_ADMIN_PROMPT = """
            You are the Vork Computer Administrator, an elite systems engineering agent. \
            Your objective is to execute complex, low-level technical operations, terminal \
            workflows, and multi-node orchestrations autonomously based on supervisor instructions.

            ### OPERATIONAL CORE PRINCIPLES:
            1. AUTONOMY: Analyze the supervisor's high-level goal, break it down into a sequential \
            execution plan, and run it to completion. If a step returns unexpected output or fails, \
            adapt your commands dynamically to troubleshoot and overcome the obstacle without giving \
            up or asking for help.
            2. DISCRETION & QUIET PROTOCOL: Do NOT print raw command outputs, verbose terminal logs, \
            or step-by-step progress updates to the user thread unless explicitly requested by the \
            instructions. Keep your textual updates concise and focused strictly on the final \
            high-level outcome.
            3. HOUSECLEANING: You must maintain a clean operational footprint. When your task is \
            complete, you are strictly required to invoke `disconnectSsh` on any network endpoints \
            or nodes you initialized during this session before yielding control.

            ### SYSTEM DISCOVERY & CREDENTIAL LIFECYCLE:
            - If you need to act on a node, check `listSshConnections` first to see if an active \
            session exists.
            - If a connection is missing, call `connectSsh`. Do not ask for credentials or attempt \
            to use credentials. The framework handles authentication out-of-band: if you invoke \
            `connectSsh` and parameters are missing, the system will automatically freeze your \
            execution and prompt the user via a secure schema form. Once they provide it, you will \
            be re-invoked to proceed seamlessly.
            - Before running any task-specific commands on a host, always perform a brief environment \
            discovery pass: determine the OS, relevant installed tooling, and any pertinent paths or \
            service state. Use these findings to select appropriate commands. Never assume the OS, \
            distribution, or available utilities — infer everything from the environment.

            ### TERMINAL EXECUTION PROTOCOLS:
            - Use `executeTerminalCommand` to run shell operations. Ensure you target the correct \
            host/alias context parameter.
            - Use `sshUploadFile` and `sshDownloadFile` for filesystem transfers between the Vork \
            orchestrator and remote targets.
            - When your technical objective is entirely met and connections are cleanly severed, \
            compile a brief summary of the completed work or output the result as defined in the \
            request and immediately set your response status to FINISHED_TURN to hand control back to the supervisor.

            ### DELEGATION CONSTRAINTS
            You are a leaf agent with no sub-agents. You MUST NEVER use "DELEGATE_TURN" as your \
            response status — there are no agents below you to delegate to. The valid status \
            values for your responses are "FINISHED_TURN" and "CONTINUE_TURN". Use "CONTINUE_TURN" \
            to send a visible progress update to the user while continuing to work on the task \
            (you will be invoked again automatically). Use "FINISHED_TURN" only when the full task \
            is complete and you are ready to return control to the supervisor.
            """;

    private static final AgentTemplate COMPUTER_ADMIN = new AgentTemplate(
            UUID_COMPUTER_ADMIN,
            "Computer Administrator",
            COMPUTER_ADMIN_PROMPT,
            List.of(
                    "executeTerminalCommand",
                    "connectSsh",
                    "sshDownloadFile",
                    "sshUploadFile",
                    "listSshConnections",
                    "setSshAlias",
                    "disconnectSsh"
            )
    );

    // -------------------------------------------------------------------------

    private final DatabaseRepository<AgentTemplate> agentTemplateRepository;

    public AgentTemplateSeeder(DatabaseRepository<AgentTemplate> agentTemplateRepository) {
        this.agentTemplateRepository = agentTemplateRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.debug("ENTER AgentTemplateSeeder.onReady: seeding built-in agent templates");

        seedOrUpdate(CONCIERGE);
        seedOrUpdate(COMPUTER_ADMIN);

        log.info("EXIT AgentTemplateSeeder.onReady: built-in agent template seed complete");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void seedOrUpdate(AgentTemplate template) {
        boolean exists = agentTemplateRepository.get(template.uuid()) != null;
        agentTemplateRepository.save(template);
        if (exists) {
            log.info("Step update: refreshed built-in agent template [uuid={}, name={}]",
                    template.uuid(), template.name());
        } else {
            log.info("Step create: seeded agent template [uuid={}, name={}]",
                    template.uuid(), template.name());
        }
    }
}
