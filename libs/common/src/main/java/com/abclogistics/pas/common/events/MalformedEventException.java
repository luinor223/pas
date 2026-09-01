package com.abclogistics.pas.common.events;

/**
 * A record that can never be processed, however often it is redelivered — a missing dedup key, a
 * value that is not JSON. Retrying it would block the partition for every document that shares it,
 * so each consumer's error handler routes it straight to {@code <topic>.DLT} without backoff
 * (registry §4, seq-02(e)). Anything else — a database down, identity unreachable — is transient
 * and retried.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }
}
