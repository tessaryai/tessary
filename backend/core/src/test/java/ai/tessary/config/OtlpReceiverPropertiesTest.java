// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.OtlpReceiverProperties.Transport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OtlpReceiverPropertiesTest {

    /** The bug: a {@code tessary.ingest.otlp.*} override binds to the wrong field or is silently ignored. */
    @Test
    void everyKeyBindsToItsOwnField() {
        OtlpReceiverProperties p = ConfigBinding.bind(
                "tessary.ingest.otlp",
                new OtlpReceiverProperties(),
                Map.of(
                        "tessary.ingest.otlp.transport", "both",
                        "tessary.ingest.otlp.grpc-port", "14317",
                        "tessary.ingest.otlp.max-spans-per-request", "12",
                        "tessary.ingest.otlp.max-body-bytes", "13"));

        assertEquals(Transport.BOTH, p.getTransport());
        assertEquals(List.of(14317, 12, 13), List.of(p.getGrpcPort(), p.getMaxSpansPerRequest(), p.getMaxBodyBytes()));
    }

    /** The bug: a gRPC-only deployment still opens the HTTP receiver, or BOTH leaves one of them closed. */
    @ParameterizedTest
    @CsvSource({"HTTP, true, false", "GRPC, false, true", "BOTH, true, true"})
    void eachTransportServesExactlyItsReceivers(Transport transport, boolean http, boolean grpc) {
        assertEquals(List.of(http, grpc), List.of(transport.includesHttp(), transport.includesGrpc()));
    }
}
