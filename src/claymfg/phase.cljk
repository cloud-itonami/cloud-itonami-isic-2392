(ns claymfg.phase
  "Phase 0->3 staged rollout for the clay building materials (brick and
  roof-tile) plant-operations coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- production-batch logging allowed,
                                    every write needs human approval.
    Phase 2  assisted-coordinate -- adds safety-concern flags and
                                    shipment-coordination proposals,
                                    still approval.
    Phase 3  supervised-auto    -- adds maintenance scheduling (still
                                    always approval -- see below);
                                    governor-clean, high-confidence
                                    `:log-production-batch` (no
                                    physical/financial risk) may auto-
                                    commit.

  `:schedule-maintenance` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Scheduling real maintenance against
  a piece of equipment is the one act in this domain with physical
  consequence (an extrusion-press/kiln-line-equipment maintenance
  window means production downtime, and the equipment ends up actually
  touched); it is always a human plant supervisor's call.
  `claymfg.governor`'s `kiln-line-actuate-blocked-violations`
  HARD-blocks actuate attempts unconditionally, and the confidence/
  high-stakes gate independently never lets `:flag-safety-concern`
  auto-commit either -- multiple independent layers agree on where
  this actor's authority ends. Like every prior sibling's phase-3
  `:auto` set, this domain has only ONE member
  (`:log-production-batch`) -- no separate no-risk lifecycle distinct
  from ordinary record logging.")

(def write-ops
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment})

;; NOTE the invariant: `:schedule-maintenance` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                            :auto #{}}
   1 {:label "assisted-intake"     :writes #{:log-production-batch}                        :auto #{}}
   2 {:label "assisted-coordinate" :writes #{:log-production-batch :flag-safety-concern
                                             :coordinate-shipment}                          :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto #{:log-production-batch}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-maintenance` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Clay Plant Operations Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
