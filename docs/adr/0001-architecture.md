# ADR-0001: ClayAdvisor ⊣ Clay Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2392` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2392` publishes an OSS blueprint for clay building
material (brick and roof-tile) plant **operations coordination**
(production-batch product-type/weight/dimensional-deviation/defect-
rate data logging, extrusion-press/kiln-line-equipment maintenance
scheduling, safety-concern flagging, and outbound clay building
material shipment coordination). Like every actor in this fleet, the
blueprint alone is not an implementation: this ADR records the
governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

The closest architectural analog is `cloud-itonami-isic-2431` (Casting
of iron and steel): both are back-office coordination actors for a
fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
plants with distinct hazard profiles: 2431's central physical hazard
is molten-metal handling (splash/burn risk at the melting furnace and
pour, furnace radiant-heat exposure, mold/core-binder fume exposure),
while 2392's is kiln-firing heat and clay/silica-dust exposure
(kiln-fire/thermal-hazard at the firing zone, clay/silica-dust hazard
at pugging/extrusion, extrusion-press pinch-point hazard). This build
mirrors 2431's architecture closely but adapts the hazard profile and
equipment/product vocabulary to the brick/tile plant: 2392's permanent
equipment-actuation block guards an extrusion press/kiln line
(`:actuate-kiln-line?`) rather than a melting furnace/pouring line
(`:actuate-furnace?`); and 2392's production-batch record declares a
`:product-type` (spanning brick, block, tile and pipe families, per
ISIC 2392's own combined scope), a `:dimensional-deviation-percent`
(dimensional-spec conformance, a data point specific to this vertical
with no direct 2431 analog), and a `:defect-rate-percent`, rather than
2431's `:alloy-grade`/`:defect-rate-percent` pair alone.

`cloud-itonami-isic-2392` is also distinct from
`cloud-itonami-isic-2391` (Manufacture of refractory products, a
distinct high-temperature-service ceramics vertical for furnace
linings rather than building materials) and from
`cloud-itonami-isic-2393` (Manufacture of other porcelain and ceramic
products, a distinct ceramics vertical with a different feedstock and
firing profile) -- neither of which this build depends on or wraps.

This vertical has NO pre-existing `kotoba-lang/claymfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`claymfg.registry` (equipment/batch verification, shipment-weight
recompute, product-type validation, dimensional-deviation plausibility
validation, defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2431`'s `foundrymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:clay-plant-operations-governor`, is grep-verified UNIQUE fleet-wide
(`gh search code "clay-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created); the
`claymfg` namespace prefix is likewise grep-verified UNIQUE fleet-wide
(`gh search code "claymfg" --owner cloud-itonami`, zero hits before
this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external clay-building-materials capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
clay building materials vertical has NO pre-existing capability
library to wrap. The equipment/batch-verification / shipment-weight /
product-type / dimensional-deviation / defect-rate validation
functions live as pure functions in `claymfg.registry` and are
re-verified independently by `claymfg.governor` -- the same "ground
truth, not self-report" discipline established across prior actors
(most directly `cloud-itonami-isic-2431`'s `foundrymfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of clay building
material plant operations. It does NOT:
- Control the extrusion press or kiln line equipment directly
- Make plant-safety or thermal-safety decisions (exclusive to the human plant supervisor)
- Actuate the extrusion press or kiln line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority --
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: clay building material manufacturing is
a safety-critical domain (kiln-firing thermal/burn hazard, clay/
silica-dust exposure, extrusion-press pinch-point hazard, heavy
material handling). Safety-concern flagging NEVER auto-commits. All
safety concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (kiln-fire/thermal-hazard concern, radiant-heat
exposure, clay/silica-dust-hazard exposure, extrusion-press-equipment
safety concern, crew fatigue) ALWAYS escalates, never auto-commits.
This is not a "low-stakes proposal" -- it is a circuit-breaker that
must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2431`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently verified/
registered before any action" HARD invariant applied to the two
distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight -- never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into eleven concrete checks
in `claymfg.governor`, one more than `cloud-itonami-isic-2431`'s own
ten -- this vertical's extra `:dimensional-deviation-percent`
plausibility check has no 2431 analog, per Decision 1's own
`:dimensional-deviation-percent` field decision above) block proposals
and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct extrusion-press/kiln-line-equipment control or kiln-line actuation is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Clay building material plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into eleven concrete governor checks) protect against scope creep into
unauthorized equipment operation or kiln-line actuation. Safety
concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and kiln-line operation remain
human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2392`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, kiln-line-actuate-
  blocked, already-scheduled, invalid-product-type, invalid-
  dimensional-deviation, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
