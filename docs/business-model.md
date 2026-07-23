# Business Model: Manufacture of clay building materials

> **Generated baseline.** This is an honest, registry- and blueprint-grounded
> business-model baseline for a fleet actor. The flagship landings (Meta Job
> Search / Talent Board / Placement Desk) carry hand-written, domain-deep
> business models; fleet actors carry this generated baseline. Unit-economics
> figures below are illustrative and **not yet measured at fleet scale** — a
> shape, not a reported metric. Regenerate with
> `nbb scripts/gen-actor-business-model.cljs <repo>` in `kotoba-lang/industry`.

## Classification
- Repository: `cloud-itonami-isic-2392` ([github.com/cloud-itonami/cloud-itonami-isic-2392](https://github.com/cloud-itonami/cloud-itonami-isic-2392))
- ISIC Rev.5: `2392` — Manufacture of clay building materials
- Domain: `:clay-building-materials/manufacturing`
- Social impact: :worker-safety :materials-safety :resource-efficiency
- Actor: `:clay-plant-operations-governor` — an independent Governor in the fleet's Sealed-LLM
  ⊣ Governor pattern (langgraph-clj StateGraph, append-only audit ledger,
  Phase 0→3 rollout). Robotics authority: full (governed field-equipment authority, HARD-gated, human sign-off on every actuation).

## Customer
An operator running this vertical as an OSS business — :clay-building-materials/manufacturing — who wants
a governed execution scaffold they own instead of renting a closed SaaS.

## Offer
The actor coordinates the :spec :design :produce :inspect :package :audit pipeline behind an independent Governor: the
advisor proposes only; the Governor HARD-blocks any proposal that fails a
spec-basis / evidence / actuation check; every real-world actuation is a
human sign-off (never autonomous, at any phase); every decision is recorded
in an append-only audit ledger. The full governor-check enumeration for this
vertical lives in `blueprint.edn`'s `:itonami.blueprint/implemented-slice`
and the `README.md`.

Capability stack (required): :robotics :identity :forms :dmn :bpmn :audit-ledger :cae.

## Revenue
Self-host is AGPL-3.0-or-later (free). Managed tenancy and compliance
packages are the revenue, in the same ¥50k–150k/月 band the sibling flagships
anchor against real competitor SaaS — see the flagship `docs/business-model.md`
files and `90-docs/pricing-intelligence` for the market-anchoring methodology.

## Unit Economics (worked example, illustrative)
One managed tenant (:clay-building-materials/manufacturing):
- infrastructure: actor runtime + store — runs at decision time, not per query
- LLM cost: proposals only at each operating step — bounded, because lookups
  never call a model
- human-approval labor: every real-world actuation is a human sign-off — the
  real cost driver
- support: budget a few hours/月 until feeds and jurisdiction catalogs stabilize

**These figures are illustrative and not yet measured at fleet scale.** Track
per operator: decisions/月, % proposals HARD-held (data-quality signal),
actuation-approval hours, churn.

## Open Participation
Anyone may fork, run the demo, self-host, submit patches, and publish
jurisdiction catalog entries (with official citations — never fabricated).
itonami.cloud certification is required before an operator is listed,
receives leads, or runs managed tenants under the platform brand.

## Operator Trust Levels
| Level | Capability |
|---|---|
| Contributor | patches, docs, jurisdiction catalog entries, examples |
| Self-host operator | runs their own instance, no platform endorsement |
| Certified operator | listed on itonami.cloud after review |
| Managed operator | may receive leads and operate customer tenants |
| Core maintainer | can approve changes to governor, security and governance |

## Trust Controls
- a proposal the governor refuses is never committed or actuated
- every real-world actuation is a human sign-off (never autonomous, at any phase)
- every decision (commit OR hold) is recorded in the append-only audit ledger
- sensitive operating and personal data stays outside Git

## Non-Negotiables
- Do not commit real customer / operating / personal data.
- Do not bypass the `:clay-plant-operations-governor` for production decisions.
- Do not market an uncertified deployment as an itonami.cloud certified operator.
- Any jurisdiction licence / registration this vertical requires is the
  operator's own legal duty; the software is the governed execution scaffold,
  not the licence.
