(ns conservation.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean specimen through
  intake -> jurisdiction assessment -> welfare screening -> specimen-
  transfer proposal (always escalates) -> human approval -> commit,
  then through specimen-release proposal (always escalates) -> human
  approval -> commit, then shows five HARD holds (a jurisdiction with
  no spec-basis, an out-of-range body-condition score, an unresolved
  welfare flag screened directly via `:welfare/screen` [never via an
  actuation op against an unscreened specimen -- see this actor's own
  governor ns docstring / the lesson `parksafety`'s ADR-2607071922
  Decision 5, `eldercare`'s ADR-0001 and `museum`'s ADR-0001 already
  recorded], and a double transfer/release of an already-processed
  specimen) that never reach a human at all, and prints the audit
  ledger + the draft specimen-transfer and specimen-release records."
  (:require [langgraph.graph :as g]
            [conservation.store :as store]
            [conservation.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :conservationist :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== specimen/intake specimen-1 (JPN, clean; BCS 5 within [4,6], welfare-flag resolved) ==")
    (println (exec! actor "t1" {:op :specimen/intake :subject "specimen-1"
                                :patch {:id "specimen-1" :specimen-name "Red panda (Ailurus fulgens), juvenile"}} operator))

    (println "== jurisdiction/assess specimen-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "specimen-1"} operator))
    (println (approve! actor "t2"))

    (println "== welfare/screen specimen-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :welfare/screen :subject "specimen-1"} operator))
    (println (approve! actor "t3"))

    (println "== specimen/transfer specimen-1 (always escalates -- actuation/transfer-specimen) ==")
    (let [r (exec! actor "t4" {:op :specimen/transfer :subject "specimen-1"} operator)]
      (println r)
      (println "-- human conservationist approves --")
      (println (approve! actor "t4")))

    (println "== specimen/release specimen-1 (always escalates -- actuation/release-specimen) ==")
    (let [r (exec! actor "t5" {:op :specimen/release :subject "specimen-1"} operator)]
      (println r)
      (println "-- human conservationist approves --")
      (println (approve! actor "t5")))

    (println "== jurisdiction/assess specimen-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "specimen-2" :no-spec? true} operator))

    (println "== jurisdiction/assess specimen-3 (escalates -- human approves; sets up the body-condition-range test) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "specimen-3"} operator))
    (println (approve! actor "t7"))

    (println "== specimen/transfer specimen-3 (BCS 2 outside [4,6] -> HARD hold) ==")
    (println (exec! actor "t8" {:op :specimen/transfer :subject "specimen-3"} operator))

    (println "== welfare/screen specimen-4 (unresolved welfare flag -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :welfare/screen :subject "specimen-4"} operator))

    (println "== specimen/transfer specimen-1 AGAIN (double-transfer -> HARD hold) ==")
    (println (exec! actor "t10" {:op :specimen/transfer :subject "specimen-1"} operator))

    (println "== specimen/release specimen-1 AGAIN (double-release -> HARD hold) ==")
    (println (exec! actor "t11" {:op :specimen/release :subject "specimen-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft specimen-transfer records ==")
    (doseq [r (store/transfer-history db)] (println r))

    (println "== draft specimen-release records ==")
    (doseq [r (store/release-history db)] (println r))))
