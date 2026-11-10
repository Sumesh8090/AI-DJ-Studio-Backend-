package com.djstudio.ws;

import com.djstudio.core.Role;
import com.djstudio.domain.LoungeParticipant;
import com.djstudio.domain.User;
import com.djstudio.repo.LoungeParticipantRepository;
import com.djstudio.repo.UserRepository;
import com.djstudio.security.JwtService;
import io.jsonwebtoken.Claims;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authenticates the WebSocket handshake. Nothing is upgraded unless the access token is valid, the account is
 * active and the user is a current participant of the lounge in the URL (/ws/lounges/{loungeId}).
 *
 * <p>Browsers cannot set an Authorization header on a WebSocket, so the token may be sent as {@code ?token=}; native
 * clients can use the header. Query strings can end up in proxy logs, so tokens are short-lived (15 min) and the
 * connection is kept alive with {@code auth.refresh} commands rather than by long-lived URL tokens.
 */
@Component
class JwtHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_USER_ID = "userId";
    static final String ATTR_NAME = "name";
    static final String ATTR_GLOBAL_ROLE = "globalRole";
    static final String ATTR_LOUNGE_ROLE = "loungeRole";
    static final String ATTR_MUTED = "muted";
    static final String ATTR_LOUNGE_ID = "loungeId";
    static final String ATTR_TOKEN_EXP = "tokenExp";

    private final JwtService jwt;
    private final UserRepository users;
    private final LoungeParticipantRepository participants;

    JwtHandshakeInterceptor(JwtService jwt, UserRepository users, LoungeParticipantRepository participants) {
        this.jwt = jwt;
        this.users = users;
        this.participants = participants;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String loungeId = loungeId(request.getURI());
        String token = token(request);
        if (loungeId == null || token == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Claims claims;
        try {
            claims = jwt.parse(token, JwtService.TokenType.ACCESS);
        } catch (JwtService.TokenException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Optional<User> user = users.findById(claims.getSubject()).filter(User::isActive);
        if (user.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Optional<LoungeParticipant> member = participants.findByLoungeIdAndUserId(loungeId, user.get().getId())
                .filter(p -> p.getLeftAt() == null);
        boolean admin = "admin".equals(user.get().getRole());
        if (member.isEmpty() && !admin) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(ATTR_USER_ID, user.get().getId());
        attributes.put(ATTR_NAME, user.get().getDisplayName());
        attributes.put(ATTR_GLOBAL_ROLE, user.get().getRole());          // re-read from the DB, not from the token
        attributes.put(ATTR_LOUNGE_ROLE, member.map(LoungeParticipant::getLoungeRole).orElse(Role.LISTENER.value()));
        attributes.put(ATTR_MUTED, member.map(LoungeParticipant::isMuted).orElse(false));
        attributes.put(ATTR_LOUNGE_ID, loungeId);
        attributes.put(ATTR_TOKEN_EXP, claims.getExpiration().getTime());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
        // nothing to do
    }

    static String loungeId(URI uri) {
        String path = uri.getPath();
        String prefix = "/ws/lounges/";
        int i = path.indexOf(prefix);
        if (i < 0) {
            return null;
        }
        String id = path.substring(i + prefix.length());
        return id.isEmpty() || id.contains("/") ? null : id;
    }

    private static String token(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        if (request instanceof ServletServerHttpRequest servlet) {
            String q = servlet.getServletRequest().getParameter("token");
            return q == null || q.isBlank() ? null : q;
        }
        return null;
    }
}
