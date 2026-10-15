// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import ai.tessary.auth.BearerTokenAuthenticator;
import ai.tessary.auth.TenantContext;
import ai.tessary.tenant.KeyScope;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Authenticates an OTLP/gRPC {@code Export} call, since {@code AuthFilter} never runs on gRPC. Uses the shared
 * {@link BearerTokenAuthenticator}, requires a project-scoped write token like {@code OtlpTraceController}, and stashes
 * the project id in the gRPC {@link Context}. Rejects with {@link Status#UNAUTHENTICATED} or
 * {@link Status#PERMISSION_DENIED} before the handler runs.
 */
@Component
public class OtlpGrpcAuthInterceptor implements ServerInterceptor {

    /** Lowercase by gRPC convention. */
    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    static final Context.Key<String> PROJECT_ID = Context.key("tessary.otlp.grpc.projectId");

    private final BearerTokenAuthenticator bearerAuth;

    public OtlpGrpcAuthInterceptor(BearerTokenAuthenticator bearerAuth) {
        this.bearerAuth = bearerAuth;
    }

    // ReqT/RespT are the SPI's own type-parameter names, so the PMD naming rule is suppressed rather than renaming
    // them.
    @Override
    @SuppressWarnings("PMD.TypeParameterNamingConventions")
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        @Nullable String authorization = headers.get(AUTHORIZATION);
        Optional<TenantContext> resolved = bearerAuth.authenticate(authorization);
        if (resolved.isEmpty()) {
            call.close(
                    Status.UNAUTHENTICATED.withDescription("OTLP ingest requires a valid bearer token"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }
        TenantContext ctx = resolved.get();
        // A stock exporter pushes with a project ingest token; a user session carries no project.
        String projectId = ctx.projectId();
        if (!ctx.isMcpToken() || projectId == null) {
            call.close(
                    Status.UNAUTHENTICATED.withDescription("OTLP ingest requires a project-scoped token"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }
        // A query-only key must not ingest over gRPC either.
        if (!ctx.keyPermits(KeyScope.WRITE)) {
            call.close(
                    Status.PERMISSION_DENIED.withDescription("OTLP ingest requires a write-scoped token"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }
        Context grpcContext = Context.current().withValue(PROJECT_ID, projectId);
        return Contexts.interceptCall(grpcContext, call, headers, next);
    }
}
