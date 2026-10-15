// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.OtlpReceiverProperties.Transport;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OtlpReceiverPropertiesTest {

    /** The bug: a gRPC-only deployment still opens the HTTP receiver, or BOTH leaves one of them closed. */
    @ParameterizedTest
    @CsvSource({"HTTP, true, false", "GRPC, false, true", "BOTH, true, true"})
    void eachTransportServesExactlyItsReceivers(Transport transport, boolean http, boolean grpc) {
        assertEquals(List.of(http, grpc), List.of(transport.includesHttp(), transport.includesGrpc()));
    }
}
