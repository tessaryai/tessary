// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one {@code telemetry_instance} row, in Postgres rather than a file (see the {@code 0014-telemetry-install.sql}
 * header). Callers must check {@link TelemetryProperties#isEnabled()} first: minting is itself a side effect.
 */
@Component
public class InstanceIdRepository {

    private final JdbcClient jdbc;

    public InstanceIdRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The SELECT is only an optimization. The PK sits on a fixed {@code singleton} column, so racing replicas' INSERTs
     * collide and {@code ON CONFLICT (singleton) DO NOTHING} converges on one id.
     */
    public String get() {
        List<String> existing = jdbc.sql("SELECT instance_id FROM telemetry_instance LIMIT 1")
                .query(String.class)
                .list();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String candidate = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO telemetry_instance (singleton, instance_id, created_at)"
                        + " VALUES (true, :id, :created) ON CONFLICT (singleton) DO NOTHING")
                .param("id", candidate)
                .param("created", Instant.now().toString())
                .update();
        return jdbc.sql("SELECT instance_id FROM telemetry_instance LIMIT 1")
                .query(String.class)
                .single();
    }

    /**
     * 0 for the first ping, then incremented across restarts and replicas. One {@code UPDATE ... RETURNING}, so
     * concurrent pings never share a value. Call {@link #get} first.
     */
    public long nextPingSeq() {
        return jdbc.sql("UPDATE telemetry_instance SET ping_seq = ping_seq + 1 RETURNING ping_seq - 1")
                .query(Long.class)
                .single();
    }
}
