package org.tauasa.apps.rtlsdr.config;

import org.tauasa.apps.rtlsdr.web.IqStreamWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the {@code /ws/iq} endpoint for binary IQ-sample streaming.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final IqStreamWebSocketHandler iqHandler;

    public WebSocketConfig(IqStreamWebSocketHandler iqHandler) {
        this.iqHandler = iqHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(iqHandler, "/ws/iq")
                .setAllowedOriginPatterns("*"); // tighten in production
    }
}
