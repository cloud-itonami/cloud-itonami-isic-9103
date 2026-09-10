(ns conservation.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [conservation.registry :as r]))

;; ----------------------------- body-condition-out-of-range? -----------------------------

(deftest in-range-when-within-bounds
  (testing "bounds are inclusive"
    (is (not (r/body-condition-out-of-range? {:body-condition-score 4 :bcs-min-healthy 4 :bcs-max-healthy 6})))
    (is (not (r/body-condition-out-of-range? {:body-condition-score 5 :bcs-min-healthy 4 :bcs-max-healthy 6})))
    (is (not (r/body-condition-out-of-range? {:body-condition-score 6 :bcs-min-healthy 4 :bcs-max-healthy 6})))))

(deftest out-of-range-below-minimum
  (is (r/body-condition-out-of-range? {:body-condition-score 2 :bcs-min-healthy 4 :bcs-max-healthy 6})))

(deftest out-of-range-above-maximum
  (is (r/body-condition-out-of-range? {:body-condition-score 8 :bcs-min-healthy 4 :bcs-max-healthy 6})))

(deftest out-of-range-is-false-on-missing-or-non-numeric-fields
  (is (not (r/body-condition-out-of-range? {})))
  (is (not (r/body-condition-out-of-range? {:body-condition-score nil :bcs-min-healthy 4 :bcs-max-healthy 6})))
  (is (not (r/body-condition-out-of-range? {:body-condition-score "2" :bcs-min-healthy 4 :bcs-max-healthy 6}))))

;; ----------------------------- register-specimen-transfer -----------------------------

(deftest specimen-transfer-is-a-draft-not-a-real-transfer
  (let [result (r/register-specimen-transfer "specimen-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest specimen-transfer-assigns-transfer-number
  (let [result (r/register-specimen-transfer "specimen-1" "JPN" 7)]
    (is (= (get result "transfer_number") "JPN-TRN-000007"))
    (is (= (get-in result ["record" "specimen_id"]) "specimen-1"))
    (is (= (get-in result ["record" "kind"]) "specimen-transfer-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest specimen-transfer-validation-rules
  (is (thrown? Exception (r/register-specimen-transfer "" "JPN" 0)))
  (is (thrown? Exception (r/register-specimen-transfer "specimen-1" "" 0)))
  (is (thrown? Exception (r/register-specimen-transfer "specimen-1" "JPN" -1))))

(deftest transfer-history-is-append-only
  (let [c1 (r/register-specimen-transfer "specimen-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-specimen-transfer "specimen-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-TRN-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-TRN-000001" (get-in hist2 [1 "record_id"])))))

;; ----------------------------- register-specimen-release -----------------------------

(deftest specimen-release-is-a-draft-not-a-real-release
  (let [result (r/register-specimen-release "specimen-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest specimen-release-assigns-release-number
  (let [result (r/register-specimen-release "specimen-1" "JPN" 7)]
    (is (= (get result "release_number") "JPN-REL-000007"))
    (is (= (get-in result ["record" "specimen_id"]) "specimen-1"))
    (is (= (get-in result ["record" "kind"]) "specimen-release-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest specimen-release-validation-rules
  (is (thrown? Exception (r/register-specimen-release "" "JPN" 0)))
  (is (thrown? Exception (r/register-specimen-release "specimen-1" "" 0)))
  (is (thrown? Exception (r/register-specimen-release "specimen-1" "JPN" -1))))

(deftest release-history-is-append-only
  (let [d1 (r/register-specimen-release "specimen-1" "JPN" 0)
        hist (r/append [] d1)
        d2 (r/register-specimen-release "specimen-2" "JPN" 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-REL-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-REL-000001" (get-in hist2 [1 "record_id"])))))
