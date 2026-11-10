package com.djstudio.ws;

import com.djstudio.config.WsProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket endpoint: {@code /ws/lounges/{loungeId}?token=<access token>}.
 *
 * <p>Deviation from the PRD's suggestion of WebFlux: the rest of the service is Spring MVC (JPA is blocking), so the
 * gateway uses Spring's servlet WebSocket support. Per-room work is tiny and in memory; blocking I/O never happens
 * under a room lock.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
class WebSocketConfig implements WebSocketConfigurer {

    private final LoungeWebSocketHandler handler;
    private final JwtHandshakeInterceptor interceptor;
    private final WsProperties props;

    WebSocketConfig(LoungeWebSocketHandler handler, JwtHandshakeInterceptor interceptor, WsProperties props) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> origins = props.origins();
        var reg = registry.addHandler(handler, "/ws/lounges/*").addInterceptors(interceptor);
        if (!origins.isEmpty()) {
            reg.setAllowedOrigins(origins.toArray(new String[0]));
        } // empty = Spring's default: same-origin only (never "*")
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(props.maxMessageBytes());   // oversized frames are rejected by the container
        c.setMaxSessionIdleTimeout(5 * 60_000L);
        return c;
    }
}
