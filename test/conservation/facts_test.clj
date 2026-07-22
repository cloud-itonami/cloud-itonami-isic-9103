(ns conservation.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [conservation.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest ita-has-a-spec-basis
  (is (some? (facts/spec-basis "ITA")))
  (is (string? (:provenance (facts/spec-basis "ITA")))))

(deftest italy-jurisdiction-entry-has-the-same-shape-as-existing-jurisdictions
  (testing "ITA carries the same six keys, populated, as JPN/USA/GBR/DEU -- no fabricated gaps"
    (let [ita (facts/spec-basis "ITA")]
      (is (= "Italy" (:name ita)))
      (is (string? (:owner-authority ita)))
      (is (string? (:legal-basis ita)))
      (is (string? (:national-spec ita)))
      (is (string? (:provenance ita)))
      (is (= 4 (count (:required-evidence ita))) "same four-item evidence checklist shape as every other jurisdiction")
      (is (= (set (keys ita)) (set (keys (facts/spec-basis "JPN"))))
          "ITA's map shape matches an existing jurisdiction's map shape exactly"))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
