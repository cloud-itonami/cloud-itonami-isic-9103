(ns conservation.governor-contract-test
  "The governor contract as executable tests -- the zoo/botanical-
  garden/conservation analog of `cloud-itonami-isic-6512`'s `casualty.
  governor-contract-test`. The single invariant under test:

    ConservationOps-LLM never transfers or releases a specimen the
    Conservation Governor would reject, `:specimen/transfer`/
    `:specimen/release` NEVER auto-commit at any phase, `:specimen/
    intake` (no direct capital risk) MAY auto-commit when clean, and
    every decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [conservation.store :as store]
            [conservation.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :conservationist :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- screen!
  "Walks `subject` through welfare screening -> approve, leaving a
  screening on file. Only safe to call for a specimen whose welfare
  flag is already resolved -- an unresolved flag HARD-holds the screen
  itself (see `welfare-flag-unresolved-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :welfare/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :specimen/intake :subject "specimen-1"
                   :patch {:id "specimen-1" :specimen-name "Red panda (Ailurus fulgens), juvenile"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Red panda (Ailurus fulgens), juvenile" (:specimen-name (store/specimen db "specimen-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "specimen-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "specimen-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "specimen-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "specimen-1")) "no assessment written"))))

(deftest specimen-transfer-without-assessment-is-held
  (testing "specimen/transfer before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :specimen/transfer :subject "specimen-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest body-condition-out-of-range-is-held
  (testing "a specimen whose body-condition-score falls outside its own healthy range -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "specimen-3")
          res (exec-op actor "t5" {:op :specimen/transfer :subject "specimen-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:body-condition-out-of-range} (-> (store/ledger db) last :basis)))
      (is (empty? (store/transfer-history db))))))

(deftest welfare-flag-unresolved-is-held-and-unoverridable
  (testing "an unresolved welfare flag on a specimen -> HOLD, and never reaches request-approval -- exercised via :welfare/screen DIRECTLY, not via an actuation op against an unscreened specimen (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's and museum's ADR-0001)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :welfare/screen :subject "specimen-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:welfare-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/welfare-screening-of db "specimen-4")) "no clearance written"))))

(deftest specimen-transfer-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, in-range, welfare-clear specimen still ALWAYS interrupts for human approval -- actuation/transfer-specimen is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "specimen-1")
          _ (screen! actor "t7pre2" "specimen-1")
          r1 (exec-op actor "t7" {:op :specimen/transfer :subject "specimen-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, transfer record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:transfer-finalized? (store/specimen db "specimen-1"))))
          (is (= 1 (count (store/transfer-history db))) "one draft transfer record"))))))

(deftest specimen-release-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, welfare-clear specimen still ALWAYS interrupts for human approval -- actuation/release-specimen is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "specimen-1")
          _ (screen! actor "t8pre2" "specimen-1")
          r1 (exec-op actor "t8" {:op :specimen/release :subject "specimen-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, release record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:released? (store/specimen db "specimen-1"))))
          (is (= 1 (count (store/release-history db))) "one draft release record"))))))

(deftest specimen-transfer-double-transfer-is-held
  (testing "transferring the same specimen twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "specimen-1")
          _ (screen! actor "t9pre2" "specimen-1")
          _ (exec-op actor "t9a" {:op :specimen/transfer :subject "specimen-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :specimen/transfer :subject "specimen-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-transferred} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/transfer-history db))) "still only the one earlier transfer"))))

(deftest specimen-release-double-release-is-held
  (testing "releasing the same specimen twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "specimen-1")
          _ (screen! actor "t10pre2" "specimen-1")
          _ (exec-op actor "t10a" {:op :specimen/release :subject "specimen-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :specimen/release :subject "specimen-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-released} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/release-history db))) "still only the one earlier release"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :specimen/intake :subject "specimen-1"
                          :patch {:id "specimen-1" :specimen-name "Red panda (Ailurus fulgens), juvenile"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "specimen-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
