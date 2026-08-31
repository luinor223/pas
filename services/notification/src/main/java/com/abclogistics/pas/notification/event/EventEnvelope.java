package com.abclogistics.pas.notification.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The envelope every {@code pas.events} record carries (registry §4). {@code payload} stays a raw
 * map: notification reads a handful of recipient keys out of it and interprets nothing else.
 */
public record EventEnvelope(UUID eventId, String eventType, Instant occurredAt,
                            UUID actorId, String actorName,
                            String documentType, UUID documentId,
                            Map<String, Object> payload) { }
