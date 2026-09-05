(ns claymfg.render-html
  "Build-time operator console renderer for the ClayOperationActor.

  WHAT IS REAL HERE
  =================
  Nothing on the generated page is hand-typed domain data. Every row is
  produced by actually executing this repo's own stack at build time:

    1. `claymfg.store/mem-store` + `claymfg.store/sample-data!` seeds the
       SSoT -- the same seed `claymfg.sim` and the test suite use.
    2. `claymfg.operation/build` compiles the real langgraph-clj
       StateGraph (intake -> advise -> govern -> decide ->
       commit | hold | request-approval).
    3. Every scenario below is driven through `langgraph.graph/run*`
       using `claymfg.sim`'s exact calling convention: one call with
       `{:thread-id t}` to start, and -- for anything the phase gate or
       the high-stakes gate escalates -- a second call with
       `{:thread-id t :resume? true}` carrying `{:approval {:status ..}}`,
       because `claymfg.operation/build` compiles the graph with
       `interrupt-before #{:request-approval}`.
    4. AFTER the runs, the entity rows are read back OUT of the store
       through the `claymfg.store/Store` protocol (`all-batches`,
       `all-equipment`, `all-maintenance`, `shipment`, `safety-concerns`,
       `ledger`, `maintenance-history`, `shipment-history`). The
       `after` columns are therefore the ground truth the run actually
       left behind, not a prediction of it -- e.g. batch-001's
       `:shipped-weight-kg` moves 10000.0 -> 15000.0 only because the
       ship-1 shipment really committed through the graph, and
       kiln-001 gains a `:last-scheduled-maintenance-date` only because
       mnt-1 really committed.

  INPUT PROVENANCE
  ================
  Every entity this page references is resolved against the seed. The
  `Input provenance` table classifies each id the scenarios use as
  either `seed` (already present in `sample-data!`: batch-001,
  batch-002, batch-003, kiln-001, extruder-002) or `run-created draft`
  (an id the run itself brings into existence -- the seed deliberately
  starts with `:maintenance {}` and `:shipments {}`, so a maintenance
  window / shipment / safety concern subject like mnt-1 or ship-1 is
  the NEW record's own id, exactly as in `claymfg.sim`). No id is
  invented: every `:equipment-id` and `:batch-id` reference points at a
  seeded row, and that is asserted on the page itself.

  Only fields that exist in the domain model are rendered. Batch,
  equipment, maintenance, shipment and safety-concern columns are the
  keys `claymfg.store` actually writes.

  DERIVED, NOT TRANSCRIBED
  ========================
  The gate tables read live vars rather than restating prose:
  `claymfg.phase/phases` (per-phase :writes / :auto), `phase/write-ops`,
  `phase/default-phase`, `governor/allowed-ops`,
  `governor/allowed-proposal-effects`, `governor/high-stakes`,
  `governor/confidence-floor`, `registry/valid-product-types` and the
  four registry plausibility bounds. The per-op `effect` / `stake` /
  `confidence` columns come from the proposals the advisor actually
  emitted during these runs, and the HARD/SOFT override column comes
  from each run's own `:hard?` verdict. The one classification that is
  NOT a live value is called out in a comment on
  `permanently-blocked-rules` below.

  DETERMINISM
  ===========
  No timestamp, no wall clock, no randomness, no environment lookup.
  The advisor is `claymfg.advisor/mock-advisor` (deterministic by
  construction), sets are sorted before rendering, and every table
  renders an explicit, fixed column list rather than map seq order.
  Two consecutive runs are byte-identical; verified with
  `clojure -M:dev:render-html /tmp/a.html && clojure -M:dev:render-html
  /tmp/b.html && diff /tmp/a.html /tmp/b.html`.

  Usage: `clojure -M:dev:render-html [out-path]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [claymfg.governor :as governor]
            [claymfg.operation :as op]
            [claymfg.phase :as phase]
            [claymfg.registry :as registry]
            [claymfg.store :as store]))

;; ----------------------------- operator context -----------------------------

(def ^:private coordinator
  "The operator context every scenario runs under. `:phase` is read from
  `claymfg.phase/default-phase` rather than hard-coded, so this console
  follows the actor's own declared rollout phase."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

;; FIXED DOCUMENTATION -- deliberately not derived.
;; `claymfg.governor` implements each check as a private fn and does not
;; expose its rule catalogue as data, so there is no live var to read
;; this from. These are the two rules whose governor docstring marks
;; them "HARD, PERMANENT, unconditional" (checks 3 and 4): a proposal
;; effect outside the closed allowlist, and an :actuate-kiln-line? true
;; maintenance proposal. Every HARD violation already blocks override at
;; runtime -- that part IS derived, from each run's own :hard? verdict.
;; This set only marks which of them the governor calls PERMANENT.
(def ^:private permanently-blocked-rules
  #{:kiln-line-control-blocked :kiln-line-actuate-blocked})

;; ----------------------------- scenarios -----------------------------

(def ^:private scenarios
  "One entry = one graph run, in execution order. `:approval` (when
  present) is the status a human hands back on the `resume?` call --
  only reachable for dispositions the graph actually escalates."
  [;; ---------- clean lifecycle: intake -> maintenance -> safety -> shipment ----------
   {:thread "t1" :lane :lifecycle
    :title "生産バッチ記録更新 batch-001 (clean patch)"
    :expect "phase-3 :auto -> 自動コミット"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :solid-brick :last-assessed "2026-07-14"}}}

   {:thread "t2" :lane :lifecycle
    :title "保守作業予定 mnt-1 on kiln-001 (検証済み・登録済みトンネル窯)"
    :expect "governor clean だが :auto 非対象 -> エスカレーション -> 承認"
    :approval :approved :by "coord-1"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kiln-001" :maintenance-type :kiln-lining-inspection
                      :scheduled-date "2026-08-01" :actuate-kiln-line? false}}}

   {:thread "t3" :lane :lifecycle
    :title "安全懸念報告 concern-1 on kiln-001"
    :expect "high-stakes -> 常にエスカレーション -> 承認"
    :approval :approved :by "coord-1"
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "kiln-001" :severity :moderate
                      :description "トンネル窯出口付近の輻射熱上昇、粉塵滞留の兆候"}}}

   {:thread "t4" :lane :lifecycle
    :title "出荷調整 ship-1 on batch-001 (5000kg / 空き容量内)"
    :expect ":auto 非対象 -> エスカレーション -> 承認"
    :approval :approved :by "coord-1"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :weight-kg 5000.0
                      :destination "buyer-yard-north"}}}

   ;; ---------- SOFT gate, other outcome: the same escalation, refused ----------
   {:thread "t5" :lane :soft
    :title "安全懸念報告 concern-2 on extruder-002"
    :expect "high-stakes -> エスカレーション -> 承認者が却下 -> HOLD"
    :approval :rejected :by "coord-1"
    :request {:op :flag-safety-concern :effect :propose :subject "concern-2"
              :value {:equipment-id "extruder-002" :severity :minor
                      :description "押出成形機まわりの粉塵堆積、点検の要否を確認したい"}}}

   ;; ---------- HARD holds ----------
   {:thread "t6" :lane :hard
    :title "request :effect が :propose でない (:direct-write)"
    :expect "HARD -- proposal-only 契約違反、最初に評価される"
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-type :solid-brick}}}

   {:thread "t7" :lane :hard
    :title "許可リスト外の操作 :actuate-kiln-line"
    :expect "HARD -- 未知 op + 提案 effect が閉じた許可リスト外"
    :request {:op :actuate-kiln-line :effect :propose :subject "batch-001"}}

   {:thread "t8" :lane :hard
    :title "保守作業予定 mnt-2 on extruder-002 (未検証・未登録)"
    :expect "HARD -- 設備の verified?/registered? を governor が独立再検証"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "extruder-002" :maintenance-type :die-inspection
                      :scheduled-date "2026-08-01" :actuate-kiln-line? false}}}

   {:thread "t9" :lane :hard
    :title "出荷調整 ship-2 on batch-003 (未検証・未登録バッチ)"
    :expect "HARD -- バッチの verified?/registered? を governor が独立再検証"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-003" :weight-kg 1000.0
                      :destination "buyer-yard-south"}}}

   {:thread "t10" :lane :hard
    :title "出荷調整 ship-3 on batch-002 (7500 + 1000 > 8000)"
    :expect "HARD -- 空き容量を governor が独立再計算"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-002" :weight-kg 1000.0
                      :destination "buyer-yard-east"}}}

   {:thread "t11" :lane :hard
    :title "保守作業予定 mnt-3 on kiln-001 with :actuate-kiln-line? true"
    :expect "HARD かつ PERMANENT -- 人間の承認画面に到達しない"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "kiln-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01" :actuate-kiln-line? true}}}

   {:thread "t12" :lane :hard
    :title "保守作業予定 mnt-1 を再度スケジュール"
    :expect "HARD -- :scheduled? 事実に基づく二重予約防止"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kiln-001" :maintenance-type :kiln-lining-inspection
                      :scheduled-date "2026-08-01" :actuate-kiln-line? false}}}

   {:thread "t13" :lane :hard
    :title "生産バッチ記録更新 batch-001 に捏造 product-type"
    :expect "HARD -- 閉じた既知集合の外"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :unobtainium-brick}}}

   {:thread "t14" :lane :hard
    :title "生産バッチ記録更新 batch-001 に非現実的な寸法偏差 999.0%"
    :expect "HARD -- 物理的妥当範囲外"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:dimensional-deviation-percent 999.0}}}

   {:thread "t15" :lane :hard
    :title "生産バッチ記録更新 batch-001 に非現実的な不良率 999.0%"
    :expect "HARD -- 物理的妥当範囲外"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:defect-rate-percent 999.0}}}])

;; ----------------------------- execution -----------------------------

(defn- run-scenario!
  "Drive one scenario through the compiled actor, exactly as
  `claymfg.sim` does: start with `{:thread-id t}`, and resume the
  interrupt with `{:thread-id t :resume? true}` when the scenario
  supplies an approval decision."
  [actor {:keys [thread request approval by] :as scenario}]
  (let [started  (g/run* actor {:request request :context coordinator}
                         {:thread-id thread})
        resumed  (when approval
                   (g/run* actor {:approval {:status approval :by by}}
                           {:thread-id thread :resume? true}))
        final    (or resumed started)]
    (assoc scenario
           :proposal    (get-in started [:state :proposal])
           :verdict     (get-in started [:state :verdict])
           :first-disposition (get-in started [:state :disposition])
           :final-disposition (get-in final [:state :disposition])
           :resumed?    (some? resumed)
           :basis       (->> (get-in final [:state :audit])
                             (filter #(#{:governor-hold :approval-rejected} (:t %)))
                             (mapcat :basis)
                             distinct
                             vec))))

(defn- refs-of
  "Every entity id a request names -- its subject plus any
  `:equipment-id` / `:batch-id` reference in its proposal value."
  [request]
  (->> [(:subject request)
        (:equipment-id (:value request))
        (:batch-id (:value request))]
       (remove nil?)
       distinct
       vec))

;; ----------------------------- html helpers -----------------------------

(defn- esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- fmt
  "Display form for a domain value. Keywords keep their namespace,
  nil renders as an em dash, everything else round-trips via pr-str so
  doubles and collections print exactly as the domain holds them."
  [v]
  (cond
    (nil? v)         "—"
    (keyword? v)     (str v)
    (string? v)      v
    (boolean? v)     (str v)
    (number? v)      (pr-str v)
    (coll? v)        (if (seq v) (str/join ", " (map fmt v)) "—")
    :else            (pr-str v)))

(defn- c
  "An escaped table cell."
  [v] (esc (fmt v)))

(defn- span [class v]
  (str "<span class=\"" class "\">" (esc (fmt v)) "</span>"))

(defn- sorted-names
  "Deterministic rendering of a set of keywords."
  [s] (sort-by pr-str s))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>"
       (str/join (for [r rows]
                   (str "<tr>" (str/join (map #(str "<td>" % "</td>") r)) "</tr>")))
       "</tbody></table>"))

(defn- disposition-cell [d]
  (span (case d :commit "ok" :escalate "warn" :hold "err" "muted") d))

;; ----------------------------- sections -----------------------------

(def ^:private batch-cols
  [:id :product-type :material :weight-kg :dimensional-deviation-percent
   :defect-rate-percent :verified? :registered? :shipped-weight-kg :last-assessed])

(def ^:private equipment-cols
  [:id :kind :verified? :registered? :last-maintenance-date
   :last-scheduled-maintenance-date])

(defn- entity-table [cols rows]
  (table (map name cols)
         (for [r rows] (for [k cols] (c (get r k))))))

(defn- diff-table
  "Before/after for one entity kind, so the page shows what the run
  actually changed in the SSoT rather than asserting that it did."
  [cols before after]
  (let [by-id (fn [rows] (into {} (map (juxt :id identity)) rows))
        b (by-id before) a (by-id after)]
    (table (cons "id" (for [k cols :when (not= k :id)] (name k)))
           (for [id (sort (distinct (concat (keys b) (keys a))))]
             (cons (c id)
                   (for [k cols :when (not= k :id)]
                     (let [bv (get-in b [id k]) av (get-in a [id k])]
                       (if (= bv av)
                         (c av)
                         (str (span "muted" bv) " → " (span "ok" av))))))))))

(defn- phase-section []
  (table ["phase" "label" ":writes (書き込み可)" ":auto (governor clean なら自動コミット)"]
         (for [p (sort (keys phase/phases))
               :let [{:keys [label writes auto]} (get phase/phases p)]]
           [(c p)
            (str (c label)
                 (when (= p phase/default-phase)
                   (str " " (span "ok" "← default-phase"))))
            (c (sorted-names writes))
            (if (seq auto) (c (sorted-names auto)) (span "muted" "(なし)"))])))

(defn- ops-section [results]
  (let [phase3-auto (:auto (get phase/phases phase/default-phase))
        ;; the proposal each op actually produced on its clean-lane run
        clean (into {} (for [r (reverse results)
                             :when (= :lifecycle (:lane r))]
                         [(get-in r [:request :op]) (:proposal r)]))]
    (table ["op" "write-op?" (str "phase " phase/default-phase " :auto?")
            "観測された proposal :effect" "effect が許可リスト内?"
            "観測された :stake" "high-stakes?" "観測された :confidence"]
           (for [o (sorted-names governor/allowed-ops)
                 :let [p (get clean o)
                       eff (:effect p)
                       stake (:stake p)]]
             [(c o)
              (if (contains? phase/write-ops o) (span "ok" "yes") (span "muted" "no"))
              (if (contains? phase3-auto o) (span "ok" "yes") (span "warn" "no"))
              (if p (c eff) (span "muted" "(未観測)"))
              (cond (nil? p) (span "muted" "—")
                    (contains? governor/allowed-proposal-effects eff) (span "ok" "yes")
                    :else (span "err" "no"))
              (if (and p (some? stake)) (c stake) (span "muted" "nil"))
              (if (contains? governor/high-stakes stake) (span "warn" "yes") (span "muted" "no"))
              (if p (c (:confidence p)) (span "muted" "—"))]))))

(defn- bounds-section []
  (table ["検査" "許容値 (live var)"]
         [[(c ":product-type") (c (sorted-names registry/valid-product-types))]
          [(c ":dimensional-deviation-percent")
           (c (str registry/dimensional-deviation-min-percent
                   " .. " registry/dimensional-deviation-max-percent))]
          [(c ":defect-rate-percent")
           (c (str registry/defect-rate-min-percent
                   " .. " registry/defect-rate-max-percent))]
          [(c "confidence floor") (c governor/confidence-floor)]]))

(defn- provenance-section [seeded-ids]
  (table ["thread" "op" "参照 id" "出所"]
         (for [s scenarios
               id (refs-of (:request s))]
           [(c (:thread s))
            (c (get-in s [:request :op]))
            (c id)
            (if (contains? seeded-ids id)
              (span "ok" "seed (sample-data!)")
              (span "muted" "run-created draft id"))])))

(defn- runs-section [results]
  (table ["thread" "シナリオ" "op" "初回 disposition" "承認"
          "最終 disposition" "HARD?" "発火した rule"]
         (for [r results
               :let [hard? (get-in r [:verdict :hard?])]]
           [(c (:thread r))
            (c (:title r))
            (c (get-in r [:request :op]))
            (disposition-cell (:first-disposition r))
            (cond (not (:resumed? r)) (span "muted" "(到達せず)")
                  (= :approved (:approval r)) (span "ok" ":approved")
                  :else (span "err" ":rejected"))
            (disposition-cell (:final-disposition r))
            (if hard?
              (span "err" (if (some permanently-blocked-rules (:basis r))
                            "HARD / PERMANENT"
                            "HARD"))
              (span "muted" "no (SOFT)"))
            (if (seq (:basis r))
              (str/join ", " (map #(span (if (contains? permanently-blocked-rules %) "critical" "err") %)
                                  (:basis r)))
              (span "ok" "(なし)"))])))

(defn- violations-section [results]
  (table ["thread" "rule" "override" "governor の説明 (:detail)"]
         (for [r results
               v (get-in r [:verdict :violations])]
           [(c (:thread r))
            (span (if (contains? permanently-blocked-rules (:rule v)) "critical" "err") (:rule v))
            (if (contains? permanently-blocked-rules (:rule v))
              (span "critical" "不可 (PERMANENT)")
              (span "err" "不可 (HARD)"))
            (c (:detail v))])))

(defn- ledger-section [facts]
  (table ["#" ":t" ":op" ":subject" ":actor" ":disposition" ":basis" ":summary"]
         (map-indexed
          (fn [i f]
            [(c (inc i))
             (span (if (= :committed (:t f)) "ok" "err") (:t f))
             (c (:op f))
             (c (:subject f))
             (c (:actor f))
             (disposition-cell (:disposition f))
             (c (:basis f))
             (c (or (:summary f) "—"))])
          facts)))

;; ----------------------------- page -----------------------------

(defn- page [{:keys [results seed-batches seed-equipment
                     batches equipment maintenance shipments concerns
                     ledger maintenance-drafts shipment-drafts seeded-ids]}]
  (let [committed (count (filter #(= :committed (:t %)) ledger))
        held      (count (filter #(not= :committed (:t %)) ledger))]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<title>Operator console — ClayOperationActor (ISIC 2392)</title>\n"
     "<meta name=\"description\" content=\""
     (esc "cloud-itonami-isic-2392 の ClayOperationActor を実際に実行して生成した運用コンソール。全行が実行結果。")
     "\">\n"
     "<style>" (skin/dds+skin) "</style>\n"
     "</head>\n<body>\n"

     "<div class=\"bar\">"
     "<span class=\"badge\">ISIC 2392</span>"
     "<span class=\"badge\">clay-plant-operations-governor</span>"
     "<span class=\"badge\">phase " (esc phase/default-phase) " "
     (esc (:label (get phase/phases phase/default-phase))) "</span>"
     "</div>\n"

     "<h1>Operator console — ClayOperationActor</h1>\n"
     "<p class=\"subtitle\">"
     (esc "粘土建材(れんが・瓦)工場プラント運用コーディネーション actor。このページは静的な文書ではなく、ビルド時に actor を実際に実行した結果です。")
     "</p>\n"

     "<div class=\"banner\">\n<h3>" (esc "このページの全行は実行結果です") "</h3>\n<ul>"
     "<li>" (esc "SSoT は claymfg.store/sample-data! の実シード。") "</li>"
     "<li>" (esc "claymfg.operation/build が組んだ langgraph-clj StateGraph を langgraph.graph/run* で駆動。承認は claymfg.sim と同じ resume? 規約(interrupt-before #{:request-approval})。")
     "</li>"
     "<li>" (esc (str "実行シナリオ " (count results) " 件 / 監査台帳 " (count ledger)
                      " 件(コミット " committed " ・ホールド " held " )。"))
     "</li>"
     "<li>" (esc "エンティティ行は実行『後』に Store プロトコル越しに読み戻した値であり、予測ではありません。") "</li>"
     "<li>" (esc "タイムスタンプ・乱数・実時刻を一切含みません(2 回続けて実行するとバイト単位で一致)。") "</li>"
     "</ul>\n</div>\n"

     "<h2>1. " (esc "入力の出所 (input provenance)") "</h2>\n"
     "<p>" (esc "シナリオが名指しするすべての id と、その出所。seed 行は claymfg.store/sample-data! に実在する行で、それ以外はこの実行自身が新規に起こす下書き記録の id です(シードは :maintenance {} / :shipments {} で始まります)。")
     "</p>\n"
     (provenance-section seeded-ids)

     "<h3>" (esc "シードされたバッチ (実行前)") "</h3>\n"
     (entity-table batch-cols seed-batches)
     "<h3>" (esc "シードされた設備 (実行前)") "</h3>\n"
     (entity-table equipment-cols seed-equipment)

     "<h2>2. " (esc "ゲート定義 (live var から導出)") "</h2>\n"
     "<p>" (esc "以下の表は散文の転記ではなく claymfg.phase / claymfg.governor / claymfg.registry の実際の var を読んだものです。")
     "</p>\n"
     "<h3>" (esc "ロールアウトフェーズ") " <code>claymfg.phase/phases</code></h3>\n"
     (phase-section)
     "<p class=\"muted\">"
     (esc ":schedule-maintenance はどのフェーズの :auto にも属しません — 上の表がその不変条件そのものです。")
     "</p>\n"
     "<h3>" (esc "操作ゲート") " <code>claymfg.governor/allowed-ops</code></h3>\n"
     (ops-section results)
     "<h3>" (esc "妥当性の境界") " <code>claymfg.registry</code></h3>\n"
     (bounds-section)

     "<h2>3. " (esc "実行シナリオ") "</h2>\n"
     "<p>" (esc "1 行 = 1 グラフ実行。HARD 違反は人間に到達しません。SOFT (エスカレーション) は人間が承認も却下もできます — t5 が却下側の実行です。")
     "</p>\n"
     (runs-section results)

     "<h2>4. " (esc "HARD ホールドの内訳") "</h2>\n"
     "<p>" (esc "governor が実際に返した verdict の :violations。:detail は governor 自身が生成した文字列です。")
     "</p>\n"
     (violations-section results)

     "<h2>5. " (esc "監査台帳 (append-only)") "</h2>\n"
     "<p>" (esc "claymfg.store/ledger をそのまま読んだもの。行の順序は実行順です。") "</p>\n"
     (ledger-section ledger)

     "<h2>6. " (esc "実行が SSoT に残したもの") "</h2>\n"
     "<h3>" (esc "バッチ (実行前 → 実行後)") "</h3>\n"
     (diff-table batch-cols seed-batches batches)
     "<h3>" (esc "設備 (実行前 → 実行後)") "</h3>\n"
     (diff-table equipment-cols seed-equipment equipment)

     "<h3>" (esc "保守作業予定") "</h3>\n"
     (if (seq maintenance)
       (table ["id" "equipment-id" "maintenance-type" "scheduled-date"
               "actuate-kiln-line?" "scheduled?" "maintenance-number"]
              (for [m maintenance]
                [(c (:id m)) (c (:equipment-id m)) (c (:maintenance-type m))
                 (c (:scheduled-date m)) (c (:actuate-kiln-line? m))
                 (c (:scheduled? m)) (c (:maintenance-number m))]))
       (str "<p class=\"muted\">" (esc "(なし)") "</p>\n"))

     "<h3>" (esc "出荷調整") "</h3>\n"
     (if (seq shipments)
       (table ["id" "batch-id" "weight-kg" "destination" "shipment-number"]
              (for [s shipments]
                [(c (:id s)) (c (:batch-id s)) (c (:weight-kg s))
                 (c (:destination s)) (c (:shipment-number s))]))
       (str "<p class=\"muted\">" (esc "(なし)") "</p>\n"))

     "<h3>" (esc "安全懸念") "</h3>\n"
     (if (seq concerns)
       (table ["id" "equipment-id" "severity" "description"]
              (for [s concerns]
                [(c (:id s)) (c (:equipment-id s)) (c (:severity s)) (c (:description s))]))
       (str "<p class=\"muted\">" (esc "(なし)") "</p>\n"))

     "<h3>" (esc "下書き記録 (claymfg.registry)") "</h3>\n"
     (table ["record_id" "kind" "対象"]
            (concat
             (for [r maintenance-drafts]
               [(c (get r "record_id")) (c (get r "kind"))
                (c (str (get r "maintenance_id") " / " (get r "equipment_id")))])
             (for [r shipment-drafts]
               [(c (get r "record_id")) (c (get r "kind"))
                (c (get r "shipment_id"))])))
     "<p class=\"muted\">"
     (esc "下書き記録の証明書はすべて未署名 (status draft-unsigned) です — 署名は人間の工場責任者の行為であり、この actor の行為ではありません。")
     "</p>\n"

     "<footer>\n<p>"
     (esc "生成: clojure -M:dev:render-html (src/claymfg/render_html.clj)。")
     (esc "捏造された行はありません — 全行が上記の実行から読み出したものです。")
     "</p>\n<p>"
     (esc "cloud-itonami-isic-2392 · Open occupation blueprint · AGPL-3.0-or-later")
     "</p>\n</footer>\n</body>\n</html>\n")))

;; ----------------------------- main -----------------------------

(defn render
  "Execute the real actor over the real seed and return the console HTML."
  []
  (let [db    (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        ;; snapshot the seed BEFORE any run, so the after-columns can be
        ;; shown as a diff rather than asserted
        seed-batches   (vec (store/all-batches db))
        seed-equipment (vec (store/all-equipment db))
        seeded-ids     (into #{} (map :id) (concat seed-batches seed-equipment))
        results        (mapv #(run-scenario! actor %) scenarios)
        ;; the Store protocol has no `all-shipments`; look up exactly the
        ;; shipment ids the scenarios proposed and keep the ones that
        ;; actually committed.
        shipment-ids   (->> scenarios
                            (filter #(= :coordinate-shipment (get-in % [:request :op])))
                            (map #(get-in % [:request :subject])))
        shipments      (vec (keep #(store/shipment db %) shipment-ids))]
    (page {:results             results
           :seed-batches        seed-batches
           :seed-equipment      seed-equipment
           :seeded-ids          seeded-ids
           :batches             (vec (store/all-batches db))
           :equipment           (vec (store/all-equipment db))
           :maintenance         (vec (store/all-maintenance db))
           :shipments           shipments
           :concerns            (vec (store/safety-concerns db))
           :ledger              (vec (store/ledger db))
           :maintenance-drafts  (vec (store/maintenance-history db))
           :shipment-drafts     (vec (store/shipment-history db))})))

(defn -main [& [out-path]]
  (let [out  (or out-path "docs/samples/operator-console.html")
        html (render)]
    (io/make-parents (io/file out))
    (spit out html)
    (println (str "wrote " out " (" (count (.getBytes ^String html "UTF-8")) " bytes)"))))
