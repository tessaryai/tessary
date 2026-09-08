// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-principal in-memory token-bucket rate limiter for {@code /api/**} and
 * {@code /mcp}, plus — since #852 — the two credential-checking {@code /auth/**} routes. Runs
 * after {@link AuthFilter} so a {@link TenantContext} is already attached and we can rate-limit by
 * user id (or MCP token id for bearer-auth) rather than IP — one signed-up user could otherwise
 * hammer the single-threaded run executor from one machine, and an identity survives the client
 * changing address, which is what an abuse limit wants to key on.
 *
 * <p>The bucket is sized for normal interactive use (a few requests per
 * second sustained, short bursts higher) — well under what a real frontend
 * generates and well above any cron-style polling. Hitting the limit means
 * something automated and probably hostile.</p>
 *
 * <p><b>{@code /auth/signup} and {@code /auth/login} (#852).</b> Every other {@code /auth/**} path
 * (the OAuth GETs, {@code /auth/logout}, {@code /auth/me}, {@code /auth/link/*}) stays exempt
 * exactly as before — those either name no credential to guess or, for the OAuth dance, are shaped
 * by WorkOS-side rate limits already. The two POST credential routes are different: with
 * {@link PasswordAuthProvider} as the open edition's default provider, they are a real
 * password-guessing surface with nothing else standing in front of them. They have no
 * {@link TenantContext} — the whole point of hitting them is not having one yet — so the general
 * per-user bucket below cannot key on them; they get their own, much tighter, IP-keyed bucket
 * instead. {@code req.getRemoteAddr()} is the real client address, not the proxy's: the production
 * profile sets {@code server.forward-headers-strategy: native} with a Tomcat {@code internal-proxies}
 * allowlist, so Caddy's {@code X-Forwarded-For} is resolved before any filter runs, and
 * {@code DeviceLinkController} reads the same address the same way. A deployment that terminates
 * somewhere unlisted collapses every client onto one bucket instead, which throttles harder than
 * intended rather than less.
 *
 * <p><b>Both pools are capped, and a full pool EVICTS rather than refuses.</b> The per-user pool's
 * key space is the user base; the credential pool's is whatever address a caller can send from, which
 * is not ours to bound — an IPv6 /64 is billions of free keys, and a map that only ever grows ends at
 * the heap. Refusing a key there is the tempting answer and the wrong one: a caller who can mint
 * addresses would be handed a fresh bucket for each anyway, so refusal costs them nothing and locks
 * out the one caller it does reach — the legitimate new sign-in. The cap is a MEMORY bound, so it is
 * paid for in memory: see {@link #MAX_BUCKETS}.
 */
@Component
@Order(20) // AuthFilter is @Order(10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Tokens added per second (sustained rate), general per-user bucket. */
    private static final double REFILL_PER_SEC = 10.0;
    /** Max tokens in the bucket (burst capacity), general per-user bucket. */
    private static final double BURST = 60.0;

    /** Refill rate for the unauthenticated credential-route bucket: one attempt every 5s sustained. */
    private static final double CREDENTIAL_REFILL_PER_SEC = 0.2;
    /** Burst capacity for the credential-route bucket: a handful of attempts, then throttled hard. */
    private static final double CREDENTIAL_BURST = 5.0;

    /**
     * Most buckets one pool keeps. Reached only under something pathological: 10k distinct principals
     * inside one refill window, or a caller rotating source addresses to mint keys. A bucket at full
     * tokens is indistinguishable from one that has never been used, so {@link #sweepIdle} can drop it
     * without giving anyone back an allowance they had spent — which is what makes a cap safe here
     * rather than a hole in the limit.
     */
    private static final int MAX_BUCKETS = 10_000;

    /**
     * How far below {@link #MAX_BUCKETS} an eviction pass aims, so the next 1,000 or so new keys are
     * admitted without another O(n) pass. Amortises the pass to O(1) per insert.
     *
     * <p>A pass removes AT LEAST this many rather than exactly: it evicts every bucket at or below the
     * cutoff stamp, and {@code System.nanoTime()} is coarser than the gaps between concurrent inserts
     * on some platforms, so buckets can share the cutoff. Over-evicting only hands those keys a fresh
     * bucket early — the same thing the cap already grants whoever it evicts — so the imprecision costs
     * churn, never a limit.
     */
    private static final int EVICTION_HEADROOM = MAX_BUCKETS / 10;

    /**
     * At most one reclaim pass per second per pool: the flood that fills a pool must not also pay
     * O(n) on every request. Between passes a full pool keeps ADMITTING, so the ceiling is
     * {@link #MAX_BUCKETS} plus one second of distinct new keys — bounded, and trimmed back on the
     * next pass however far it overshot.
     */
    private static final long SWEEP_INTERVAL_NANOS = 1_000_000_000L;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> credentialRouteBuckets = new ConcurrentHashMap<>();
    private final AtomicLong lastUserSweepNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastCredentialSweepNanos = new AtomicLong(System.nanoTime());
    private final ObjectMapper mapper;

    public RateLimitFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getServletPath();
        if (path == null) path = req.getRequestURI();
        // Only rate-limit the user-facing API surfaces, plus (since #852) the two credential
        // routes. The rest of /auth/** stays exempt: the OAuth GETs are shaped by WorkOS-side rate
        // limits already; /auth/logout and /auth/me name no credential to guess. Everything else
        // under /actuator/ is NOT exempt (#935): AuthFilter runs first (@Order(10) vs this filter's
        // @Order(20)) and now requires a staff-verified TenantContext for those paths, so anything
        // reaching here already carries a real principal and can be throttled like any other
        // authenticated surface — leaving the whole prefix exempt was the same "one predicate
        // forgets what its sibling knows" drift that #929 fixed on the auth side.
        //
        // NOTE the polarity: this method returns true to SKIP filtering (exempt), so the guarded
        // actuator paths must be an OR term OUTSIDE a bare isPublicActuatorPath check, not folded
        // into one — `isPublicActuatorPath(path)` alone as a third disjunct here would flip the
        // public probes to "rate limited" and leave the guarded paths untouched, the opposite of
        // the intent. What has to enter the "must be filtered" side is isActuatorPath(path) &&
        // !isPublicActuatorPath(path) — a guarded (non-public) actuator path — not the public one.
        boolean guardedActuator = AuthFilter.isActuatorPath(path) && !AuthFilter.isPublicActuatorPath(path);
        boolean credentialRoute = isCredentialRoute(path, req.getMethod());
        return !(path.startsWith("/api/") || path.startsWith("/mcp") || guardedActuator || credentialRoute);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        TenantContext ctx = (TenantContext) req.getAttribute(TenantContext.ATTRIBUTE);
        if (ctx == null || ctx.userId() == null) {
            // The credential routes are the one case reaching here WITHOUT a context that must
            // still be throttled: shouldNotFilter now lets POST /auth/signup and /auth/login
            // through specifically because they have no session yet, so the ctx==null fall-through
            // below (correct for every other path this filter sees) would otherwise silently
            // un-throttle them again — removing the shouldNotFilter exemption alone does nothing
            // without this arm.
            String path = req.getServletPath();
            if (path == null) path = req.getRequestURI();
            if (isCredentialRoute(path, req.getMethod())) {
                if (!rejectIfExhausted(
                        req,
                        res,
                        credentialRouteBuckets,
                        lastCredentialSweepNanos,
                        "ip:" + req.getRemoteAddr(),
                        () -> new Bucket(CREDENTIAL_BURST, CREDENTIAL_REFILL_PER_SEC))) {
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

        // Key by user id when present (cookie-auth, MCP) — every MCP token
        // resolves to a user via ApiKey.principalId, so this keeps
        // automated callers tied to the human who issued the token.
        if (!rejectIfExhausted(
                req, res, buckets, lastUserSweepNanos, ctx.userId(), () -> new Bucket(BURST, REFILL_PER_SEC))) {
            return;
        }
        chain.doFilter(req, res);
    }

    /** POST /auth/signup or POST /auth/login — the two credential-checking routes #852 added. */
    private static boolean isCredentialRoute(String path, String method) {
        return "POST".equalsIgnoreCase(method) && ("/auth/signup".equals(path) || "/auth/login".equals(path));
    }

    /** Consume one token from {@code key}'s bucket in {@code pool}; on exhaustion, write the 429
     *  response and return false. True means the caller should proceed to {@code chain.doFilter}. */
    private boolean rejectIfExhausted(
            HttpServletRequest req,
            HttpServletResponse res,
            ConcurrentMap<String, Bucket> pool,
            AtomicLong sweepClock,
            String key,
            java.util.function.Supplier<Bucket> newBucket)
            throws IOException {
        // A new key is the only thing that can grow the pool, so the cap is checked here and nowhere
        // else. The key is then admitted either way — reclaim() makes the room rather than deciding
        // who goes without it.
        Bucket b = pool.get(key);
        if (b == null) {
            if (pool.size() >= MAX_BUCKETS) reclaim(pool, sweepClock);
            b = pool.computeIfAbsent(key, k -> newBucket.get());
        }
        return b.tryConsume(1.0) || reject429(res, b);
    }

    /** Always returns false, so a caller can {@code return reject429(res, b)} at each exhaustion arm. */
    private boolean reject429(HttpServletResponse res, Bucket bucket) throws IOException {
        res.setStatus(429);
        // The bucket's own refill rate, not a constant 1: the credential pool refills every five
        // seconds, so telling a caller to retry in one is an instruction to collect four more 429s.
        res.setHeader("Retry-After", Long.toString(bucket.retryAfterSeconds()));
        res.setContentType("application/json");
        res.getWriter()
                .write(mapper.writeValueAsString(
                        Map.of("ok", false, "error", Map.of("code", "rate.limited", "message", "too many requests"))));
        return false;
    }

    /**
     * Make room in a full pool, at most once per second per pool.
     *
     * <p>First drops every bucket that has refilled to full — one whose owner has stopped spending,
     * which is indistinguishable from a bucket that never existed, so nobody loses an allowance they
     * had spent. That is the ordinary case and usually the whole job.
     *
     * <p>A pool still at the cap afterwards is one where every bucket is being actively spent, which
     * is what a caller holding thousands of addresses and keeping each one warm produces: the idle
     * sweep frees nothing and, if a full pool refused new keys, every legitimate sign-in from an
     * address not already in the map would be turned away for as long as that caller cared to
     * continue. So the pass then evicts by AGE instead. Age is the right axis precisely because
     * {@link Bucket#tryConsume} stamps the bucket on every call including a rejected one: a caller
     * currently being throttled has the freshest stamp of all and cannot be evicted out of its own
     * limit, while the coldest tenth — the ones nobody is spending hardest — make way.
     */
    private static void reclaim(ConcurrentMap<String, Bucket> pool, AtomicLong sweepClock) {
        long now = System.nanoTime();
        long last = sweepClock.get();
        if (now - last < SWEEP_INTERVAL_NANOS || !sweepClock.compareAndSet(last, now)) return;
        pool.values().removeIf(b -> b.refilledBy(now));
        int excess = pool.size() - (MAX_BUCKETS - EVICTION_HEADROOM);
        if (excess <= 0) return;
        long[] stamps =
                pool.values().stream().mapToLong(Bucket::lastTouchedNanos).toArray();
        if (excess >= stamps.length) {
            pool.clear();
        } else {
            Arrays.sort(stamps);
            long coldest = stamps[excess - 1];
            pool.values().removeIf(b -> b.lastTouchedNanos() <= coldest);
        }
        // The one signal that separates "a pool full of real callers" from "someone is minting keys".
        log.warn("rate limiter pool was full of active buckets; evicted {} by age (cap {})", excess, MAX_BUCKETS);
    }

    /** Lock-free token bucket, parameterized so the credential-route pool can run a much tighter
     *  burst/refill than the general per-user pool without a second copy of the arithmetic. */
    private static final class Bucket {
        private final double burst;
        private final double refillPerSec;
        private final AtomicLong stateNanos = new AtomicLong(System.nanoTime());
        private volatile double tokens;

        Bucket(double burst, double refillPerSec) {
            this.burst = burst;
            this.refillPerSec = refillPerSec;
            this.tokens = burst;
        }

        /**
         * True when enough time has passed since this bucket was last touched for it to hold a full
         * burst again — so dropping it and minting a fresh one for the same key are the same thing.
         * Read without the lock on purpose: it decides eviction, not admission, and the worst a stale
         * read does is keep a bucket for one more sweep.
         */
        boolean refilledBy(long nowNanos) {
            return (nowNanos - stateNanos.get()) / 1_000_000_000.0 * refillPerSec >= burst;
        }

        /** When this bucket was last asked for a token, granted or not. Same unsynchronized read as above. */
        long lastTouchedNanos() {
            return stateNanos.get();
        }

        /** Whole seconds until one token is worth waiting for, at least 1 — what {@code Retry-After} carries. */
        long retryAfterSeconds() {
            return Math.max(1L, (long) Math.ceil(1.0 / refillPerSec));
        }

        synchronized boolean tryConsume(double n) {
            long now = System.nanoTime();
            long prev = stateNanos.getAndSet(now);
            double elapsedSec = (now - prev) / 1_000_000_000.0;
            tokens = Math.min(burst, tokens + elapsedSec * refillPerSec);
            if (tokens < n) return false;
            tokens -= n;
            return true;
        }
    }
}
