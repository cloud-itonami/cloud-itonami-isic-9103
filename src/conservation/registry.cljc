(ns conservation.registry
  "Pure-function specimen-transfer + specimen-release record
  construction -- an append-only zoo/botanical-garden book-of-record
  draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a specimen-transfer or
  specimen-release reference number -- every institution/jurisdiction
  assigns its own reference format. This namespace does NOT invent
  one; it builds a jurisdiction-scoped sequence number and validates
  the record's required fields, the same honest, non-fabricating
  discipline `conservation.facts` uses.

  `body-condition-out-of-range?`/`bcs-min-healthy`/`bcs-max-healthy`
  is the SECOND check in this fleet to combine BOTH directions in ONE
  check (established by `testlab.registry/within-tolerance?`), but the
  FIRST to apply per-ENTITY acceptance bounds representing a species-
  specific healthy range (a specimen's own `:bcs-min-healthy`/
  `:bcs-max-healthy` fields) rather than per-test-protocol bounds. `4`/
  `6` on a 1-9 body-condition-scoring scale is a single representative
  'ideal' range commonly referenced in veterinary/zoo body-condition-
  scoring practice (the scale itself varies by species and
  institution), not a species-by-species survey (see `conservation.
  facts`'s own docstring for the honest scope this makes).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real collection-management system. It builds the RECORD
  an institution would keep, not the act of transferring or releasing
  the specimen itself (that is `conservation.operation`'s `:specimen/
  transfer`/`:specimen/release`, always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  institution's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn body-condition-out-of-range?
  "Does `specimen`'s own `:body-condition-score` fall OUTSIDE its own
  `:bcs-min-healthy`/`:bcs-max-healthy` range (inclusive bounds are
  healthy)? A pure ground-truth check against the specimen's own
  permanent fields -- see ns docstring for the honest simplification
  this makes (not a full veterinary body-condition assessment)."
  [{:keys [body-condition-score bcs-min-healthy bcs-max-healthy]}]
  (and (number? body-condition-score) (number? bcs-min-healthy) (number? bcs-max-healthy)
       (or (< (double body-condition-score) (double bcs-min-healthy))
           (> (double body-condition-score) (double bcs-max-healthy)))))

(defn register-specimen-transfer
  "Validate + construct the SPECIMEN-TRANSFER registration DRAFT -- the
  institution's own legal act of transferring a real living specimen
  to another institution. Pure function -- does not touch any real
  collection-management system; it builds the RECORD an institution
  would keep. `conservation.governor` independently re-verifies the
  specimen's own body-condition sufficiency and welfare-flag status,
  and blocks a double-transfer of the same specimen, before this is
  ever allowed to commit."
  [specimen-id jurisdiction sequence]
  (when-not (and specimen-id (not= specimen-id ""))
    (throw (ex-info "specimen-transfer: specimen_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "specimen-transfer: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "specimen-transfer: sequence must be >= 0" {})))
  (let [transfer-number (str (str/upper-case jurisdiction) "-TRN-" (zero-pad sequence 6))
        record {"record_id" transfer-number
                "kind" "specimen-transfer-draft"
                "specimen_id" specimen-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "transfer_number" transfer-number
     "certificate" (unsigned-certificate "SpecimenTransfer" transfer-number transfer-number)}))

(defn register-specimen-release
  "Validate + construct the SPECIMEN-RELEASE registration DRAFT -- the
  institution's own legal act of releasing a real living specimen
  (e.g. to the wild or a rehabilitation program). Pure function --
  does not touch any real collection-management system; it builds the
  RECORD an institution would keep. `conservation.governor`
  independently re-verifies the specimen's own body-condition
  sufficiency and welfare-flag status, and blocks a double-release of
  the same specimen, before this is ever allowed to commit."
  [specimen-id jurisdiction sequence]
  (when-not (and specimen-id (not= specimen-id ""))
    (throw (ex-info "specimen-release: specimen_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "specimen-release: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "specimen-release: sequence must be >= 0" {})))
  (let [release-number (str (str/upper-case jurisdiction) "-REL-" (zero-pad sequence 6))
        record {"record_id" release-number
                "kind" "specimen-release-draft"
                "specimen_id" specimen-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "release_number" release-number
     "certificate" (unsigned-certificate "SpecimenRelease" release-number release-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
