package org.tauasa.apps.rtlsdr.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.tauasa.apps.rtlsdr.service.RtlSdrService;

/**
 * WebSocket handler for the {@code /ws/iq} endpoint.
 *
 * <p>Each connecting client receives a continuous stream of raw IQ bytes (8-bit
 * unsigned, interleaved I/Q, offset binary). The same data that goes out over the
 * TCP rtl_tcp port is multiplexed here — useful for browser-based SDR UIs.
 *
 * <h2>Binary frame format</h2>
 * Identical to the rtl_tcp stream: unsigned 8-bit I/Q pairs, offset by 127.
 * One WebSocket binary frame per librtlsdr callback invocation.
 */
@Component
public class IqStreamWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(IqStreamWebSocketHandler.class);

    private final RtlSdrService sdrService;
    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    /** Registered IQ consumer — kept as a field so it can be removed on shutdown. */
    private final Consumer<byte[]> iqConsumer;

    public IqStreamWebSocketHandler(RtlSdrService sdrService) {
        this.sdrService = sdrService;
        this.iqConsumer = this::broadcast;
        sdrService.addIqConsumer(iqConsumer);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket IQ client connected: {} (total: {})",
                session.getRemoteAddress(), sessions.size());

        // Start streaming if not already running and device is open
        if (sdrService.isOpen() && !sdrService.isStreaming()) {
            sdrService.startStreaming();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket IQ client disconnected: {} — remaining: {}",
                session.getRemoteAddress(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        sessions.remove(session);
        log.warn("WebSocket transport error for {}: {}", session.getRemoteAddress(), ex.getMessage());
    }

    private void broadcast(byte[] data) {
        if (sessions.isEmpty()) return;
        BinaryMessage msg = new BinaryMessage(ByteBuffer.wrap(data));
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try { session.sendMessage(msg); }
                catch (IOException e) {
                    log.debug("Failed to send IQ to {}: {}", session.getRemoteAddress(), e.getMessage());
                    sessions.remove(session);
                }
            }
        }
    }
}
