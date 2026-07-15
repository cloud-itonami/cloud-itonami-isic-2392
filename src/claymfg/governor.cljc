(ns claymfg.governor
  "Clay Plant Operations Governor -- the independent compliance layer
  that earns the ClayAdvisor the right to commit. The advisor has no
  notion of whether a piece of equipment it wants to schedule
  maintenance against has actually been inspected/registered, whether
  a batch it wants to coordinate a shipment against has actually been
  QC-verified/registered, whether a maintenance proposal secretly
  tries to ACTUATE (rather than merely draft-schedule) the extrusion
  press or kiln line, whether a shipment proposal's own claimed weight
  would blow through the batch's own logged production weight, or
  when an act stops being a coordination proposal and becomes direct
  extrusion-press/kiln-line-equipment control, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:clay-plant-operations-governor`
  (see docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:kiln-line/actuate`
                                       or `:extrusion-press/run`) is
                                       the 'direct extrusion-press/
                                       kiln-line-equipment control'
                                       scope violation this actor must
                                       NEVER perform -- HARD,
                                       PERMANENT, unconditional.
    4. Kiln-line-actuate blocked   -- for `:schedule-maintenance`, does
                                       the proposal's own `:value`
                                       declare `:actuate-kiln-line?
                                       true`? Directly actuating the
                                       extrusion press or kiln line is
                                       this actor's other permanent
                                       scope boundary (see README `What
                                       this actor does NOT do`) --
                                       HARD, PERMANENT, unconditional.
                                       NO phase and NO human approval
                                       can ever override this (see
                                       `claymfg.phase`: this op is
                                       never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    5. Equipment not verified/
       registered                  -- for `:schedule-maintenance`,
                                       INDEPENDENTLY verify the
                                       referenced equipment's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`claymfg.registry/equipment-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status. Grounded in this
                                       blueprint's own HARD invariant
                                       ('plant/batch record must be
                                       independently verified/
                                       registered before any action'):
                                       maintenance must never be
                                       scheduled against equipment
                                       whose own conditions have not
                                       actually been inspected or
                                       whose registration is not
                                       actually on file.
    6. Already scheduled           -- for `:schedule-maintenance`,
                                       refuses to schedule the SAME
                                       maintenance record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
    7. Batch not verified/
       registered                  -- for `:coordinate-shipment`,
                                       INDEPENDENTLY verify the
                                       referenced batch's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`claymfg.registry/batch-
                                       ready?`) -- never trust the
                                       advisor's own rationale. Also
                                       part of the 'plant/batch record'
                                       HARD invariant: a batch's own
                                       verified/registered status is as
                                       much a ground-truth fact as an
                                       equipment unit's own.
    8. Shipment weight exceeded    -- for `:coordinate-shipment`,
                                       INDEPENDENTLY recompute whether
                                       the batch's own recorded
                                       `:shipped-weight-kg` plus
                                       the proposal's own claimed
                                       `:weight-kg` would exceed
                                       the batch's own recorded
                                       `:weight-kg`
                                       (`claymfg.registry/shipment-
                                       weight-exceeded?`) -- ground
                                       truth from the batch's own
                                       permanent fields, never a
                                       self-reported weight claim.
    9. Invalid product-type        -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:product-type` outside the
                                       closed known set
                                       (`claymfg.registry/product-
                                       type-valid?`), the batch record
                                       is rejected rather than let a
                                       fabricated product type through.
   10. Invalid dimensional-
       deviation                    -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:dimensional-deviation-percent`
                                       that is not a physically
                                       plausible reading
                                       (`claymfg.registry/dimensional-
                                       deviation-valid?`), the batch
                                       record is rejected rather than
                                       let fabricated/gauge-error data
                                       through.
   11. Invalid defect-rate         -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:defect-rate-percent` that is
                                       not a physically plausible
                                       reading
                                       (`claymfg.registry/defect-
                                       rate-valid?`), the batch record
                                       is rejected rather than let
                                       fabricated/sensor-error data
                                       through.
   12. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`) -- escalate to a human
                                       plant supervisor. SOFT: the
                                       human may approve."
  (:require [claymfg.registry :as registry]
            [claymfg.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct extrusion-
  press/kiln-line-equipment-control effect."
  #{:batch/upsert :maintenance/schedule
    :safety-concern/flag :shipment/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns are the one op in this domain that always demands
  human eyes regardless of confidence."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- kiln-line-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct extrusion-press/kiln-line control, a
  fabricated actuation effect) is this actor's central scope
  boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :kiln-line-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は押出成形機・窯業焼成ライン等の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- kiln-line-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-maintenance` proposal
  whose own `:value` declares `:actuate-kiln-line? true` is attempting
  to directly actuate the extrusion press or kiln line -- this actor
  may only ever propose/schedule a DRAFT maintenance window, never
  actuate the extrusion press/kiln line directly. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-maintenance)
             (true? (:actuate-kiln-line? (:value proposal))))
    [{:rule :kiln-line-actuate-blocked
      :detail "押出成形機・窯業焼成ライン(トンネル窯等)の直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- equipment-not-verified-violations
  "For `:schedule-maintenance`, INDEPENDENTLY verify the referenced
  equipment exists and is both `:verified?` AND `:registered?` --
  never trust the advisor's own report. This is the HARD invariant
  ('plant/batch record must be independently verified/registered
  before any action')."
  [{:keys [op]} proposal st]
  (when (= op :schedule-maintenance)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での保守作業予定提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-maintenance`, refuses to schedule the SAME
  maintenance record twice, off a dedicated `:scheduled?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-maintenance)
    (when (store/maintenance-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- batch-not-verified-violations
  "For `:coordinate-shipment`, INDEPENDENTLY verify the referenced
  batch exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report. Also part of the 'plant/batch record
  must be independently verified/registered before any action' HARD
  invariant."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when-not (and b (registry/batch-ready? b))
        [{:rule :batch-not-verified
          :detail (str batch-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みバッチ記録が無い状態での出荷調整提案")}]))))

(defn- shipment-weight-exceeded-violations
  "For `:coordinate-shipment`, INDEPENDENTLY recompute whether the
  batch's own recorded shipped-to-date weight plus the proposal's own
  claimed weight would exceed the batch's own recorded `:weight-kg`
  -- ground truth from the batch's own permanent fields, never a
  self-reported weight claim."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [{:keys [batch-id weight-kg]} (:value proposal)
          b (and batch-id (store/batch st batch-id))]
      (when (and b (registry/shipment-weight-exceeded? b weight-kg))
        [{:rule :shipment-weight-exceeded
          :detail (str batch-id " の記録済み生産量(" (:weight-kg b)
                       "kg)を、既存出荷実績(" (:shipped-weight-kg b 0.0)
                       "kg)+今回申請(" weight-kg "kg)が超過")}]))))

(defn- invalid-product-type-violations
  "For `:log-production-batch`, if the patch declares a
  `:product-type` outside the closed known set, reject rather than
  let a fabricated product type through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [product-type (:product-type (:value proposal))]
      (when (and (some? product-type) (not (registry/product-type-valid? product-type)))
        [{:rule :invalid-product-type
          :detail (str product-type " は既知の product-type 値ではない")}]))))

(defn- invalid-dimensional-deviation-violations
  "For `:log-production-batch`, if the patch declares a
  `:dimensional-deviation-percent` that is not a physically plausible
  reading, reject rather than let fabricated/gauge-error data
  through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [dd (:dimensional-deviation-percent (:value proposal))]
      (when (and (some? dd) (not (registry/dimensional-deviation-valid? dd)))
        [{:rule :invalid-dimensional-deviation
          :detail (str dd "% は物理的に妥当な寸法偏差の範囲外")}]))))

(defn- invalid-defect-rate-violations
  "For `:log-production-batch`, if the patch declares a
  `:defect-rate-percent` that is not a physically plausible reading,
  reject rather than let fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [rr (:defect-rate-percent (:value proposal))]
      (when (and (some? rr) (not (registry/defect-rate-valid? rr)))
        [{:rule :invalid-defect-rate
          :detail (str rr "% は物理的に妥当な不良率の範囲外")}]))))

(defn check
  "Censors a ClayAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (kiln-line-control-blocked-violations proposal)
                           (kiln-line-actuate-blocked-violations request proposal)
                           (equipment-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (batch-not-verified-violations request proposal st)
                           (shipment-weight-exceeded-violations request proposal st)
                           (invalid-product-type-violations request proposal)
                           (invalid-dimensional-deviation-violations request proposal)
                           (invalid-defect-rate-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
