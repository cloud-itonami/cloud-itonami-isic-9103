(ns conservation.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:specimen/transfer`/`:specimen/release` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [conservation.phase :as phase]))

(deftest specimen-transfer-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real specimen transfer"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :specimen/transfer))
          (str "phase " n " must not auto-commit :specimen/transfer")))))

(deftest specimen-release-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real specimen release"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :specimen/release))
          (str "phase " n " must not auto-commit :specimen/release")))))

(deftest welfare-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling KYC/conflict/independence/surveillance/calibration/credential/integrity/patron/authorization/safety-test/inspection/incident-flag screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :welfare/screen))
          (str "phase " n " must not auto-commit :welfare/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":specimen/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:specimen/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :specimen/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :specimen/transfer} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :specimen/release} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :specimen/intake} :commit)))))
