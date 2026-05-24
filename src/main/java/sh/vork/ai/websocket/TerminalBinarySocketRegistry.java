package sh.vork.ai.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
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
    private final Map<String, PendingBinaryBuffer> pendingByStream = new ConcurrentHashMap<>();
    private final Map<String, Object> sendLocksBySession = new ConcurrentHashMap<>();

    private static final int MAX_PENDING_BYTES_PER_STREAM = 512 * 1024;

    public void addSession(WebSocketSession session) {
        String key = keyOf(session);
        if (key == null) {
            return;
        }
        activeSessions.put(key, session);
        sendLocksBySession.computeIfAbsent(key, k -> new Object());
        flushPending(key, session);
    }

    public void removeSession(WebSocketSession session) {
        String key = keyOf(session);
        if (key == null) {
            return;
        }
        activeSessions.remove(key);
        sendLocksBySession.remove(key);
    }

    public void broadcast(String sessionUuid, String terminalId, byte[] data) {
        if (sessionUuid == null || terminalId == null || data == null || data.length == 0) {
            return;
        }
        String targetPrefix = sessionUuid + "|" + terminalId;
        String streamKey = targetPrefix;

        // Preserve byte ordering strictly: if we already have pending bytes for this stream,
        // flush those first and keep buffering new bytes until backlog is cleared.
        if (hasPending(streamKey)) {
            WebSocketSession primary = activeSessions.get(streamKey);
            if (primary != null && primary.isOpen()) {
                flushPending(streamKey, primary);
            }
            if (hasPending(streamKey)) {
                enqueuePending(streamKey, data);
                return;
            }
        }

        boolean delivered = false;
        
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
            String sessionKey = entry.getKey();
            if (!sessionKey.equals(targetPrefix) || ws == null || !ws.isOpen()) {
                continue;
            }
            if (sendBinarySafely(sessionKey, ws, data)) {
                delivered = true;
            }
        }

        if (!delivered) {
            enqueuePending(targetPrefix, data);
        }
    }

    private boolean hasPending(String streamKey) {
        PendingBinaryBuffer pending = pendingByStream.get(streamKey);
        return pending != null && pending.chunkCount() > 0;
    }

    private void enqueuePending(String streamKey, byte[] data) {
        PendingBinaryBuffer pending = pendingByStream.computeIfAbsent(streamKey,
                k -> new PendingBinaryBuffer(MAX_PENDING_BYTES_PER_STREAM));
        pending.add(data);
        log.debug("Buffered terminal chunk [stream={}, chunks={}, bytes={}]",
            streamKey, pending.chunkCount(), pending.totalBytes());
    }

    private void flushPending(String streamKey, WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        Object lock = sendLocksBySession.computeIfAbsent(streamKey, k -> new Object());
        int flushedChunks = 0;
        int flushedBytes = 0;

        synchronized (lock) {
            if (session == null || !session.isOpen()) {
                return;
            }
        PendingBinaryBuffer pending = pendingByStream.remove(streamKey);
        if (pending == null) {
            return;
        }
        Deque<byte[]> drained = pending.drain();
        while (!drained.isEmpty()) {
            byte[] chunk = drained.removeFirst();
            if (sendBinaryUnsafe(streamKey, session, chunk)) {
                flushedChunks += 1;
                flushedBytes += chunk.length;
            } else {
                // Put current and remaining data back so it can be retried by a later connection.
                enqueuePending(streamKey, chunk);
                while (!drained.isEmpty()) {
                    enqueuePending(streamKey, drained.removeFirst());
                }
                break;
            }
        }
        }
        if (flushedChunks > 0) {
            log.info("Flushed buffered terminal output [stream={}, chunks={}, bytes={}]",
                    streamKey, flushedChunks, flushedBytes);
        }
    }

    private boolean sendBinarySafely(String sessionKey, WebSocketSession session, byte[] data) {
        Object lock = sendLocksBySession.computeIfAbsent(sessionKey, k -> new Object());
        synchronized (lock) {
            return sendBinaryUnsafe(sessionKey, session, data);
        }
    }

    private boolean sendBinaryUnsafe(String sessionKey, WebSocketSession session, byte[] data) {
            if (session == null || !session.isOpen()) {
                return false;
            }
            try {
                session.sendMessage(new BinaryMessage(ByteBuffer.wrap(data)));
                return true;
            } catch (IOException | IllegalStateException ex) {
                log.debug("Failed to send terminal binary chunk [stream={}]: {}", sessionKey, ex.getMessage());
                return false;
            }
    }

    private static final class PendingBinaryBuffer {
        private final int maxBytes;
        private final Deque<byte[]> chunks = new ArrayDeque<>();
        private int totalBytes;

        private PendingBinaryBuffer(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        private synchronized void add(byte[] data) {
            byte[] copy = java.util.Arrays.copyOf(data, data.length);
            chunks.addLast(copy);
            totalBytes += copy.length;

            while (totalBytes > maxBytes && !chunks.isEmpty()) {
                byte[] removed = chunks.removeFirst();
                totalBytes -= removed.length;
            }
        }

        private synchronized Deque<byte[]> drain() {
            Deque<byte[]> drained = new ArrayDeque<>(chunks);
            chunks.clear();
            totalBytes = 0;
            return drained;
        }

        private synchronized int chunkCount() {
            return chunks.size();
        }

        private synchronized int totalBytes() {
            return totalBytes;
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
