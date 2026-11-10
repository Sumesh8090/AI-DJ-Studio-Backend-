package com.djstudio.security;

import com.djstudio.domain.User;
import com.djstudio.repo.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from its Bearer access token.
 *
 * <p>The user (and therefore the role) is re-read from the database, so a demotion or deactivation takes
 * effect immediately instead of when the access token expires. A bad token does not throw here: the reason is
 * stored on the request and the entry point turns it into a 401 if the endpoint requires authentication.
 *
 * <p>Deliberately NOT a Spring bean: a Filter bean would also be registered as a servlet filter, running before
 * Spring Security and having its authentication wiped. {@code SecurityConfig} adds it to the chain directly.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "djstudio.auth.error";

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            try {
                Claims claims = jwtService.parse(token, JwtService.TokenType.ACCESS);
                User user = users.findById(claims.getSubject()).orElse(null);
                if (user == null || !user.isActive()) {
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, "User no longer exists or is disabled.");
                } else {
                    UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail(), user.getRole());
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase())));
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                    MDC.put("userId", user.getId());
                }
            } catch (JwtService.TokenException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
