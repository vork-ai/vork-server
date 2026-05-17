package sh.vork.ai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

class AsynchronousAuthorizationAdvisorTest {

    @Test
    void adviseCall_passesThroughUnchangedResponse() {
        AsynchronousAuthorizationAdvisor advisor = new AsynchronousAuthorizationAdvisor();

        ChatClientResponse expected = responseWithAssistant(new AssistantMessage("ok"));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(expected);

        ChatClientResponse actual = advisor.adviseCall(request(), chain);

        assertSame(expected, actual);
        verify(chain).nextCall(any());
    }

    @Test
    void order_isHighestPrecedence() {
        AsynchronousAuthorizationAdvisor advisor = new AsynchronousAuthorizationAdvisor();
        assertEquals(Ordered.HIGHEST_PRECEDENCE, advisor.getOrder());
        assertEquals("AsynchronousAuthorizationAdvisor", advisor.getName());
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(new Prompt(List.of(new UserMessage("test"))), Map.of());
    }

    private static ChatClientResponse responseWithAssistant(AssistantMessage assistant) {
        Generation generation = new Generation(assistant);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        return new ChatClientResponse(chatResponse, Map.of());
    }
}
