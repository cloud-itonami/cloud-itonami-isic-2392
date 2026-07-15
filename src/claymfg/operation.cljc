(ns claymfg.operation
  "ClayOperationActor -- one plant-operations coordination request =
  one supervised actor run, expressed as a langgraph-clj StateGraph.
  The advisor (ClayAdvisor) is sealed into a single node (:advise);
  its proposal is ALWAYS routed through the Clay Plant Operations
  Governor (:govern) and the rollout phase gate (:decide) before
  anything commits to the SSoT.

  One graph run = one coordination request (assess -> advise -> govern
  -> decide -> commit | hold | approval). No unbounded inner loop --
  each operation is auditable and checkpointed. A plant's lifecycle is
  advanced by MANY ops (production-batch logging / maintenance
  scheduling / shipment coordination), each independent.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor and hands the
  decision to a human plant supervisor/shipping approver. The approver
  resumes with `{:approval {:status :approved}}` (or :rejected)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [claymfg.advisor :as advisor]
            [claymfg.governor :as governor]
            [claymfg.phase :as phase]
            [claymfg.store :as store]))

(defn- commit-fact [request context proposal]
  {:t :committed
   :op (:op request)
   :actor (:actor-id context)
   :subject (:subject request)
   :disposition :commit
   :basis (:cites proposal)
   :summary (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:effect (:effect proposal)
   :path [(:subject request)]
   :value (or (:value proposal) {})
   :payload (:value proposal)})

(defn build
  "Compiles a ClayOperationActor graph bound to `store`.
  opts:
    :advisor      -- a `claymfg.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request {:default nil}
         :context {:default nil}  ; injected actor-id/role/phase
         :proposal {:default nil}
         :verdict {:default nil}
         :disposition {:default nil}  ; :commit | :hold | :escalate
         :record {:default nil}
         :approval {:default nil}
         :audit {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ClayAdvisor inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; Clay Plant Operations Governor -- independent censor.
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then phase gate.
      ;; HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :requires-supervisor-approval
                                          :else :standard-escalation))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      ;; Approval handoff -- paused by interrupt-before.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      ;; Hold -- write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer
        :interrupt-before #{:request-approval}})))
