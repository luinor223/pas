package com.abclogistics.pas.common.security;

import java.util.UUID;

/**
 * The principal credited with system-initiated actions: scheduler flips, auto-applied addenda,
 * relay-driven starts with no human submitter. A real id, never null. After this, a null
 * actor_id / created_by / requested_by means missing or corrupt attribution, not "the system did
 * it". Seeded as an {@code app_user} row in identity so the identity FK and cross-service name
 * snapshots both resolve.
 */
public final class SystemActor {

    private SystemActor() { }

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String NAME = "System";
}
