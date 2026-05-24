package sh.vork.ai.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class TerminalBinarySocketRegistry {

    private static final Logger log = LoggerFactory.getLogger(TerminalBinarySocketRegistry.class);

    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, TerminalStreamStats> streamStats = new ConcurrentHashMap<>();

    public void addSession(WebSocketSession session) {
        String key = keyOf(session);
        if (key == null) {
            return;
        }
        activeSessions.put(key, session);
    }

    public void removeSession(WebSocketSession session) {
        String key = keyOf(session);
        if (key == null) {
            return;
        }
        activeSessions.remove(key);
    }

    public void broadcast(String sessionUuid, String terminalId, byte[] data) {
        if (sessionUuid == null || terminalId == null || data == null || data.length == 0) {
            return;
        }
        String targetPrefix = sessionUuid + "|" + terminalId;
        String streamKey = targetPrefix;
        
        // Track stats
        TerminalStreamStats stats = streamStats.computeIfAbsent(streamKey, k -> new TerminalStreamStats());
        stats.recordFrame(data.length);
        
        // Periodic logging (every 100 frames)
        if (stats.frameCount % 100 == 0) {
            long avgFrameSize = stats.totalBytes / stats.frameCount;
            long elapsedMs = stats.getElapsedMs();
            long throughputKbps = elapsedMs > 0 ? (stats.totalBytes * 8) / elapsedMs : 0;
            log.info("Terminal stream stats [session={}, terminal={}]: frames={}, avgSize={} bytes, throughput={}Kbps, minFrame={}, maxFrame={}",
                    sessionUuid, terminalId, stats.frameCount, avgFrameSize, throughputKbps, 
                    stats.minFrameSize, stats.maxFrameSize);
        }
        
        for (Map.Entry<String, WebSocketSession> entry : activeSessions.entrySet()) {
            WebSocketSession ws = entry.getValue();
            if (!entry.getKey().startsWith(targetPrefix) || ws == null || !ws.isOpen()) {
                continue;
            }
            try {
                ws.sendMessage(new BinaryMessage(ByteBuffer.wrap(data)));
            } catch (IOException ex) {
                log.debug("Failed to send terminal binary chunk [session={}, terminal={}]: {}",
                        sessionUuid, terminalId, ex.getMessage());
            }
        }
    }

    private static String keyOf(WebSocketSession session) {
        if (session == null) {
            return null;
        }
        Object sessionUuid = session.getAttributes().get("sessionUuid");
        Object terminalId = session.getAttributes().get("terminalId");
        if (sessionUuid == null || terminalId == null) {
            return null;
        }
        return String.valueOf(sessionUuid) + "|" + String.valueOf(terminalId);
    }

    private static class TerminalStreamStats {
        long startTime = System.currentTimeMillis();
        long frameCount = 0;
        long totalBytes = 0;
        long minFrameSize = Long.MAX_VALUE;
        long maxFrameSize = 0;

        void recordFrame(int size) {
            frameCount++;
            totalBytes += size;
            minFrameSize = Math.min(minFrameSize, size);
            maxFrameSize = Math.max(maxFrameSize, size);
        }

        long getElapsedMs() {
            return System.currentTimeMillis() - startTime;
        }
    }
}
