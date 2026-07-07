(ns conservation.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [conservation.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Red panda (Ailurus fulgens), juvenile" (:specimen-name (store/specimen s "specimen-1"))))
      (is (= "JPN" (:jurisdiction (store/specimen s "specimen-1"))))
      (is (= 5 (:body-condition-score (store/specimen s "specimen-1"))))
      (is (true? (:welfare-flag-resolved? (store/specimen s "specimen-1"))))
      (is (= 2 (:body-condition-score (store/specimen s "specimen-3"))))
      (is (false? (:welfare-flag-resolved? (store/specimen s "specimen-4"))))
      (is (false? (:transfer-finalized? (store/specimen s "specimen-1"))))
      (is (false? (:released? (store/specimen s "specimen-1"))))
      (is (= ["specimen-1" "specimen-2" "specimen-3" "specimen-4"]
             (mapv :id (store/all-specimens s))))
      (is (nil? (store/welfare-screening-of s "specimen-1")))
      (is (nil? (store/assessment-of s "specimen-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/transfer-history s)))
      (is (= [] (store/release-history s)))
      (is (zero? (store/next-transfer-sequence s "JPN")))
      (is (zero? (store/next-release-sequence s "JPN")))
      (is (false? (store/specimen-already-transferred? s "specimen-1")))
      (is (false? (store/specimen-already-released? s "specimen-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :specimen/upsert
                                 :value {:id "specimen-1" :specimen-name "Red panda (Ailurus fulgens), juvenile"}})
        (is (= "Red panda (Ailurus fulgens), juvenile" (:specimen-name (store/specimen s "specimen-1"))))
        (is (= 5 (:body-condition-score (store/specimen s "specimen-1"))) "unrelated field preserved"))
      (testing "assessment / welfare-screening payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["specimen-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "specimen-1")))
        (store/commit-record! s {:effect :welfare-screening/set :path ["specimen-1"]
                                 :payload {:specimen-id "specimen-1" :verdict :resolved}})
        (is (= {:specimen-id "specimen-1" :verdict :resolved} (store/welfare-screening-of s "specimen-1"))))
      (testing "specimen transfer drafts a transfer record and advances the transfer sequence"
        (store/commit-record! s {:effect :specimen/mark-transferred :path ["specimen-1"]})
        (is (= "JPN-TRN-000000" (get (first (store/transfer-history s)) "record_id")))
        (is (= "specimen-transfer-draft" (get (first (store/transfer-history s)) "kind")))
        (is (true? (:transfer-finalized? (store/specimen s "specimen-1"))))
        (is (= 1 (count (store/transfer-history s))))
        (is (= 1 (store/next-transfer-sequence s "JPN")))
        (is (true? (store/specimen-already-transferred? s "specimen-1")))
        (is (false? (store/specimen-already-transferred? s "specimen-2"))))
      (testing "specimen release drafts a release record and advances the release sequence"
        (store/commit-record! s {:effect :specimen/mark-released :path ["specimen-1"]})
        (is (= "JPN-REL-000000" (get (first (store/release-history s)) "record_id")))
        (is (= "specimen-release-draft" (get (first (store/release-history s)) "kind")))
        (is (true? (:released? (store/specimen s "specimen-1"))))
        (is (= 1 (count (store/release-history s))))
        (is (= 1 (store/next-release-sequence s "JPN")))
        (is (true? (store/specimen-already-released? s "specimen-1")))
        (is (false? (store/specimen-already-released? s "specimen-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/specimen s "nope")))
    (is (= [] (store/all-specimens s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/transfer-history s)))
    (is (= [] (store/release-history s)))
    (is (zero? (store/next-transfer-sequence s "JPN")))
    (is (zero? (store/next-release-sequence s "JPN")))
    (store/with-specimens s {"x" {:id "x" :specimen-name "n" :body-condition-score 5
                                  :bcs-min-healthy 4 :bcs-max-healthy 6
                                  :welfare-flag-resolved? true :transfer-finalized? false
                                  :released? false :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:specimen-name (store/specimen s "x"))))))
