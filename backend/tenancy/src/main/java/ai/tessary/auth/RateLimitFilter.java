// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keyed by principal rather than IP, since an identity survives an address change. The two POST credential routes have
 * no context yet, so they get a much tighter IP-keyed bucket; {@code getRemoteAddr()} is the real client because
 * production resolves Caddy's X-Forwarded-For via Tomcat {@code internal-proxies}. A full pool evicts rather than
 * refuses: a caller who can mint addresses (an IPv6 /64) would get fresh buckets anyway, so refusal would only lock out
 * legitimate new sign-ins.
 */
@Component
@Order(20) // AuthFilter is @Order(10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final double REFILL_PER_SEC = 10.0;
    private static final double BURST = 60.0;

    private static final double CREDENTIAL_REFILL_PER_SEC = 0.2;
    private static final double CREDENTIAL_BURST = 5.0;

    /**
     * A bucket at full tokens is indistinguishable from an unused one, so dropping it gives nobody back a spent
     * allowance; that makes a cap safe rather than a hole in the limit.
     */
    private static final int MAX_BUCKETS = 10_000;

    /**
     * Amortises eviction to O(1) per insert. {@code System.nanoTime()} can be coarser than insert gaps, so a pass may
     * over-evict; that only costs churn, never a limit.
     */
    private static final int EVICTION_HEADROOM = MAX_BUCKETS / 10;

    /**
     * The flood that fills a pool must not also pay O(n) per request; overshoot is bounded to one second of new keys.
     */
    private static final long SWEEP_INTERVAL_NANOS = 1_000_000_000L;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> credentialRouteBuckets = new ConcurrentHashMap<>();
    private final AtomicLong lastUserSweepNanos;
    private final AtomicLong lastCredentialSweepNanos;
    private final ObjectMapper mapper;

    private final LongSupplier nanoTime;

    @Autowired
    public RateLimitFilter(ObjectMapper mapper) {
        this(mapper, System::nanoTime);
    }

    RateLimitFilter(ObjectMapper mapper, LongSupplier nanoTime) {
        this.mapper = mapper;
        this.nanoTime = nanoTime;
        this.lastUserSweepNanos = new AtomicLong(nanoTime.getAsLong());
        this.lastCredentialSweepNanos = new AtomicLong(nanoTime.getAsLong());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getServletPath();
        if (path == null) path = req.getRequestURI();
        // The rest of /auth/** stays exempt. Guarded actuator paths are filtered, since AuthFilter (@Order(10)) already
        // requires a staff principal there; note this returns true to skip, so the guarded term must be isActuatorPath
        // && !isPublicActuatorPath.
        boolean guardedActuator = AuthFilter.isActuatorPath(path) && !AuthFilter.isPublicActuatorPath(path);
        boolean credentialRoute = isCredentialRoute(path, req.getMethod());
        // A prompt write carries up to 200 KB under an ADMIN key, so it gets the principal bucket; reads stay exempt
        // like /v1/query, guarded by the SDK cache.
        boolean promptWrite = path.startsWith("/v1/prompts") && !"GET".equals(req.getMethod());
        return !(path.startsWith("/api/")
                || path.startsWith("/mcp")
                || guardedActuator
                || credentialRoute
                || promptWrite);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        TenantContext ctx = (TenantContext) req.getAttribute(TenantContext.ATTRIBUTE);
        if (ctx == null || ctx.userId() == null) {
            // The credential routes reach here with no context, so without this arm the ctx==null fall-through below
            // would silently un-throttle them.
            String path = req.getServletPath();
            if (path == null) path = req.getRequestURI();
            if (isCredentialRoute(path, req.getMethod())) {
                if (!rejectIfExhausted(
                        req,
                        res,
                        credentialRouteBuckets,
                        lastCredentialSweepNanos,
                        "ip:" + req.getRemoteAddr(),
                        () -> new Bucket(CREDENTIAL_BURST, CREDENTIAL_REFILL_PER_SEC, nanoTime))) {
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            // Unauthenticated requests are already rejected by AuthFilter for the other paths we
            // cover; if somehow we got here without a ctx, fall through and let the next layer
            // answer.
            chain.doFilter(req, res);
            return;
        }

        // Every MCP token resolves to a user via ApiKey.principalId, so automated callers stay tied to the human who
        // issued the token.
        if (!rejectIfExhausted(
                req,
                res,
                buckets,
                lastUserSweepNanos,
                ctx.userId(),
                () -> new Bucket(BURST, REFILL_PER_SEC, nanoTime))) {
            return;
        }
        chain.doFilter(req, res);
    }

    private static boolean isCredentialRoute(String path, String method) {
        return "POST".equalsIgnoreCase(method) && ("/auth/signup".equals(path) || "/auth/login".equals(path));
    }

    private boolean rejectIfExhausted(
            HttpServletRequest req,
            HttpServletResponse res,
            ConcurrentMap<String, Bucket> pool,
            AtomicLong sweepClock,
            String key,
            java.util.function.Supplier<Bucket> newBucket)
            throws IOException {
        // A new key is the only thing that can grow the pool, so the cap is checked here. The key is admitted either
        // way.
        Bucket b = pool.get(key);
        if (b == null) {
            if (pool.size() >= MAX_BUCKETS) reclaim(pool, sweepClock, nanoTime.getAsLong());
            b = pool.computeIfAbsent(key, k -> newBucket.get());
        }
        return b.tryConsume(1.0) || reject429(res, b);
    }

    private boolean reject429(HttpServletResponse res, Bucket bucket) throws IOException {
        res.setStatus(429);
        // The bucket's own refill rate: the credential pool refills every five seconds, so "retry in one" would earn
        // four more 429s.
        res.setHeader("Retry-After", Long.toString(bucket.retryAfterSeconds()));
        res.setContentType("application/json");
        res.getWriter()
                .write(mapper.writeValueAsString(
                        Map.of("ok", false, "error", Map.of("code", "rate.limited", "message", "too many requests"))));
        return false;
    }

    /**
     * Drops full buckets first. If the pool is still full, every bucket is being spent (an address-rotating caller), so
     * evict by age: {@link Bucket#tryConsume} stamps even a rejected call, so a throttled caller has the freshest stamp
     * and cannot evict itself out of its limit.
     */
    private static void reclaim(ConcurrentMap<String, Bucket> pool, AtomicLong sweepClock, long now) {
        long last = sweepClock.get();
        if (now - last < SWEEP_INTERVAL_NANOS || !sweepClock.compareAndSet(last, now)) return;
        pool.values().removeIf(b -> b.refilledBy(now));
        // Sized from the same snapshot it indexes, so a concurrently shrinking pool cannot push the index past the
        // array.
        long[] stamps =
                pool.values().stream().mapToLong(Bucket::lastTouchedNanos).toArray();
        int excess = stamps.length - (MAX_BUCKETS - EVICTION_HEADROOM);
        if (excess <= 0) return;
        Arrays.sort(stamps);
        long coldest = stamps[excess - 1];
        pool.values().removeIf(b -> b.lastTouchedNanos() <= coldest);
        log.warn("rate limiter pool was full of active buckets; evicted {} by age (cap {})", excess, MAX_BUCKETS);
    }

    private static final class Bucket {
        private final double burst;
        private final double refillPerSec;
        private final LongSupplier nanoTime;
        private final AtomicLong stateNanos;
        private volatile double tokens;

        Bucket(double burst, double refillPerSec, LongSupplier nanoTime) {
            this.burst = burst;
            this.refillPerSec = refillPerSec;
            this.nanoTime = nanoTime;
            this.stateNanos = new AtomicLong(nanoTime.getAsLong());
            this.tokens = burst;
        }

        /**
         * Read without the lock on purpose: it decides eviction, not admission, and a stale read only keeps a bucket
         * one more sweep.
         */
        boolean refilledBy(long nowNanos) {
            return (nowNanos - stateNanos.get()) / 1_000_000_000.0 * refillPerSec >= burst;
        }

        long lastTouchedNanos() {
            return stateNanos.get();
        }

        long retryAfterSeconds() {
            return Math.max(1L, (long) Math.ceil(1.0 / refillPerSec));
        }

        synchronized boolean tryConsume(double n) {
            long now = nanoTime.getAsLong();
            long prev = stateNanos.getAndSet(now);
            double elapsedSec = (now - prev) / 1_000_000_000.0;
            tokens = Math.min(burst, tokens + elapsedSec * refillPerSec);
            if (tokens < n) return false;
            tokens -= n;
            return true;
        }
    }
}
