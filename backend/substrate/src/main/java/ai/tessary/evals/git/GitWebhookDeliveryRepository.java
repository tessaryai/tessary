// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GitWebhookDeliveryRepository {

    private final JdbcClient jdbc;

    public GitWebhookDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record a delivery if unseen. Returns true when this is the first time we've
     * seen {@code deliveryId} (caller should process it); false when it's a
     * duplicate redelivery (caller should no-op). Idempotent via the PK.
     */
    public boolean recordIfNew(String deliveryId, String provider, String eventType) {
        int inserted = jdbc.sql("""
            INSERT INTO git_webhook_delivery (delivery_id, provider, event_type, received_at, status)
            VALUES (:id, :prov, :evt, :now, 'received')
            ON CONFLICT (delivery_id) DO NOTHING
            """)
                .param("id", deliveryId)
                .param("prov", provider)
                .param("evt", eventType)
                .param("now", Instant.now().toString())
                .update();
        return inserted > 0;
    }
}
