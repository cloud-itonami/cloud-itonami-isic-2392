(ns claymfg.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `claymfg.store` ns docstring for why a second (Datomic-backed)
  backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [claymfg.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/batch s "batch-001"))))
    (is (true? (:registered? (store/batch s "batch-001"))))
    (is (true? (:verified? (store/batch s "batch-002"))))
    (is (true? (:registered? (store/batch s "batch-002"))))
    (is (false? (:verified? (store/batch s "batch-003"))))
    (is (false? (:registered? (store/batch s "batch-003"))))
    (is (= ["batch-001" "batch-002" "batch-003"] (mapv :id (store/all-batches s))))
    (is (true? (:verified? (store/equipment-unit s "kiln-001"))))
    (is (true? (:registered? (store/equipment-unit s "kiln-001"))))
    (is (false? (:verified? (store/equipment-unit s "extruder-002"))))
    (is (false? (:registered? (store/equipment-unit s "extruder-002"))))
    (is (= ["extruder-002" "kiln-001"] (mapv :id (store/all-equipment s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/maintenance-history s)))
    (is (= [] (store/shipment-history s)))
    (is (= [] (store/safety-concerns s)))
    (is (zero? (store/next-maintenance-sequence s)))
    (is (zero? (store/next-shipment-sequence s)))
    (is (false? (store/maintenance-already-scheduled? s "mnt-1")))
    (is (nil? (store/maintenance s "mnt-1")))))

(deftest fresh-store-has-no-batches-or-equipment
  (let [s (store/mem-store)]
    (is (= [] (store/all-batches s)))
    (is (nil? (store/batch s "batch-001")))
    (is (= [] (store/all-equipment s)))
    (is (nil? (store/equipment-unit s "kiln-001")))))

(deftest batch-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :batch/upsert :path ["batch-001"]
                             :value {:product-type :roof-tile}})
    (is (= :roof-tile (:product-type (store/batch s "batch-001"))))
    (is (true? (:verified? (store/batch s "batch-001"))) "unrelated field preserved")
    (is (true? (:registered? (store/batch s "batch-001"))) "unrelated field preserved")))

(deftest maintenance-schedule-commits-and-advances-sequence
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly, matching the discipline the actor's own :commit node relies on"
    (let [s (seeded)]
      (store/commit-record! s {:effect :maintenance/schedule :path ["mnt-1"]
                               :value {:equipment-id "kiln-001" :maintenance-type :kiln-lining-inspection
                                       :scheduled-date "2026-08-01"}})
      (is (= "MNT-000000" (get (first (store/maintenance-history s)) "record_id")))
      (is (= "maintenance-schedule-draft" (get (first (store/maintenance-history s)) "kind")))
      (is (true? (:scheduled? (store/maintenance s "mnt-1"))))
      (is (= "kiln-001" (:equipment-id (store/maintenance s "mnt-1"))))
      (is (= 1 (count (store/maintenance-history s))))
      (is (= 1 (store/next-maintenance-sequence s)))
      (is (true? (store/maintenance-already-scheduled? s "mnt-1")))
      (is (= "MNT-000000" (:maintenance-number (store/maintenance s "mnt-1")))))))

(deftest safety-concern-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-1"]
                             :value {:equipment-id "kiln-001" :severity :moderate}})
    (is (= 1 (count (store/safety-concerns s))))
    (is (= :moderate (:severity (first (store/safety-concerns s)))))
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-2"]
                             :value {:equipment-id "extruder-002" :severity :high}})
    (is (= 2 (count (store/safety-concerns s))) "append-only")))

(deftest shipment-propose-commits-and-advances-sequence-and-batch-weight
  (let [s (seeded)]
    (store/commit-record! s {:effect :shipment/propose :path ["ship-1"]
                             :value {:batch-id "batch-001" :weight-kg 5000.0
                                     :destination "buyer-yard-north"}})
    (is (= "SHP-000000" (get (first (store/shipment-history s)) "record_id")))
    (is (= "shipment-coordination-draft" (get (first (store/shipment-history s)) "kind")))
    (is (= 1 (count (store/shipment-history s))))
    (is (= 1 (store/next-shipment-sequence s)))
    (is (= "SHP-000000" (:shipment-number (store/shipment s "ship-1"))))
    (is (= 15000.0 (:shipped-weight-kg (store/batch s "batch-001")))
        "10000.0 seeded + 5000.0 committed")))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
