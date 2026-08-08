package com.kholodilin.idempotency.demo.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Simulates technical infrastructure failures for demo purposes.
 *
 * <ul>
 *   <li>recipient {@code FAIL_TECH} — always fails (permanent outage);</li>
 *   <li>recipient {@code FAIL_ONCE} — fails on the first attempt per idempotency key,
 *       succeeds on retry (transient failure: demonstrates that a rollback leaves no
 *       committed record and the retry executes from scratch).</li>
 * </ul>
 */
@Component
public class FailureSimulator {

    public static final String FAIL_TECH = "FAIL_TECH";
    public static final String FAIL_ONCE = "FAIL_ONCE";

    private final Set<String> alreadyFailed = ConcurrentHashMap.newKeySet();

    public void maybeFail(String recipient, String idempotencyKey) {
        if (FAIL_TECH.equals(recipient)) {
            throw new IllegalStateException("Simulated permanent technical failure (recipient FAIL_TECH)");
        }
        if (FAIL_ONCE.equals(recipient) && alreadyFailed.add(idempotencyKey)) {
            throw new IllegalStateException("Simulated transient technical failure (recipient FAIL_ONCE)");
        }
    }
}
