(ns conservation.store
  "SSoT for the zoo/botanical-garden/conservation actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/conservation/store_contract_test.clj), which is the whole
  point: the actor, the Conservation Governor and the audit ledger
  never know which SSoT they run on.

  Like `marketadmin.store`'s dual admission/halt-lift history,
  `registrar.store`'s dual grade/degree history, `wagering.store`'s
  dual acceptance/settlement history, `repairshop.store`'s dual
  completion/return history, `eldercare.store`'s dual care-plan/
  incident-response-finalization history and `museum.store`'s dual
  loan/deaccession history, this actor has TWO actuation events
  (transferring a living specimen, releasing a living specimen) acting
  on the SAME entity (a specimen), each with its OWN history
  collection, sequence counter and dedicated double-actuation-guard
  boolean (`:transfer-finalized?`/`:released?`, never a `:status`
  value) -- the same discipline `accounting.governor`'s/`marketadmin.
  governor`'s/`testlab.governor`'s/`clinic.governor`'s/`registrar.
  governor`'s/`wagering.governor`'s/`veterinary.governor`'s/`funeral.
  governor`'s/`repairshop.governor`'s/`parksafety.governor`'s/
  `eldercare.governor`'s/`museum.governor`'s guards establish.

  The ledger stays append-only on every backend: 'which specimen was
  screened for an unresolved welfare flag, which specimen was
  transferred, which specimen was released, on what jurisdictional
  basis, approved by whom' is always a query over an immutable log --
  the audit trail a regulator/receiving-institution trusting an
  operator needs, and the evidence an operator needs if a transfer or
  release is later disputed."
  (:require [conservation.registry :as registry]
            [langchain-store.core :as ls]
            [langchain.db :as d]))

(defprotocol Store
  (specimen [s id])
  (all-specimens [s])
  (welfare-screening-of [s specimen-id] "committed welfare screening verdict for a specimen, or nil")
  (assessment-of [s specimen-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (transfer-history [s] "the append-only specimen-transfer history (conservation.registry drafts)")
  (release-history [s] "the append-only specimen-release history (conservation.registry drafts)")
  (next-transfer-sequence [s jurisdiction] "next specimen-transfer-number sequence for a jurisdiction")
  (next-release-sequence [s jurisdiction] "next specimen-release-number sequence for a jurisdiction")
  (specimen-already-transferred? [s specimen-id] "has this specimen's transfer already been finalized?")
  (specimen-already-released? [s specimen-id] "has this specimen already been released?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-specimens [s specimens] "replace/seed the specimen directory (map id->specimen)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained specimen set covering both actuation
  lifecycles (transferring a specimen, releasing a specimen) so the
  actor + tests run offline."
  []
  {:specimens
   {"specimen-1" {:id "specimen-1" :specimen-name "Red panda (Ailurus fulgens), juvenile"
                  :body-condition-score 5 :bcs-min-healthy 4 :bcs-max-healthy 6
                  :welfare-flag-resolved? true
                  :transfer-finalized? false :released? false
                  :jurisdiction "JPN" :status :intake}
    "specimen-2" {:id "specimen-2" :specimen-name "Unregistered specimen"
                  :body-condition-score 5 :bcs-min-healthy 4 :bcs-max-healthy 6
                  :welfare-flag-resolved? true
                  :transfer-finalized? false :released? false
                  :jurisdiction "ATL" :status :intake}
    "specimen-3" {:id "specimen-3" :specimen-name "Underweight specimen"
                  :body-condition-score 2 :bcs-min-healthy 4 :bcs-max-healthy 6
                  :welfare-flag-resolved? true
                  :transfer-finalized? false :released? false
                  :jurisdiction "JPN" :status :intake}
    "specimen-4" {:id "specimen-4" :specimen-name "Welfare-flagged specimen"
                  :body-condition-score 5 :bcs-min-healthy 4 :bcs-max-healthy 6
                  :welfare-flag-resolved? false
                  :transfer-finalized? false :released? false
                  :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize-transfer!
  "Backend-agnostic `:specimen/mark-transferred` -- looks up the
  specimen via the protocol and drafts the specimen-transfer record,
  and returns {:result .. :specimen-patch ..} for the caller to
  persist."
  [s specimen-id]
  (let [sp (specimen s specimen-id)
        seq-n (next-transfer-sequence s (:jurisdiction sp))
        result (registry/register-specimen-transfer specimen-id (:jurisdiction sp) seq-n)]
    {:result result
     :specimen-patch {:transfer-finalized? true
                      :transfer-number (get result "transfer_number")}}))

(defn- finalize-release!
  "Backend-agnostic `:specimen/mark-released` -- looks up the specimen
  via the protocol and drafts the specimen-release record, and returns
  {:result .. :specimen-patch ..} for the caller to persist."
  [s specimen-id]
  (let [sp (specimen s specimen-id)
        seq-n (next-release-sequence s (:jurisdiction sp))
        result (registry/register-specimen-release specimen-id (:jurisdiction sp) seq-n)]
    {:result result
     :specimen-patch {:released? true
                      :release-number (get result "release_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (specimen [_ id] (get-in @a [:specimens id]))
  (all-specimens [_] (sort-by :id (vals (:specimens @a))))
  (welfare-screening-of [_ id] (get-in @a [:welfare-screenings id]))
  (assessment-of [_ specimen-id] (get-in @a [:assessments specimen-id]))
  (ledger [_] (:ledger @a))
  (transfer-history [_] (:transfers @a))
  (release-history [_] (:releases @a))
  (next-transfer-sequence [_ jurisdiction] (get-in @a [:transfer-sequences jurisdiction] 0))
  (next-release-sequence [_ jurisdiction] (get-in @a [:release-sequences jurisdiction] 0))
  (specimen-already-transferred? [_ specimen-id] (boolean (get-in @a [:specimens specimen-id :transfer-finalized?])))
  (specimen-already-released? [_ specimen-id] (boolean (get-in @a [:specimens specimen-id :released?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :specimen/upsert
      (swap! a update-in [:specimens (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :welfare-screening/set
      (swap! a assoc-in [:welfare-screenings (first path)] payload)

      :specimen/mark-transferred
      (let [specimen-id (first path)
            {:keys [result specimen-patch]} (finalize-transfer! s specimen-id)
            jurisdiction (:jurisdiction (specimen s specimen-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:transfer-sequences jurisdiction] (fnil inc 0))
                       (update-in [:specimens specimen-id] merge specimen-patch)
                       (update :transfers registry/append result))))
        result)

      :specimen/mark-released
      (let [specimen-id (first path)
            {:keys [result specimen-patch]} (finalize-release! s specimen-id)
            jurisdiction (:jurisdiction (specimen s specimen-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:release-sequences jurisdiction] (fnil inc 0))
                       (update-in [:specimens specimen-id] merge specimen-patch)
                       (update :releases registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-specimens [s specimens] (when (seq specimens) (swap! a assoc :specimens specimens)) s))

(defn seed-db
  "A MemStore seeded with the demo specimen set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :welfare-screenings {} :ledger [] :transfer-sequences {}
                           :transfers [] :release-sequences {} :releases []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/welfare-screening payloads, ledger
  facts, transfer/release records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:specimen/id                      {:db/unique :db.unique/identity}
   :assessment/specimen-id           {:db/unique :db.unique/identity}
   :welfare-screening/specimen-id    {:db/unique :db.unique/identity}
   :ledger/seq                       {:db/unique :db.unique/identity}
   :transfer/seq                     {:db/unique :db.unique/identity}
   :release/seq                      {:db/unique :db.unique/identity}
   :transfer-sequence/jurisdiction   {:db/unique :db.unique/identity}
   :release-sequence/jurisdiction    {:db/unique :db.unique/identity}})

(defn- specimen->tx [{:keys [id specimen-name body-condition-score bcs-min-healthy bcs-max-healthy
                             welfare-flag-resolved? transfer-finalized? released?
                             jurisdiction status transfer-number release-number]}]
  (cond-> {:specimen/id id}
    specimen-name                      (assoc :specimen/specimen-name specimen-name)
    body-condition-score               (assoc :specimen/body-condition-score body-condition-score)
    bcs-min-healthy                    (assoc :specimen/bcs-min-healthy bcs-min-healthy)
    bcs-max-healthy                    (assoc :specimen/bcs-max-healthy bcs-max-healthy)
    (some? welfare-flag-resolved?)     (assoc :specimen/welfare-flag-resolved? welfare-flag-resolved?)
    (some? transfer-finalized?)        (assoc :specimen/transfer-finalized? transfer-finalized?)
    (some? released?)                  (assoc :specimen/released? released?)
    jurisdiction                       (assoc :specimen/jurisdiction jurisdiction)
    status                             (assoc :specimen/status status)
    transfer-number                    (assoc :specimen/transfer-number transfer-number)
    release-number                     (assoc :specimen/release-number release-number)))

(def ^:private specimen-pull
  [:specimen/id :specimen/specimen-name :specimen/body-condition-score
   :specimen/bcs-min-healthy :specimen/bcs-max-healthy
   :specimen/welfare-flag-resolved? :specimen/transfer-finalized? :specimen/released?
   :specimen/jurisdiction :specimen/status :specimen/transfer-number :specimen/release-number])

(defn- pull->specimen [m]
  (when (:specimen/id m)
    {:id (:specimen/id m) :specimen-name (:specimen/specimen-name m)
     :body-condition-score (:specimen/body-condition-score m)
     :bcs-min-healthy (:specimen/bcs-min-healthy m)
     :bcs-max-healthy (:specimen/bcs-max-healthy m)
     :welfare-flag-resolved? (boolean (:specimen/welfare-flag-resolved? m))
     :transfer-finalized? (boolean (:specimen/transfer-finalized? m))
     :released? (boolean (:specimen/released? m))
     :jurisdiction (:specimen/jurisdiction m) :status (:specimen/status m)
     :transfer-number (:specimen/transfer-number m) :release-number (:specimen/release-number m)}))

(defrecord DatomicStore [conn]
  Store
  (specimen [_ id]
    (pull->specimen (d/pull (d/db conn) specimen-pull [:specimen/id id])))
  (all-specimens [_]
    (->> (d/q '[:find [?id ...] :where [?e :specimen/id ?id]] (d/db conn))
         (map #(pull->specimen (d/pull (d/db conn) specimen-pull [:specimen/id %])))
         (sort-by :id)))
  (welfare-screening-of [_ id]
    (ls/dec* (d/q '[:find ?p . :in $ ?sid
                :where [?k :welfare-screening/specimen-id ?sid] [?k :welfare-screening/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ specimen-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?sid
                :where [?a :assessment/specimen-id ?sid] [?a :assessment/payload ?p]]
              (d/db conn) specimen-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (transfer-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :transfer/seq ?s] [?e :transfer/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (release-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :release/seq ?s] [?e :release/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (next-transfer-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :transfer-sequence/jurisdiction ?j] [?e :transfer-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-release-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :release-sequence/jurisdiction ?j] [?e :release-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (specimen-already-transferred? [s specimen-id]
    (boolean (:transfer-finalized? (specimen s specimen-id))))
  (specimen-already-released? [s specimen-id]
    (boolean (:released? (specimen s specimen-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :specimen/upsert
      (d/transact! conn [(specimen->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/specimen-id (first path) :assessment/payload (ls/enc payload)}])

      :welfare-screening/set
      (d/transact! conn [{:welfare-screening/specimen-id (first path) :welfare-screening/payload (ls/enc payload)}])

      :specimen/mark-transferred
      (let [specimen-id (first path)
            {:keys [result specimen-patch]} (finalize-transfer! s specimen-id)
            jurisdiction (:jurisdiction (specimen s specimen-id))
            next-n (inc (next-transfer-sequence s jurisdiction))]
        (d/transact! conn
                     [(specimen->tx (assoc specimen-patch :id specimen-id))
                      {:transfer-sequence/jurisdiction jurisdiction :transfer-sequence/next next-n}
                      {:transfer/seq (count (transfer-history s)) :transfer/record (ls/enc (get result "record"))}])
        result)

      :specimen/mark-released
      (let [specimen-id (first path)
            {:keys [result specimen-patch]} (finalize-release! s specimen-id)
            jurisdiction (:jurisdiction (specimen s specimen-id))
            next-n (inc (next-release-sequence s jurisdiction))]
        (d/transact! conn
                     [(specimen->tx (assoc specimen-patch :id specimen-id))
                      {:release-sequence/jurisdiction jurisdiction :release-sequence/next next-n}
                      {:release/seq (count (release-history s)) :release/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-specimens [s specimens]
    (when (seq specimens) (d/transact! conn (mapv specimen->tx (vals specimens)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:specimens ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [specimens]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-specimens s specimens))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo specimen set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
