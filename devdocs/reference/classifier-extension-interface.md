# The classifier extension interface

**What a classifier is, as a plug-in.** The platform's launch promise is that the classifier
interface stays open, we charge for the classifiers we build, and anyone can write and run their own
on the open interface. This page is that interface written down: the ports a classifier attaches
through, how one is packaged and discovered, what happens when it is absent, and how the three paid
classifiers with an actual Java extraction map onto it. (A fourth paid classifier, frustration, has
no extraction to map — it rides the always-open, generic `EncoderDetector` and is paid only in that
its trained weights are withheld; see `CapabilityService` for the full four-classifier capability
picture, and §8 below for why this count is three, not four.)

Design record for #847. Implemented far enough to sever the engine's compile-time dependency on the
paid classifiers by #876, and first used end to end by #881. #887/#888 extended the seam a second
time: `BuiltInDetector` (observation/turn grain) gained a Spring-discovered path of its own,
`DetectorSupplier`, alongside the in-tree manifest — see §2 and §9.

---

## 1. Who can load an extension, and onto which backend

**The classifier extension interface is a same-JVM classpath mechanism for a self-hoster's own
backend.** An extension jar goes on the classpath of the backend the operator runs themselves.

**Tessary's hosted cloud runs only the classifiers Tessary ships.** It is not a backend you can load
an extension onto.

The reason, once: a classifier loaded by Spring into the backend JVM has no isolation — it sees every
bean and every tenant's rows — which is fine when the operator wrote it themselves and is a different
product decision on a multi-tenant cloud.

That is the whole of the boundary, and it is a product statement rather than a runtime check: nothing
in the tree today distinguishes "our cloud" from "a self-hoster", and inventing an edition signal to
enforce this would be a different decision in a different epic.

---

## 2. The seam is six ports, five of which already shipped

A classifier is not one interface. It is a set of small ports it may implement as many or as few of
as it needs, each keyed on the open `BuiltInDetector.Kind` constants. **Five of the six are discovered
by Spring collection injection outright. `BuiltInDetector` is hybrid, since #887/#888**: its CATALOG
ENTRY — name, description, capability, grain, config — is still in-tree-manifest-only, but the
detector object itself can now ALSO be supplied from off the classpath, through a new sixth-port
sibling, `DetectorSupplier`. The `Registered by` column below says which is which, and the difference
decides what an out-of-tree jar can actually contribute — see §9.

| Port | Package | Grain | Registered by | What an implementation does |
|---|---|---|---|---|
| `BuiltInDetector` | `classifier/catalog/` | observation, turn | in-tree manifest, **or** Spring `ObjectProvider<DetectorSupplier>` (generic, unguarded — new in #887/#888, see below) | Score one span (or a batch of them). Pure: no database, no clock, no writes. |
| `ClassifierSweep` | `classifier/worker/` | trace, window | Spring, `ObjectProvider` | Run one leased pass over a larger unit and write findings. Stateful. **New in #876.** |
| `TriageSource` | `classifier/finding/` | — | Spring, `List` | Serve this classifier's findings into the shared read surface and triage queue. `@Order`-ranked. |
| `CauseResolver` | `classifier/finding/` | — | Spring, `ObjectProvider` | Apply the classifier-specific half of a human's correction. |
| `ClassifierDebugContributor` | `classifier/debug/` | — | Spring, `ObjectProvider` | Contribute this classifier's fitted state to the debug rail. |
| `DetectionTable` | `detection/` (`backend/shared`) | span, trace | Spring, `ObjectProvider`, folded by `DetectionTableRegistry` (`toUnmodifiableMap`, duplicate kind fails boot) | Name the table this classifier's fired detections land in and the grain (`span`/`trace`) its rows carry. **New in Epic 3, #1071** — replaces the old `classifier_detection_v` view's hard-coded six-table list; a kind with no registered table takes one `writesDetections`-gated WARN from `ClassifierWorker` and scores nothing rather than throwing. |

`ProfileSource` used to be a sixth port here, serving `/behavior/profiles`. #919 moved it, and that
route, into `tessary-paid/behavior-drift` — it is now an internal wiring detail of that one paid
classifier, not a cross-boundary extension point, so it is not in this table. See §8.

The last three are #839's work and their contract is stated once, in
`backend/analysis/src/main/java/ai/tessary/evals/classifier/finding/package-info.java`. This page does
not restate those four rules in different words; it extends them to the write side. Read that file
first.

**A sixth seam exists and is deliberately not public.** `TrajectoryDetector` — the trace-grain
detector interface — lives inside the behaviour-drift module, where its only implementation already sat.
It went paid with the classifier that defined it (#840). An extension that wants trace-grain judgement
implements `ClassifierSweep` and does its own scoring; it does not get behaviour drift's fitting
procedure as a framework.

### The two extension grains

**`BuiltInDetector` — observation and turn grain.** Five implementations today, all pure functions of
one observation plus a config blob. The worker batches them, owns persistence, and the detector never
sees a repository.

**This port is now open to an out-of-tree jar, for a kind the in-tree catalog already declares — and
that is a real, deliberate change from what this page used to say, not a softened caveat on the old
claim.** Until #887/#888 there was no injection point for this port at all: `BuiltInClassifierCatalog`
built its detector map in its constructor from the private `static final MODULES` manifest list plus
two hardcoded instances, and `detectorFor` read only that map. `DetectorSupplier`
(`classifier/catalog/DetectorSupplier.java`) changed that: any `@Component` implementing it — a small,
`Deps`-taking factory shaped exactly like `ClassifierModelModule.DetectorFactory` — is discovered by
Spring `ObjectProvider` collection injection and folded into the SAME detector map `MODULES`'
factories populate, keyed by whatever `kind()` the detector it builds reports. Groundedness is the
worked example: its catalog entry stays in-tree (`detectorFactory: null`), and
`tessary-paid/groundedness`'s `GroundednessAutoConfiguration` supplies the real `GroundednessDetector`
through this seam.

**What still constrains it, and it is sharp enough to be a live trap, not a footnote.**
`BuiltInClassifierCatalog#builtIns()` and `ClassifierService`'s seeding both still read only the
in-tree `MODULES` list for a classifier's catalog metadata — its name, description, capability, grain
and default config. `DetectorSupplier` has NO membership check against that list: a bean claiming a
kind `MODULES` never declares builds successfully, registers successfully in the detector map, and is
never dispatched to, because nothing seeds a row for it, no capability gates it, and `grainFor`
defaults an undeclared kind to `Grain.OBSERVATION` with no sweep or catalog entry behind it. That is
the EXACT failure mode this section used to call impossible ("it fails silently") — it is now a live
possibility instead, and an extension author supplying a detector for a kind of their own still hits
hard stop 3 in §9 below: a new catalog entry is closed to an outsider, only a detector for an EXISTING
in-tree kind can be supplied this way. `DetectorSupplier` also fails loud on a duplicate kind, the same
invariant `ClassifierSweepRegistry` already enforces for `ClassifierSweep` — two sources (in-tree or
discovered, in any combination) claiming one kind throws `IllegalStateException` at construction
rather than letting classpath order pick a winner.

Trace or window judgement of your own still goes through `ClassifierSweep` only; observation/turn-grain
detector SUPPLY is now real, and observation/turn-grain catalog AUTHORSHIP (a brand-new kind, name,
capability and config `MODULES` has never declared) is still in-tree work, per hard stop 3.

**`ClassifierSweep` — trace and window grain.** The fitting tier: behaviour drift, SOP conformance,
duration drift, cost drift, tool errors. These carry `detectorFactory == null` and no
`BuiltInDetector` at all, because their unit of judgement is larger than a span and their state is
per-project and fitted rather than shipped. Until #876 this tier had no interface: `ClassifierWorker`
held each sweep as a constructor field and dispatched with an `if/else` chain on the detector kind,
which is precisely why the open engine could not compile without the paid classifiers.

```java
public interface ClassifierSweep {
    Set<String> kinds();                 // the BuiltInDetector.Kind values this sweep claims
    SweepOutcome sweep(SweepContext ctx); // one leased pass
}

public record SweepContext(ClassifierJobRow job, ClassifierRow classifier) {}
public record SweepOutcome(int scanned, int fired) {}
```

`kinds()` is a set rather than a single value because one sweep legitimately serves a family:
`MetricDriftSweep` runs both `duration_drift` and `cost_drift` from one bean over one baseline store.

`SweepContext` carries the two records every sweep already took as its two arguments and nothing
else — no repositories, no catalog, no worker, no clock. An implementation injects the collaborators
it needs like any other Spring bean. Widening this later costs one field; narrowing it after
out-of-tree classifiers have compiled against it costs their authors a release.

`SweepOutcome` is the two numbers every sweep can honestly answer. A classifier with a richer notion
of progress keeps it on its own type and narrows at the seam — `MetricDriftSweep` does exactly this
with the windows it rotated, which describes progress there and means nothing anywhere else.

---

## 3. Discovery: Spring Boot auto-configuration

**One jar, one imports file.** An extension ships
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` naming an
`@AutoConfiguration` class that `@ComponentScan`s its own package.

This is the only mechanism that fits, and the constraint that forces it is worth stating plainly: the
backend's scan root is `ai.tessary.evals`, so a bean in any other package is invisible to component
scan, and the open build can never take a Maven dependency on an extension in order to see it. Boot's
auto-configuration import is keyed on the **classpath** rather than on a package name or a build
dependency — Boot reads that file out of every jar it can see — so neither side has to name the other.

`java.util.ServiceLoader` was considered and rejected. There is no `ServiceLoader` use and no
`META-INF/services` directory anywhere in this backend, so it would be a second discovery mechanism
alongside the one Spring already runs, for beans that need Spring injection anyway. A plugin
classloader was rejected for the same reason plus one more: it implies an isolation boundary this
mechanism does not provide, which would be a worse lie than not having one (§1).

**`@ComponentScan`, not `@Bean` methods, and this is load-bearing.** An `@AutoConfiguration` class
registers only its own `@Bean` methods. An extension whose classifier is a `@Component` — which is
what a Spring-injected sweep is — gets an auto-configuration that loads and registers nothing.
Worse, auto-configuration is processed *after* user configuration, so a `@Bean` method there loses to
anything the host declares behind `@ConditionalOnMissingBean`, while a `@ComponentScan` registers
during the parse phase and is present in time. `tessary-paid/plan`'s
`PaidPlanAutoConfiguration` is the worked example and `PaidPlanAutoConfigurationTest` is the proof.

**`@ConfigurationProperties` binds without help, but is not scanned without it.** A properties class
outside `ai.tessary.evals` is not found by the host's `@ConfigurationPropertiesScan` — but once the
extension's own component scan registers it, the host's binding post-processor (which every Spring
Boot application has) binds it. Do **not** add `@EnableConfigurationProperties` for a class the scan
already registers: that is a second definition under a generated name and turns by-type injection
ambiguous.

---

## 4. Packaging

For paid modules in this repo: one Maven module per feature under `tessary-paid/`, package
`ai.tessary.paid.*`, parent `ai.tessary.paid:tessary-paid`. Three same-commit co-updates a NEW paid
module drags with it, each enforced by a script rather than by review:

- The overlay's own dev compose fragment, `tessary-paid/docker-compose.dev.yml`, must mount both
  `./tessary-paid/<m>/pom.xml:` and `./tessary-paid/<m>/src:` (`scripts/check-module-hygiene.sh`).
  The reason is build parity: without the mount the `paid` profile deactivates inside the container
  and the identical `mvn` command builds a different module set there than on the host. Note the
  trailing colons — they are the short bind syntax the gate greps for, so do not convert these
  mounts to long syntax without changing that script in the same commit. The paths stay
  repo-root-relative even though the file is one directory down: Compose resolves an override
  file's relative paths against the project directory, not the fragment's own.
- A new dependency is pinned with a literal `<version>` element, never a `<*.version>` property —
  properties are allowed only in `backend/pom.xml`, an open pom (same script). A dependency the Spring
  Boot BOM manages takes no version at all.
- Every pom at depth ≥ 2 under `tessary-paid/` declares `<groupId>ai.tessary.paid</groupId>`
  (`scripts/check-open-boundary.sh` check 0).

For a third-party extension: any group and any package. Nothing in the platform constrains it.

---

## 5. Versioning

**The manifest's `version` int is the only re-sync trigger.** `ClassifierService` re-syncs a seeded
built-in onto an already-provisioned project only when the catalog version exceeds the stored one, so
a change to a classifier's default config or its user-facing description that does not bump the
version reaches new installs and nothing else.

Port interfaces are **source-compatible-only within an epic**: a method may be added with a `default`
implementation, and a record may not lose a component. `SweepContext` in particular is a record on a
public port, so adding a field is a source-compatible change for implementers and a breaking one for
anyone who constructs it — which today is the worker and the test suite.

---

## 6. Failure isolation

There is no sandbox, and §1 is why that is a coherent position rather than an omission. What there
is, and what an extension inherits for free:

- **A per-job lease.** A sweep runs inside a claimed `classifier_job` row with a lease owner and
  expiry, so a crashed process does not strand the job.
- **An attempt budget and a dead-letter transition.** A sweep that throws is retried under
  `evals.classifier.max-attempts`; exhausting it moves the job to `DEAD` with a cooldown, and that
  transition is the one ERROR-level log in the path. Below-cap failures are WARN and dedup to one
  stacktrace per streak.
- **Kind isolation.** One classifier's failing job does not touch another's; each has its own row,
  cursor and budget.

**An unregistered kind is inert.** The worker logs one WARN (`signal.sweep.no-handler`) and completes
the job. It does not throw, does not dead-letter, and — the part with history behind it — does not
fall through to another sweep. The dispatch chain this replaced ended in a bare `else` that routed
every unrecognised window kind into metric drift; `tool_error` landed there once, its config blob
named no `measures` so the parse fell back to the full default set, and it maintained a second copy of
every duration and cost baseline and emitted a duplicate finding per drift under its own classifier
id. Nothing failed loudly.

**A duplicate kind fails the context at startup.** Two sweeps claiming one kind means two analyses
writing findings under one classifier row; picking one by bean order would make which analysis ran
depend on classpath order.

---

## 7. Absence is not withdrawal — the invariant with teeth

`BuiltInClassifierCatalog.builtIns()` is the full set of classifiers the platform **defines**, and it
is never filtered by what is loaded.

This is not a style preference. `ClassifierService.retireDroppedBuiltIns` permanently disables any
seeded `built_in=true` row whose key has left the catalog, on a 60-second `ClassifierCatalogWorker`
heartbeat over every active project, and the seeding path then treats the row as "not missing" and
never restores it. A registry that expressed "this jar is not on the classpath" as "the key left the
catalog" would silently and **irreversibly** retire every row a classifier had ever written, the first
time a database that had run the paid build ran the open one — and putting the jar back would not
bring them back.

So there are two distinct endings and only one of them writes:

| Ending | Mechanism | Writes |
|---|---|---|
| This edition does not ship the classifier | the flag/capability layer; `withheldBuiltInKeys` | nothing |
| The classifier left the catalog | `retireDroppedBuiltIns` | permanent disable |

`ClassifierSweepRegistry` never touches the catalog, and `ClassifierSweepRegistryTest` pins it.

**An absent adapter degrades; it does not fail to wire.** `CauseResolver` and `ClassifierDebugContributor`
are injected as `ObjectProvider<T>` rather than `List<T>`, because Spring treats a required collection
parameter with no candidates as an *unsatisfied dependency* rather than an empty list. Two of the ports
have exactly one implementation each, both inside the package #840 makes paid, so the `List<T>` shape
would have turned that extraction into a boot failure with no compile error, no import to sever and
nothing for the boundary grep to see. `TriageSource` stays `List<T>` because its own built-in
implementation, `BehaviorTriageSource`, never left the open package. `AbsentAdapterContextTest` holds it.

---

## 8. The paper port of the three paid classifiers

Frustration is deliberately near-absent from this section: it is a paid CAPABILITY
(`Capability.FRUSTRATION` joined `CapabilityService.UNAVAILABLE_IN_OPEN_EDITION` by #887) whose paid
CODE, as of Epic 3 (#1071), is exactly ONE bean — `tessary-paid/frustration`'s
`FrustrationAutoConfiguration`, registering `frustration_detection` with `DetectionTableRegistry` (§2)
and nothing else. It rides the always-open, generic `EncoderDetector`, the same detector class other
encoder-tier signals share, and what is withheld without that one bean is the write path (the
`writesDetections` gate), not the trained artifact or the detector class. This section is about
classifiers with an actual Java DETECTOR extraction, and there are three of those.

**Behaviour drift** (`tessary-paid/behavior-drift/`, package `ai.tessary.paid.classifier.behavior`, 25 files — originally 20 at #840, since grown with #919's profile-route move and Epic 3's #1071 detection-table bean):

| Port | Implementation |
|---|---|
| `ClassifierSweep` for `behavior_drift` (TRACE) | `BehaviorDriftSweep` |
| `CauseResolver` | `BehaviorDriftCauseResolver` |
| `ClassifierDebugContributor` | `BehaviorProfileDebugContributor` |
| `TrajectoryDetector` (its own, private) | `BehaviorDriftDetector` |
| `CaseSource` | `BehaviorDriftSource` (moved from the open `cases/` package with the classifier, for parity with `ConformanceCaseSource`) |

`GET /behavior/profiles` is served here too, but no longer through one of the six ports above:
`ProfileSource` and its one caller, `BehaviorProfileController`, both moved into this module in #919,
so `BehaviorProfileSource` now implements a port-shaped interface that is purely internal wiring
between two classes in the same module, not a cross-boundary extension point.

**Groundedness** (`tessary-paid/groundedness/`, package `ai.tessary.paid.classifier.groundedness`, 3 files — extracted by #887/#888):

| Port | Implementation |
|---|---|
| `DetectorSupplier` for `groundedness` (OBSERVATION) | `GroundednessAutoConfiguration`'s `groundednessDetectorSupplier` bean, building `GroundednessDetector` |

**Groundedness is a PARTIAL extraction, the first one this page has, and worth reading as its own
shape rather than reusing behaviour drift's or conformance's framing.** Only the compute moves —
`GroundednessDetector` and its `VerifiableClaims` deterministic pre-filter. The substrate-facing port,
`GroundingEvidenceReads` (`classifier/detector/`), STAYS OPEN: it is implemented by the open
`SubstrateReadRepository`, and moving the interface would force that open class to implement a paid
type, which the `enforce-open-to-paid-direction` enforcer bans outright. So the catalog entry stays
in-tree (metadata only, `detectorFactory: null`), the port stays open, and only the detector object
itself crosses the boundary — through `DetectorSupplier`, §2's new seam, not the #876
`ClassifierSweepRegistry`, which only covers trace/window-grain `ClassifierSweep` and never touches
observation-grain dispatch.

**SOP conformance** (`tessary-paid/conformance/`, package `ai.tessary.paid.classifier.conformance`, 60 files — extracted by #841, since grown with `intent/`, `scoring/` and `store/` subpackages and #918's reunification below):

| Port | Implementation |
|---|---|
| `ClassifierSweep` for `sop_conformance` (WINDOW) | `ConformanceSweep` |
| `TriageSource`, `@Order(10)` | `ConformanceTriageSource` |
| `CaseSource` | `ConformanceCaseSource` (moved from the open `cases/` package with the classifier) |
| `SopCompiler` (open, `ai.tessary.evals.sopcompile`; its caller is `tessary-paid/sop` since #842, so the port has two paid ends and open ground between them) | `compile/ConformanceCompileService` |
| `FitReportSource` (paid, `tessary-paid/conformance`) | `ConformanceRulebookService` |

**The asymmetry that used to be worth copying, and no longer applies.** Conformance was once the only
whole-directory extraction whose package could not move entirely, and the reason was the wire, not the
feature: `ConformanceController`, `ConformanceDtos` and the `FitReportSource` port stayed in the open
`ai.tessary.evals.classifier.conformance`, because the checked-in OpenAPI spec was one document
generated from the open application context and a route behind the boundary was a route deleted from
that document. #917/#944 replaced that rule with a per-edition spec, and #918 rejoined all three with
the rest of the classifier in `tessary-paid/conformance` — today there is no open/paid wire split left
in conformance. `FindingController` (renamed from `BehaviorController` in #921) and its records were
already in `classifier/finding/`, which does not move, so behaviour drift never needed this asymmetry.
Groundedness needed no wire asymmetry either (it opens no route of its own — the classifier read
endpoints are the shared, generic classifier surface — but it needed the SUBSTRATE-port asymmetry
above instead). An extension author with a wire surface today has no analogous split to copy from this
codebase.

Behaviour drift and conformance were complete after #876: every attachment is through a port, no open
class names either implementation, and each extraction was a `git mv` plus a package rename. Behaviour
drift is the worked example for a whole-classifier move — a module pom, an `@AutoConfiguration` with a
`@ComponentScan`, and one line in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, exactly the shape
§4 describes for a third-party author. Groundedness is the worked example for a PARTIAL move — an
`@AutoConfiguration` with two `@Bean` methods (the `DetectorSupplier` and, since Epic 3's #1071, a
`DetectionTable` registration) instead of a `@ComponentScan`, because its contributions are plain
factory objects rather than scanned stereotypes. What each still drags is
**text**, not code, and the boundary check is a grep rather than a compiler — see §11.

---

## 9. Writing an extension: the worked path

**What you write.** One jar, `my-classifier.jar`, in your own group and package:

1. `MyClassifierAutoConfiguration`, annotated `@AutoConfiguration` and `@ComponentScan` over your own
   package.
2. `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
   one line, naming that class.
3. A `@Component` implementing `ClassifierSweep` — `kinds()` returning the detector kind(s) it claims,
   `sweep(SweepContext)` returning a `SweepOutcome`. (Or, since #887/#888, a `@Bean` `DetectorSupplier`
   for observation/turn grain instead — but ONLY for a kind the in-tree catalog manifest already
   declares: seeding, the capability gate and `grainFor` all still read `MODULES` independently of this
   seam, so a `DetectorSupplier` for a kind of your own registers and is never dispatched to. A brand
   new kind needs a catalog entry, which is hard stop 3 below either way — see §2.)
4. Optionally, adapters on the read-side ports for what the surfaces show: `TriageSource`
   (`@Order`-ranked), `CauseResolver`, `ClassifierDebugContributor`, `CaseSource`.
5. If your classifier writes per-span/per-trace detections it needs a table to put them in
   (Epic 3, #1070/#1071): own your own `SpringLiquibase` bean, `@DependsOn(LiquibaseConfig.BEAN_NAME)`
   so it runs strictly after the open one, applying a changelog that creates your table — then
   register a `@Bean DetectionTable(yourKind, yourTable, Grain.SPAN|TRACE)` (§2) so
   `DetectionTableRegistry` includes it in the stitched union the writer, the retention sweep and
   every reader use. `tessary-paid/db` (`PaidDbAutoConfiguration`) and `tessary-paid/frustration`
   (`FrustrationAutoConfiguration`) are the in-tree worked examples of both halves.

**Where it runs.** On the classpath of **your own** backend — see §1. Build the platform, then launch
it with your jar alongside:

```
mvn -B -f backend/pom.xml install -DskipTests
mvn -f backend/pom.xml -pl app dependency:build-classpath -Dmdep.outputFile=/tmp/app.cp
mvn -f /path/to/my-classifier/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/mine.cp
java -cp "backend/app/target/classes:$(cat /tmp/app.cp):my-classifier.jar:$(cat /tmp/mine.cp)" \
     ai.tessary.evals.EvalsApplication
```

Boot reads the imports file out of `my-classifier.jar`, your auto-configuration scans your package,
and your `@Component`s join the context. Nothing in the platform's build names your jar.

**`/tmp/mine.cp` is your jar's own runtime dependencies, and it is not optional** unless your jar is
a fat one. `dependency:build-classpath` on `app` emits app's dependency closure and nothing else, and
that closure can never contain your extension — so it can never contain your extension's dependencies
either. A bare jar plus a library the platform does not already ship does not degrade to "extension
not loaded": Boot reads the imports file, the component scan reaches your class, and the context dies
in condition evaluation with `NoClassDefFoundError` before the application starts. Emit it from your own build, as the
second command above does, or shade your dependencies into the jar.

**What works end to end today, precisely.** The read side is real: a third-party `TriageSource`,
`CauseResolver`, `ClassifierDebugContributor` or `CaseSource` is discovered by `ObjectProvider`
injection and feeds the existing findings, triage, debug and case surfaces with no further
prerequisite. `/behavior/profiles` is not one of these — since #919 it is served from inside
`tessary-paid/behavior-drift` by a port that module owns itself, not one an out-of-tree jar can
implement (§2, §8). On the write side the **dispatch** is real — `ClassifierSweepRegistry`
discovers your sweep, and `ClassifierWorker` runs the kind it claims on the worker's cadence with the
lease and dead-letter budget — but in the builds this repo produces today **there is no kind left for
an out-of-tree sweep to claim**, so nothing of yours actually runs yet. Two closed doors, and it is
worth knowing which one you are at:

**A third door, opened by #887/#888, is narrower but genuinely usable today: `DetectorSupplier` for an
existing observation/turn-grain kind whose in-tree manifest entry has `detectorFactory: null`.**
`groundedness` is that kind in a self-hosted OPEN build — `tessary-paid/groundedness` is not on an
open classpath, so nothing claims it, and a self-hoster's own `@Component DetectorSupplier` for
`Kind.GROUNDEDNESS` is discovered and dispatched exactly like our own paid implementation would be.
This is real, working substitution, not a read-side adapter. It does not generalise past that one
kind, though: every OTHER observation/turn-grain kind (`frustration`, `secret_leak`,
`malformed_output`) already has an in-tree `detectorFactory`, and a `DetectorSupplier` claiming one of
those collides with it — the same fail-loud `IllegalStateException` the sweep story hits, not a silent
override. And on a build that DOES carry `tessary-paid/groundedness`, a self-hoster's own
`DetectorSupplier` for `groundedness` collides with OUR implementation the same way. So the honest
statement is: one kind, one edition, until a future manifest entry ships with `detectorFactory: null`
on purpose for extensibility rather than as a byproduct of one detector's own extraction.

- **The five fitting-tier kinds are already taken.** `behavior_drift` (TRACE) and `duration_drift`,
  `cost_drift`, `tool_error`, `sop_conformance` (WINDOW) are the whole of what
  `BuiltInClassifierCatalog` declares at trace or window grain, and each is claimed by an in-tree
  `@Component` sweep — `BehaviorDriftSweep`, `MetricDriftSweep` (two kinds), `ToolErrorSweep`,
  `ConformanceSweep`. Claiming one of them a second time is the duplicate-kind ending in §6: the
  registry's `toUnmodifiableMap` throws and **the application context refuses to start**. That is the
  designed answer, not a bug, but it means "drop in a sweep for an existing kind" bricks the backend
  on first boot rather than overriding anything.
- **Any other kind never reaches the registry.** `grainFor` defaults an undeclared kind to
  `Grain.OBSERVATION`, and `sweepSignal` consults `sweeps.forKind(...)` only in its `TRACE, WINDOW`
  arm — so a sweep claiming a kind of your own, or an observation-grain kind like `frustration` or
  `secret_leak`, registers successfully and is never dispatched. Giving a new kind trace or window
  grain means a catalog manifest entry, which is hard stop 3 below.

**What has to land first**, and it is somebody's issue rather than a mystery: #840 and #841 move
behaviour drift and SOP conformance out of `backend/analysis`, which frees `behavior_drift` and
`sop_conformance` for an out-of-tree sweep in the open build. That alone is not enough — both sit in
`CapabilityService.UNAVAILABLE_IN_OPEN_EDITION`, a compile-time `EnumSet` whose keys are never seeded
and never enqueued (`withheldBuiltInKeys`, `enqueueEnabled`), so the set has to become edition-aware
at the same time. Until then the seam is proven by the in-tree sweeps that ride it, and by
`ClassifierSweepRegistryTest`, and not by a third-party one.

**Three hard stops bound an extension, and each is somebody else's issue.** They are tracked on
the roadmap with owners; naming them here is the honest version of the promise:

1. **`Capability` is a closed enum** of 17 constants in the open `product` module, and a manifest's
   `capability` field is mandatory. An outsider cannot declare a new capability, so an extension
   classifier cannot have a switch of its own.
2. **`eval_case.detector` is a CHECK constraint over five literal keys** (`0000-baseline.sql:536`). An
   extension cannot open a **case** under a new key: the INSERT fails at runtime with nothing at
   compile time to warn. Relaxing it is a forward migration and belongs to the epic that owns the
   changelog partition, not here.
3. **Retirement cannot yet distinguish "not loaded" from "withdrawn"** (§7), so an extension cannot
   contribute a **catalog entry** of its own until it can. **Narrowed by #887/#888: this still holds
   in full for a BRAND-NEW entry** — a kind, name, capability and config `MODULES` has never
   declared is still catalog-authorship, and still closed, for exactly the reason above. **What it no
   longer blocks is supplying the DETECTOR for a kind the in-tree manifest already claims** —
   groundedness is exactly this case: its catalog entry is unchanged and in-tree, only the object that
   scores each observation is external, through `DetectorSupplier` (§2). Say both halves, because a
   reader who only reads "hard stop 3" would otherwise conclude the whole door is gone; it is ajar for
   one specific, narrower thing.

What each of those blocks: a new capability, a new case key, a new catalog module. What none of them
blocks: feeding every read-side surface — findings, triage, profiles, the debug rail — which works
today; and, since #887/#888, supplying the detector object for an EXISTING observation/turn-grain
kind, which works too. Running a sweep of your own is blocked by neither of them and by the two doors
above instead: every trace- and window-grain kind is claimed, and an unclaimed kind needs the catalog
entry hard stop 3 (in its still-closed, brand-new-entry sense) forbids. So hard stop 3 is the one to
watch — it gates third-party catalog contribution *and*, transitively, the only unclaimed way to get a
sweep dispatched, while its narrower carve-out is what makes `DetectorSupplier` possible for a kind
that already has a home.

---

## 10. Configuration

**Paid classifier configuration stays bound in the open `core` module, deliberately, and #841 left it
there.** `ClassifierProperties` carries the whole `evals.classifier.conformance.*` block — encoder mode,
encoder concurrency, max turns per sweep, minimum confirmations — and `@ConfigurationPropertiesScan`
binds it at boot in the open edition whether or not the classifier exists.

The alternative is to move those keys into a paid `@ConfigurationProperties` class, which would make
every existing deployment's `evals.classifier.conformance.*` value an unbound property the moment the
open image shipped. Recorded as a deliberate exception: unused keys in the open image are inert, and
silently unbound keys in a hosted one are not. Revisit at epic 5, where the two-edition build makes
"which image binds which keys" a question with a mechanism behind it.

An extension binds its own configuration however it likes, or reads the classifier row's
`config_json` — which is the per-project, per-tenant knob and the one an operator can change without a
deploy.

---

## 11. When a package moves

The check that actually enforces the boundary is `scripts/check-open-boundary.sh` rule 1, and it is a
**text grep** over every file under `backend/*/src` — main, test and resources — for the package
prefixes it derives from the overlay's own layout. It is self-arming: it proves nothing until paid
source exists at a new prefix, and then it fires on everything at once.

Two consequences the compiler will not warn you about:

- **Javadoc counts, and only in one direction.** A fully-qualified paid class name inside a
  `{@code ...}` block is a boundary violation to that grep and invisible to everything else.
  `BuiltInDetector` carried two such names and they were rewritten in #876 to name the seam instead.
  The rule that follows: **the seam does not name its implementations, in prose or in code.**

  The direction matters and #841 proved it. The grep arms on the packages the OVERLAY holds, so it
  catches a reference written as the NEW name and never sees one written as the OLD name. Every
  `ai.tessary.evals.classifier.conformance.*` string left in open prose survived that extraction
  silently, pointing at a package that no longer exists, with the build green. `BuiltInDetector`'s own
  javadoc had claimed the grep would catch exactly that case; it now says the opposite, because a prose
  reference to a moved package is caught by nothing but a person reading the file.
- **FQN-pinned ArchUnit rules are deleted by the extraction that moves their package, not
  re-pointed.** `ArchitectureRulesTest` pins class names as strings, and `backend/app` scans
  `ai.tessary.evals` only, so a rule pointing at a moved class matches nothing and passes green
  guarding nothing. `every_pinned_class_name_resolves` exists because that already happened once. Its
  job transfers to the boundary grep, which arms itself on the new prefix.

Two CI jobs also read classifier source by path or by regex over source text and will go red with no
Java error if a refactor moves what they read: `scripts/check-classifier-quality-doc.sh` opens
`BuiltInClassifierCatalog.java` by literal path and pulls thresholds out of its config string
literals, and `scripts/check-conformance-parity.sh` pins the test resource
`backend/analysis/src/test/resources/conformance_parity.json` to the Java port at
`ai.tessary.paid.classifier.conformance`.

That fixture is worth reading as a worked example, because it has **five** consumers and moving it
touches every one: the Python generator
(`tessary-paid/classifiers/experiments/engine/build_parity_fixture.py`, which walks up to the repo
root to find the output path — #843 moved it into the paid overlay while the fixture stayed open,
so an open artifact three open consumers read is now regenerated only by paid code), the parity gate above, `scripts/check-classify-service.sh` (which
reads `encoder_smoke.checkpoint` to validate the embedder registry, and IS in `scripts/check.sh`, so
missing it turns a local `task check` red rather than only CI), and two Node tests in
`classify-service/`. Only the first two are obvious from the Java side. A generated artifact that
lives under a moving directory is not a file move; it is a small graph.

**And three of those five are OPEN**, which is why the fixture did NOT follow the Java port into
`tessary-paid/`. #841 moved it there and every gate stayed green — the enforcer sees no dependency,
`check-open-boundary.sh` reads only `backend/*/src` and `frontend/` — while `task check` and two CI
jobs went red in the public repo, because D2's folder filter deletes the directory those three open
consumers were now reading from. A shared fixture belongs on the OPEN side of a boundary and is
consumed across it; the paid module puts it on its own test classpath with a `<testResource>`, and
`check-open-boundary.sh` rule 5 now fails any open service or check script that reads out of the
overlay. Nothing in an open file may name a paid package either, the fixture body included — which
is why its `comment` names no Java package at all.
