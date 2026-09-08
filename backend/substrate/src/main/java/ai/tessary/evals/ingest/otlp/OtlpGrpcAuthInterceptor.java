// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.otlp;

import ai.tessary.evals.auth.BearerTokenAuthenticator;
import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.tenant.KeyScope;
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
 * Authenticates an OTLP/gRPC {@code Export} call from its request {@link Metadata}.
 *
 * <p>gRPC is not a servlet request, so {@code AuthFilter} / {@code TenantArgumentResolver} never run on this
 * path. This interceptor fills that gap: it reads the {@code authorization} metadata header, resolves it
 * through the <em>shared</em> {@link BearerTokenAuthenticator} (the same verify + project lookup the HTTP
 * receiver uses), enforces the project-scoped + write-scoped token requirement (mirroring
 * {@code OtlpTraceController}), and stashes the resolved project id in the gRPC {@link Context} for the
 * service to read. A missing/invalid token, or a non-project-scoped one, is rejected with
 * {@link Status#UNAUTHENTICATED}; a token whose family cannot write (e.g. a query-only key) is rejected with
 * {@link Status#PERMISSION_DENIED} — either way before the handler runs, so the call never reaches the
 * substrate path.
 */
@Component
public class OtlpGrpcAuthInterceptor implements ServerInterceptor {

    /** gRPC metadata key for the bearer token (lowercased by gRPC convention). */
    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** Carries the authenticated project id from this interceptor to the service handler. */
    static final Context.Key<String> PROJECT_ID = Context.key("evals.otlp.grpc.projectId");

    private final BearerTokenAuthenticator bearerAuth;

    public OtlpGrpcAuthInterceptor(BearerTokenAuthenticator bearerAuth) {
        this.bearerAuth = bearerAuth;
    }

    // ReqT/RespT are the gRPC ServerInterceptor SPI's own type-parameter names; this override must keep
    // them, so the single-uppercase-letter PMD naming rule is suppressed here rather than renaming the SPI.
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
        // The token must be project-scoped — a stock exporter pushes with a project ingest token, not a user
        // session (which carries no project). Mirrors OtlpTraceController's project-scoped-token check.
        String projectId = ctx.projectId();
        if (!ctx.isMcpToken() || projectId == null) {
            call.close(
                    Status.UNAUTHENTICATED.withDescription("OTLP ingest requires a project-scoped token"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }
        // The key family must permit writing — mirror OtlpTraceController's keyPermits(WRITE) gate so a
        // query-only key cannot ingest over gRPC (it is already rejected on the HTTP ingest path).
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
