package sh.vork.ai.security;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Transitional pass-through advisor.
 *
 * <p>Authorisation interception now happens at tool-execution time via
 * {@link SecuredToolCallback}. This advisor remains as a no-op to keep wiring
 * compatible while avoiding duplicate post-call checks.
 */
@Component
public class AsynchronousAuthorizationAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(request);
    }

    @Override
    public String getName() {
        return "AsynchronousAuthorizationAdvisor";
    }

    /** Keep execution order unchanged for compatibility. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
