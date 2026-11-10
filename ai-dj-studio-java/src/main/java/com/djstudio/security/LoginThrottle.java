package com.djstudio.security;

import com.djstudio.web.ApiException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Brute-force protection for login. Two independent limits on FAILED attempts within 15 minutes:
 * 5 per (email + client address), so one attacker cannot lock a victim out from elsewhere, and 60 per client address,
 * which slows down password spraying across many emails. A successful login clears the account counter.
 *
 * <p>The client address is the servlet remote address. Behind a reverse proxy, set
 * {@code server.forward-headers-strategy=native} so it reflects the real client rather than the proxy.
 */
@Component
public class LoginThrottle {

    private static final long WINDOW_MS = 15 * 60_000L;

    private final FailureLimiter perAccount = new FailureLimiter(5, WINDOW_MS, 50_000);
    private final FailureLimiter perAddress = new FailureLimiter(60, WINDOW_MS, 50_000);

    /** Throws 429 when this email/address is currently blocked. */
    public void check(String email, String address) {
        long now = System.currentTimeMillis();
        long wait = Math.max(perAccount.retryAfterSeconds(accountKey(email, address), now),
                perAddress.retryAfterSeconds(address, now));
        if (wait > 0) {
            throw new ApiException(429, "too_many_attempts",
                    "Too many failed sign-in attempts. Try again in " + wait + " seconds.");
        }
    }

    public void failed(String email, String address) {
        long now = System.currentTimeMillis();
        perAccount.recordFailure(accountKey(email, address), now);
        perAddress.recordFailure(address, now);
    }

    public void succeeded(String email, String address) {
        perAccount.clear(accountKey(email, address));
    }

    private static String accountKey(String email, String address) {
        return email.strip().toLowerCase(Locale.ROOT) + "|" + address;
    }
}
