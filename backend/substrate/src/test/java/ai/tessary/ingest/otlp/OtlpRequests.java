// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import ai.tessary.ingest.GenAiAttributes;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;

final class OtlpRequests {

    private OtlpRequests() {}

    static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value).build())
                .build();
    }

    static Span chatSpan(byte id) {
        return Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {id}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .build();
    }

    static ExportTraceServiceRequest request(Span... spans) {
        ScopeSpans.Builder scope = ScopeSpans.newBuilder();
        for (Span s : spans) scope.addSpans(s);
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                        ResourceSpans.newBuilder().addScopeSpans(scope).build())
                .build();
    }
}
