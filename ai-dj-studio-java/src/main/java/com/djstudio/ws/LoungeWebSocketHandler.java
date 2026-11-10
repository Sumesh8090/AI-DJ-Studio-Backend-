package com.djstudio.ws;

import com.djstudio.config.WsProperties;
import com.djstudio.core.Role;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Thin adapter between Spring's WebSocket callbacks and {@link RoomService}. */
@Component
class LoungeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LoungeWebSocketHandler.class);
    private static final String ATTR_CONN = "conn";

    private final RoomService rooms;
    private final WsProperties props;

    LoungeWebSocketHandler(RoomService rooms, WsProperties props) {
        this.rooms = rooms;
        this.props = props;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        Map<String, Object> a = raw.getAttributes();
        String loungeId = (String) a.get(JwtHandshakeInterceptor.ATTR_LOUNGE_ID);
        String userId = (String) a.get(JwtHandshakeInterceptor.ATTR_USER_ID);
        // The decorator serialises sends and enforces a time/buffer limit, so one slow client cannot stall a room.
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, props.sendTimeLimitMs(),
                props.sendBufferBytes());
        Conn conn = new Conn(UUID.randomUUID().toString(), userId, session, (Long) a.get(JwtHandshakeInterceptor.ATTR_TOKEN_EXP));
        a.put(ATTR_CONN, conn);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", loungeId)) {
            boolean ok = rooms.connect(conn, loungeId,
                    (String) a.get(JwtHandshakeInterceptor.ATTR_NAME),
                    Role.fromValue((String) a.get(JwtHandshakeInterceptor.ATTR_GLOBAL_ROLE)).orElse(Role.LISTENER),
                    Role.fromValue((String) a.get(JwtHandshakeInterceptor.ATTR_LOUNGE_ROLE)).orElse(Role.LISTENER),
                    Boolean.TRUE.equals(a.get(JwtHandshakeInterceptor.ATTR_MUTED)));
            if (!ok) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("lounge_not_found"));
                return;
            }
            log.atInfo().addKeyValue("loungeId", loungeId).addKeyValue("userId", userId).log("ws.connected");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        Conn conn = (Conn) raw.getAttributes().get(ATTR_CONN);
        if (conn == null) {
            return;
        }
        String loungeId = (String) raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_LOUNGE_ID);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", loungeId)) {
            rooms.handle(conn, loungeId, message.getPayload());
        } catch (RuntimeException ex) {
            log.atError().addKeyValue("loungeId", loungeId).setCause(ex).log("ws.command_failed");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Conn conn = (Conn) raw.getAttributes().get(ATTR_CONN);
        if (conn == null) {
            return;
        }
        String loungeId = (String) raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_LOUNGE_ID);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", loungeId)) {
            rooms.disconnect(conn, loungeId);
            log.atInfo().addKeyValue("loungeId", loungeId).addKeyValue("userId", conn.userId)
                    .addKeyValue("code", status.getCode()).log("ws.disconnected");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.atWarn().log("ws.transport_error: " + exception.getClass().getSimpleName());
    }
}
