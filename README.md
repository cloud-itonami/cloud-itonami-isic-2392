# cloud-itonami-isic-2392: Manufacture of clay building materials

Open Business Blueprint for **ISIC Rev.5 2392**: manufacture of clay building materials — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **brick and roof-tile plant operations**: production-batch data logging (product-type/weight/dimensional-deviation/defect-rate), extrusion-press/kiln-line-equipment maintenance scheduling, safety-concern flagging, and outbound clay building material shipment coordination.

This repository designs a forkable OSS business for clay building
material plant operations: run by a qualified operator so a brick/
tile plant keeps its own operating records instead of renting a
closed SaaS.

## Scope: clay building materials plant, not refractory or other ceramics

ISIC 2392 covers the **clay building materials plant** that wins/pugs
clay, extrudes or press-molds it into shape, dries it, then fires it
in a kiln (tunnel kiln or periodic kiln) — producing solid/perforated
brick, hollow block, facing brick, paving brick, roof/ridge tile, or
clay drainage pipe, ready to sell or ship. This is distinct from
`cloud-itonami-isic-2391` (Manufacture of refractory products), which
covers high-temperature-service refractory ceramics for furnace
linings rather than building materials, and from
`cloud-itonami-isic-2393` (Manufacture of other porcelain and ceramic
products), a separate ceramics vertical with a different feedstock and
firing profile. This actor's own hazard profile is centered on the
kiln-firing line and clay preparation: kiln-fire/thermal-hazard
(radiant heat and burn risk at the firing zone), clay/silica-dust
hazard at pugging and extrusion, and extrusion-press pinch-point
hazard.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-type/weight/dimensional-deviation/defect-rate data logging (administrative, not an operational decision)
- `:schedule-maintenance` — extrusion-press/kiln-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a kiln-fire/thermal-hazard, clay/silica-dust-hazard, or extrusion-press-equipment safety concern (always escalates)
- `:coordinate-shipment` — outbound clay building material (brick/tile) shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(kiln firing line, thermal/burn hazard, clay/silica-dust exposure,
extrusion-press pinch-point hazard):

- Does NOT control the extrusion press or kiln line equipment directly
- Does NOT make plant-safety or thermal-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the extrusion press or kiln line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`claymfg.operation/build`, a langgraph-clj StateGraph):
1. **`claymfg.advisor`** (sealed intelligence node, `ClayAdvisor`): proposes decisions only, never commits
2. **`claymfg.governor`** (independent, `Clay Plant Operations Governor`): validates against domain rules, re-derived from `claymfg.registry`'s pure functions and `claymfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct extrusion-press/kiln-line-equipment control)
     - Directly actuating the extrusion press or kiln line (`:actuate-kiln-line? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:dimensional-deviation-percent` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`claymfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`claymfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
