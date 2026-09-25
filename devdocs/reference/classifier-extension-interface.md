# The classifier extension interface

**What a classifier is, as a plug-in.** The platform's launch promise is that the classifier
interface stays open, we charge for the classifiers we build, and anyone can write and run their own
on the open interface. This page is that interface written down: the ports a classifier attaches
through, how one is packaged and discovered, and what happens when it is absent. (Frustration and
groundedness, once paid, are open: their detectors, tables and rate tests all ship in this tree.)

This is implemented far enough to sever the engine's compile-time dependency on the
paid classifiers, and is used end to end. The seam was later extended a second
time: `BuiltInDetector` (observation/turn grain) gained a Spring-discovered path of its own,
`DetectorSupplier`, alongside the in-tree manifest — see §2 and §8.

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
enforce this would be a different decision, tracked separately.

---

## 2. The seam is four ports

A classifier is not one interface. It is a set of small ports it may implement as many or as few of
as it needs, each keyed on the open `BuiltInDetector.Kind` constants. **Three of the four are discovered
by Spring collection injection outright. `BuiltInDetector` is hybrid**: its CATALOG
ENTRY — name, description, capability, grain, config — is still in-tree-manifest-only, but the
detector object itself can now ALSO be supplied from off the classpath, through a
sibling, `DetectorSupplier`. The `Registered by` column below says which is which, and the difference
decides what an out-of-tree jar can actually contribute — see §8.

| Port | Package | Grain | Registered by | What an implementation does |
|---|---|---|---|---|
| `BuiltInDetector` | `classifier/catalog/` | observation, turn | in-tree manifest, **or** Spring `ObjectProvider<DetectorSupplier>` (generic, unguarded, see below) | Score one span (or a batch of them). Pure: no database, no clock, no writes. |
| `ClassifierSweep` | `classifier/worker/` | trace, window | Spring, `ObjectProvider` | Run one leased pass over a larger unit and write findings. Stateful. |
| `TriageSource` | `classifier/finding/` | — | Spring, `List` | Serve this classifier's findings into the shared read surface and triage queue. `@Order`-ranked. |
| `DetectionTable` | `detection/` (`backend/shared`) | span, trace | Spring, `ObjectProvider`, folded by `DetectionTableRegistry` (`toUnmodifiableMap`, duplicate kind fails boot) | Name the table this classifier's fired detections land in and the grain (`span`/`trace`) its rows carry. Replaces the old `classifier_detection_v` view's hard-coded six-table list; a kind with no registered table takes one `writesDetections`-gated WARN from `ClassifierWorker` and scores nothing rather than throwing. |

`ProfileSource` used to be a port here, serving `/behavior/profiles`. That route later moved
into the behaviour-drift classifier's own module — it is now an internal wiring detail of that one
paid classifier, not a cross-boundary extension point, so it is not in this table.

`TriageSource` has its contract stated once, in
`backend/analysis/src/main/java/ai/tessary/classifier/finding/package-info.java`. This page does
not restate those three rules in different words; it extends them to the write side. Read that file
first.

**One more seam exists and is deliberately not public.** `TrajectoryDetector` — the trace-grain
detector interface — lives inside the behaviour-drift module, where its only implementation already sat.
It went paid with the classifier that defined it. An extension that wants trace-grain judgement
implements `ClassifierSweep` and does its own scoring; it does not get behaviour drift's fitting
procedure as a framework.

### The two extension grains

**`BuiltInDetector` — observation and turn grain.** Five implementations today, all pure functions of
one observation plus a config blob. The worker batches them, owns persistence, and the detector never
sees a repository.

**This port is now open to an out-of-tree jar, for a kind the in-tree catalog already declares — and
that is a real, deliberate change from what this page used to say, not a softened caveat on the old
claim.** Previously there was no injection point for this port at all: `BuiltInClassifierCatalog`
built its detector map in its constructor from the private `static final MODULES` manifest list plus
two hardcoded instances, and `detectorFor` read only that map. `DetectorSupplier`
(`classifier/catalog/DetectorSupplier.java`) changed that: any `@Component` implementing it — a small,
`Deps`-taking factory shaped exactly like `ClassifierModelModule.DetectorFactory` — is discovered by
Spring `ObjectProvider` collection injection and folded into the SAME detector map `MODULES`'
factories populate, keyed by whatever `kind()` the detector it builds reports. Groundedness WAS the
worked example — its catalog entry sat in-tree with `detectorFactory: null` and the paid
`GroundednessAutoConfiguration` supplied the real `GroundednessDetector` through this seam — until
2026-09-21, when the model went public and the detector moved in-tree
(`classifier/detector/groundedness/`). Two shipped manifest entries still have a null factory,
`frustration` and `groundedness`, and both are open: each detector needs something the catalog's
shared `Deps` do not carry (its own repositories, and for frustration the decision client), so each
arrives through an in-tree
`DetectorSupplier` (`FrustrationDetectorSupplier`, `GroundednessDetectorSupplier`). The seam is
proven by `ClassifierModelModuleCatalogTest`'s two discovery tests.

**What still constrains it, and it is sharp enough to be a live trap, not a footnote.**
`BuiltInClassifierCatalog#builtIns()` and `ClassifierService`'s seeding both still read only the
in-tree `MODULES` list for a classifier's catalog metadata — its name, description, capability, grain
and default config. `DetectorSupplier` has NO membership check against that list: a bean claiming a
kind `MODULES` never declares builds successfully, registers successfully in the detector map, and is
never dispatched to, because nothing seeds a row for it, no capability gates it, and `grainFor`
defaults an undeclared kind to `Grain.OBSERVATION` with no sweep or catalog entry behind it. That is
the EXACT failure mode this section used to call impossible ("it fails silently") — it is now a live
possibility instead, and an extension author supplying a detector for a kind of their own still hits
hard stop 3 in §8 below: a new catalog entry is closed to an outsider, only a detector for an EXISTING
in-tree kind can be supplied this way. `DetectorSupplier` also fails loud on a duplicate kind, the same
invariant `ClassifierSweepRegistry` already enforces for `ClassifierSweep` — two sources (in-tree or
discovered, in any combination) claiming one kind throws `IllegalStateException` at construction
rather than letting classpath order pick a winner.

Trace or window judgement of your own still goes through `ClassifierSweep` only; observation/turn-grain
detector SUPPLY is now real, and observation/turn-grain catalog AUTHORSHIP (a brand-new kind, name,
capability and config `MODULES` has never declared) is still in-tree work, per hard stop 3.

**`ClassifierSweep` — trace and window grain.** The fitting tier:
duration drift, cost drift, tool errors. These carry `detectorFactory == null` and no
`BuiltInDetector` at all, because their unit of judgement is larger than a span and their state is
per-project and fitted rather than shipped. Previously this tier had no interface: `ClassifierWorker`
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
backend's scan root is `ai.tessary`, so a bean in any other package is invisible to component
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
during the parse phase and is present in time. A paid plan module's own
`PaidPlanAutoConfiguration` is the worked example and `PaidPlanAutoConfigurationTest` is the proof.

**`@ConfigurationProperties` binds without help, but is not scanned without it.** A properties class
outside `ai.tessary` is not found by the host's `@ConfigurationPropertiesScan` — but once the
extension's own component scan registers it, the host's binding post-processor (which every Spring
Boot application has) binds it. Do **not** add `@EnableConfigurationProperties` for a class the scan
already registers: that is a second definition under a generated name and turns by-type injection
ambiguous.

---

## 4. Packaging

For a paid module: one Maven module per feature, package
`ai.tessary.paid.*`, parent `ai.tessary.paid:tessary-paid`. A new dependency is pinned with a
literal `<version>` element, never a `<*.version>` property — properties are allowed only in
`backend/pom.xml` (`scripts/check-module-hygiene.sh`). A dependency the Spring Boot BOM manages
takes no version at all.

For a third-party extension: any group and any package. Nothing in the platform constrains it.

---

## 5. Versioning

**The manifest's `version` int is the only re-sync trigger.** `ClassifierService` re-syncs a seeded
built-in onto an already-provisioned project only when the catalog version exceeds the stored one, so
a change to a classifier's default config or its user-facing description that does not bump the
version reaches new installs and nothing else.

Port interfaces are **source-compatible-only for now**: a method may be added with a `default`
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
  `tessary.classifier.max-attempts`; exhausting it moves the job to `DEAD` with a cooldown, and that
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

**An absent adapter degrades; it does not fail to wire.** Spring treats a required collection
parameter with no candidates as an *unsatisfied dependency* rather than an empty list. `TriageSource`
is safe as a `List<T>` because its own built-in implementation, `BehaviorTriageSource`, never left the
open package. `AbsentAdapterContextTest` holds it.

---

## 8. Writing an extension: the worked path

**What you write.** One jar, `my-classifier.jar`, in your own group and package:

1. `MyClassifierAutoConfiguration`, annotated `@AutoConfiguration` and `@ComponentScan` over your own
   package.
2. `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
   one line, naming that class.
3. A `@Component` implementing `ClassifierSweep` — `kinds()` returning the detector kind(s) it claims,
   `sweep(SweepContext)` returning a `SweepOutcome`. (Or a `@Bean` `DetectorSupplier`
   for observation/turn grain instead — but ONLY for a kind the in-tree catalog manifest already
   declares: seeding, the capability gate and `grainFor` all still read `MODULES` independently of this
   seam, so a `DetectorSupplier` for a kind of your own registers and is never dispatched to. A brand
   new kind needs a catalog entry, which is hard stop 3 below either way — see §2.)
4. Optionally, adapters on the read-side ports for what the surfaces show: `TriageSource`
   (`@Order`-ranked) and `CaseSource`.
5. If your classifier writes per-span/per-trace detections it needs a table to put them in:
   own your own `SpringLiquibase` bean, `@DependsOn(LiquibaseConfig.BEAN_NAME)`
   so it runs strictly after the open one, applying a changelog that creates your table — then
   register a `@Bean DetectionTable(yourKind, yourTable, Grain.SPAN|TRACE)` (§2) so
   `DetectionTableRegistry` includes it in the stitched union the writer, the retention sweep and
   every reader use. `PaidDbAutoConfiguration` is the in-tree worked example of both halves.

**Where it runs.** On the classpath of **your own** backend — see §1. Build the platform, then launch
it with your jar alongside:

```
mvn -B -f backend/pom.xml install -DskipTests
mvn -f backend/pom.xml -pl app dependency:build-classpath -Dmdep.outputFile=/tmp/app.cp
mvn -f /path/to/my-classifier/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/mine.cp
java -cp "backend/app/target/classes:$(cat /tmp/app.cp):my-classifier.jar:$(cat /tmp/mine.cp)" \
     ai.tessary.TessaryApplication
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

**What works end to end today, precisely.** The read side is real: a third-party `TriageSource`
or `CaseSource` is discovered by Spring injection and feeds the existing findings, triage and case
surfaces with no further prerequisite. `/behavior/profiles` is not one of these — it is served from
inside the behaviour-drift module by a port that module owns itself, not one an out-of-tree jar can
implement (§2). On the write side the **dispatch** is real — `ClassifierSweepRegistry`
discovers your sweep, and `ClassifierWorker` runs the kind it claims on the worker's cadence with the
lease and dead-letter budget — but in the builds this repo produces today **there is no kind left for
an out-of-tree sweep to claim**, so nothing of yours actually runs yet. Two closed doors, and it is
worth knowing which one you are at:

**A third door exists but is currently closed: `DetectorSupplier` for an existing
observation/turn-grain kind whose in-tree manifest entry has `detectorFactory: null`.** Until
2026-09-21 `groundedness` was that kind in a self-hosted OPEN build — the paid groundedness module was
not on an open classpath, so nothing claimed it, and a self-hoster's own `@Component
DetectorSupplier` for `Kind.GROUNDEDNESS` was discovered and dispatched exactly like the paid
implementation. With the detector now in-tree, every observation/turn-grain kind already has an
in-tree detector (`groundedness`, `secret_leak` and `malformed_output` through their
`detectorFactory`, `frustration` through its own in-tree `DetectorSupplier`), and a
`DetectorSupplier` claiming one of them collides with it — the same fail-loud
`IllegalStateException` the sweep story hits, not a silent override. So the honest statement is: no
kind today, until a future manifest entry ships with `detectorFactory: null` on purpose for
extensibility rather than as a byproduct of one detector's extraction.

- **The three fitting-tier kinds are already taken.** `duration_drift`, `cost_drift` and
  `tool_error` (WINDOW) are the whole of what
  `BuiltInClassifierCatalog` declares at trace or window grain, and each is claimed by an in-tree
  `@Component` sweep — `MetricDriftSweep` (two kinds) and `ToolErrorSweep`. Claiming one of them a
  second time is the duplicate-kind ending in §6: the
  registry's `toUnmodifiableMap` throws and **the application context refuses to start**. That is the
  designed answer, not a bug, but it means "drop in a sweep for an existing kind" bricks the backend
  on first boot rather than overriding anything.
- **Any other kind never reaches the registry.** `grainFor` defaults an undeclared kind to
  `Grain.OBSERVATION`, and `sweepSignal` consults `sweeps.forKind(...)` only in its `TRACE, WINDOW`
  arm — so a sweep claiming a kind of your own, or an observation-grain kind like `frustration` or
  `secret_leak`, registers successfully and is never dispatched. Giving a new kind trace or window
  grain means a catalog manifest entry, which is hard stop 3 below.

**Three hard stops bound an extension, and each is somebody else's issue.** They are tracked on
the roadmap with owners; naming them here is the honest version of the promise:

1. **`Capability` is a closed enum** of 15 constants in the open `product` module, and a manifest's
   `capability` field is mandatory. An outsider cannot declare a new capability, so an extension
   classifier cannot have a switch of its own.
2. **`eval_case.detector` is a CHECK constraint over five literal keys** (`0000-baseline.sql:536`). An
   extension cannot open a **case** under a new key: the INSERT fails at runtime with nothing at
   compile time to warn. Relaxing it is a forward migration and belongs to whoever owns the
   changelog partition, not here.
3. **Retirement cannot yet distinguish "not loaded" from "withdrawn"** (§7), so an extension cannot
   contribute a **catalog entry** of its own until it can. **Narrowed: this still holds
   in full for a BRAND-NEW entry** — a kind, name, capability and config `MODULES` has never
   declared is still catalog-authorship, and still closed, for exactly the reason above. **What it no
   longer blocks is supplying the DETECTOR for a kind the in-tree manifest already claims** —
   groundedness was exactly this case until its detector moved in-tree: its catalog entry was
   unchanged and in-tree, only the object that scored each observation was external, through
   `DetectorSupplier` (§2). Say both halves, because a
   reader who only reads "hard stop 3" would otherwise conclude the whole door is gone; it is ajar for
   one specific, narrower thing.

What each of those blocks: a new capability, a new case key, a new catalog module. What none of them
blocks: feeding the read-side surfaces — findings, triage and cases — which works
today; and supplying the detector object for an EXISTING observation/turn-grain
kind, which works too. Running a sweep of your own is blocked by neither of them and by the two doors
above instead: every trace- and window-grain kind is claimed, and an unclaimed kind needs the catalog
entry hard stop 3 (in its still-closed, brand-new-entry sense) forbids. So hard stop 3 is the one to
watch — it gates third-party catalog contribution *and*, transitively, the only unclaimed way to get a
sweep dispatched, while its narrower carve-out is what makes `DetectorSupplier` possible for a kind
that already has a home.

---

## 9. Configuration

An extension binds its own configuration however it likes, or reads the classifier row's
`config_json` — which is the per-project, per-tenant knob and the one an operator can change without a
deploy.

---

## 10. When a package moves

Nothing in this tree greps for another edition's package names, so a reference that points at a
moved class is caught by a person reading the file, not by a gate. Two consequences the compiler will
not warn you about:

- **Javadoc counts.** A fully-qualified class name inside a `{@code ...}` block goes stale silently
  when the class moves. `BuiltInDetector` carried two such names and they were rewritten to name the
  seam instead. The rule that follows: **the seam does not name its implementations, in prose or in
  code.**
- **FQN-pinned ArchUnit rules are deleted by the extraction that moves their package, not
  re-pointed.** `ArchitectureRulesTest` pins class names as strings, and `backend/app` scans
  `ai.tessary` only, so a rule pointing at a moved class matches nothing and passes green
  guarding nothing. `every_pinned_class_name_resolves` exists because that already happened once.

One CI job also reads classifier source by path and will go red with no Java error if a refactor
moves what it reads: `scripts/check-classifier-quality-doc.sh` opens `BuiltInClassifierCatalog.java`
by literal path and pulls thresholds out of its config string literals.
