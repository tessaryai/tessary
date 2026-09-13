// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads or mints the one {@code telemetry_install} row (contract §1: "generated once at first boot,
 * persisted locally"). Postgres, not a file on disk — see the {@code 0014-telemetry-install.sql}
 * changeset header for why a file would silently stop persisting on this stack's deploy shape.
 *
 * <p>Callers must gate on {@link TelemetryProperties#isEnabled()} BEFORE calling {@link #get}: reading
 * or minting an install id is itself a side effect (a write, the first time), and the contract's §3
 * "zero outbound calls when disabled" language is about the whole telemetry subsystem staying inert, not
 * merely about not sending — see {@code TelemetryHeartbeat}, the one caller.
 */
@Component
public class InstallIdRepository {

    private final JdbcClient jdbc;

    public InstallIdRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The install's UUID v4 identity, minting and persisting one on first call if none exists yet.
     *
     * <p>{@code SELECT} first is purely an optimization to skip minting a throwaway UUID on the
     * common (already-initialized) path — it is NOT what makes this safe under concurrent first boot.
     * The actual singleton guarantee comes from the table's primary key sitting on a fixed sentinel
     * column ({@code singleton = true}, see the {@code 0014-telemetry-install.sql} changeset), not on
     * {@code install_id} itself: two replicas racing on an empty table each mint a distinct candidate
     * UUID, but both {@code INSERT}s target the SAME primary key value, so exactly one commits and the
     * other's {@code ON CONFLICT (singleton) DO NOTHING} no-ops — every process converges on the
     * winner's id despite the race, which an {@code install_id}-keyed PK could not guarantee (distinct
     * candidate UUIDs would not collide on that key at all).
     */
    public String get() {
        List<String> existing = jdbc.sql("SELECT install_id FROM telemetry_install LIMIT 1")
                .query(String.class)
                .list();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String candidate = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO telemetry_install (singleton, install_id, created_at)"
                        + " VALUES (true, :id, :created) ON CONFLICT (singleton) DO NOTHING")
                .param("id", candidate)
                .param("created", Instant.now().toString())
                .update();
        return jdbc.sql("SELECT install_id FROM telemetry_install LIMIT 1")
                .query(String.class)
                .single();
    }

    /**
     * The {@code ping_seq} for the ping about to be sent: 0 for an install's first ping, then one more on
     * every call, across restarts and replicas (the {@code 0005-telemetry-ping-seq.sql} changeset).
     *
     * <p>One {@code UPDATE ... RETURNING}, so the read and the increment are a single statement and two
     * replicas pinging at once can never be handed the same value. Call {@link #get} first: this assumes
     * the singleton row exists and fails loudly if it does not.
     */
    public long nextPingSeq() {
        return jdbc.sql("UPDATE telemetry_install SET ping_seq = ping_seq + 1 RETURNING ping_seq - 1")
                .query(Long.class)
                .single();
    }
}
