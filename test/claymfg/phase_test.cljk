(ns claymfg.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-maintenance` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [claymfg.phase :as phase]))

(deftest schedule-maintenance-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real maintenance schedule"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-maintenance))
          (str "phase " n " must not auto-commit :schedule-maintenance")))))

(deftest flag-safety-concern-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :flag-safety-concern))
        (str "phase " n " must not auto-commit :flag-safety-concern"))))

(deftest coordinate-shipment-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :coordinate-shipment))
        (str "phase " n " must not auto-commit :coordinate-shipment"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-production-batch carries no physical/financial risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-production-batch} (:auto (get phase/phases 3))))))

(deftest schedule-maintenance-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :schedule-maintenance))
  (is (not (contains? (:writes (get phase/phases 2)) :schedule-maintenance)))
  (is (not (contains? (:writes (get phase/phases 1)) :schedule-maintenance))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-production-batch} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-maintenance} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-shipment} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-production-batch} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-production-batch} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
