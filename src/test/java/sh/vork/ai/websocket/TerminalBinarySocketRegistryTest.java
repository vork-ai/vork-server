package sh.vork.ai.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

class TerminalBinarySocketRegistryTest {

    @Test
    void buffersChunksUntilSessionIsRegisteredThenFlushesInOrder() throws Exception {
        TerminalBinarySocketRegistry registry = new TerminalBinarySocketRegistry();

        byte[] first = "first\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second\n".getBytes(StandardCharsets.UTF_8);

        registry.broadcast("sess-1", "term-1", first);
        registry.broadcast("sess-1", "term-1", second);

        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sessionUuid", "sess-1");
        attrs.put("terminalId", "term-1");
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);

        registry.addSession(session);

        ArgumentCaptor<BinaryMessage> msgCaptor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(session, times(2)).sendMessage(msgCaptor.capture());

        byte[] sent0 = msgCaptor.getAllValues().get(0).getPayload().array();
        byte[] sent1 = msgCaptor.getAllValues().get(1).getPayload().array();
        assertArrayEquals(first, sent0);
        assertArrayEquals(second, sent1);
    }

    @Test
    void sendsDirectlyWhenSessionAlreadyConnected() throws Exception {
        TerminalBinarySocketRegistry registry = new TerminalBinarySocketRegistry();

        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sessionUuid", "sess-2");
        attrs.put("terminalId", "term-2");
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);

        registry.addSession(session);

        byte[] payload = "live\n".getBytes(StandardCharsets.UTF_8);
        registry.broadcast("sess-2", "term-2", payload);

        verify(session, times(1)).sendMessage(any(BinaryMessage.class));
    }

    @Test
    void illegalStateDuringSendIsRecoveredByBufferingAndLaterFlush() throws Exception {
        TerminalBinarySocketRegistry registry = new TerminalBinarySocketRegistry();

        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sessionUuid", "sess-3");
        attrs.put("terminalId", "term-3");
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);
        when(session.isOpen()).thenReturn(true);

        registry.addSession(session);

        byte[] payload = "chunk\n".getBytes(StandardCharsets.UTF_8);
        org.mockito.Mockito.doThrow(new IllegalStateException("BINARY_PARTIAL_WRITING"))
                .when(session)
                .sendMessage(any(BinaryMessage.class));

        assertDoesNotThrow(() -> registry.broadcast("sess-3", "term-3", payload));

        reset(session);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);
        registry.addSession(session);

        verify(session, times(1)).sendMessage(any(BinaryMessage.class));
    }

    @Test
    void preservesOrderingAcrossTransientSendFailure() throws Exception {
        TerminalBinarySocketRegistry registry = new TerminalBinarySocketRegistry();

        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sessionUuid", "sess-4");
        attrs.put("terminalId", "term-4");
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);

        registry.addSession(session);

        byte[] first = "preview".getBytes(StandardCharsets.UTF_8);
        byte[] second = "import".getBytes(StandardCharsets.UTF_8);

        org.mockito.Mockito.doThrow(new IllegalStateException("BINARY_PARTIAL_WRITING"))
                .doNothing()
                .doNothing()
                .when(session)
                .sendMessage(any(BinaryMessage.class));

        registry.broadcast("sess-4", "term-4", first);
        registry.broadcast("sess-4", "term-4", second);

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(session, times(3)).sendMessage(captor.capture());

        byte[] sentSecondCall = captor.getAllValues().get(1).getPayload().array();
        byte[] sentThirdCall = captor.getAllValues().get(2).getPayload().array();
        assertArrayEquals(first, sentSecondCall);
        assertArrayEquals(second, sentThirdCall);
    }
}
