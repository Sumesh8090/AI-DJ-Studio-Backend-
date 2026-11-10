package com.djstudio.ws;

import com.djstudio.room.SlidingWindowLimiter;
import org.springframework.web.socket.WebSocketSession;

/** One live WebSocket connection (a user may have several). All fields are confined to the room runtime's lock. */
final class Conn {

    final String id;
    final String userId;
    final WebSocketSession session;      // a ConcurrentWebSocketSessionDecorator: safe to send from any thread
    volatile long tokenExpiresAtMs;
    final SlidingWindowLimiter generalLimiter = new SlidingWindowLimiter(20, 1_000);   // 20 commands / second
    final SlidingWindowLimiter chatLimiter = new SlidingWindowLimiter(5, 5_000);       // 5 chat messages / 5 s
    final SlidingWindowLimiter aiLimiter = new SlidingWindowLimiter(5, 30_000);        // 5 AI questions / 30 s

    Conn(String id, String userId, WebSocketSession session, long tokenExpiresAtMs) {
        this.id = id;
        this.userId = userId;
        this.session = session;
        this.tokenExpiresAtMs = tokenExpiresAtMs;
    }
}
