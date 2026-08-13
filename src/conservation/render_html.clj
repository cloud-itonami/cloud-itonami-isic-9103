(ns conservation.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`conservation.operation` ->
  `conservation.governor` -> `conservation.store`, compiled as a
  langgraph-clj StateGraph and resumed through the real
  `interrupt-before #{:request-approval}` human handoff) through a
  scenario adapted from this repo's own `conservation.sim` demo driver
  (`clojure -M:run`, confirmed BEFORE writing this file to produce a
  sensible ledger against the real seeded specimen ids
  `specimen-1`..`specimen-4` of `conservation.store/demo-data`).

  Every row on the page is real actor output:

    - the specimen directory is `store/all-specimens` AFTER the run
      (so `:transfer-finalized?`/`:released?` are the actor's own
      writes, not a hand-typed lifecycle);
    - the HARD-hold table is the governor's own `:violations` vectors;
    - the draft transfer/release records are
      `store/transfer-history`/`store/release-history`, i.e. what
      `conservation.registry` actually built;
    - the audit ledger is `store/ledger` verbatim.

  Nothing is timestamped and no value is invented, so the output is
  byte-identical across reruns against the same seed (verify by
  diffing two consecutive runs into two scratch files).

  TWO KINDS OF HOLD, RENDERED SEPARATELY. A `:governor-hold` fact is
  written both when the Conservation Governor refuses a proposal
  (non-empty `:violations` -- un-overridable) and when the rollout
  phase gate refuses to let an otherwise-clean op write at this phase
  (`:phase-reason`, EMPTY `:violations`). Counting `:governor-hold`
  facts alone would conflate the two and let a page claim governor
  refusals it never produced, so the two are separated by
  `(seq (:violations f))` and given their own tables -- and `-main`'s
  build-time invariant counts only the HARD kind.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [conservation.facts :as facts]
            [conservation.store :as store]
            [conservation.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  "Phase 3 (supervised-auto) conservationist -- the demo operator."
  {:actor-id "op-1" :actor-role :conservationist :phase 3})

(def ^:private early-phase-operator
  "The SAME operator at phase 1 (assisted-intake). Used once, to show
  the rollout-phase gate holding an op the governor itself cleared --
  a structurally different hold from a governor refusal."
  {:actor-id "op-1" :actor-role :conservationist :phase 1})

(defn- exec!
  ([actor tid request] (exec! actor tid request operator))
  ([actor tid request ctx]
   (g/run* actor {:request request :context ctx} {:thread-id tid})))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- approval-grants
  "The `:approval-granted` audit facts a run produced. This is the ONLY
  place the approver's identity appears at all -- see
  `approver-disclosure` below."
  [run]
  (filterv #(= :approval-granted (:t %)) (get-in run [:state :audit])))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every
  disposition this actor can reach.

  specimen-1 (Red panda, JPN, BCS 5 within [4,6], welfare flag
  resolved) clears a full lifecycle: intake (auto-commits clean at
  phase 3 -- no living-specimen risk yet), a jurisdiction assessment
  (phase-gated, approved), a welfare screening (approved), a SPECIMEN
  TRANSFER (ALWAYS escalates -- `:actuation/transfer-specimen` is
  permanently high-stakes, never auto at any phase -- approved) and a
  SPECIMEN RELEASE (ALWAYS escalates -- `:actuation/release-specimen`,
  the irreversible one -- approved).

  Five HARD governor holds, none of which ever reaches a human:
  specimen-2's jurisdiction assessment cites no official spec-basis for
  its (deliberately unregistered) jurisdiction; specimen-3 clears its
  own assessment but then HARD-holds a transfer because the governor
  INDEPENDENTLY recomputes its body-condition-score (2) as outside its
  own healthy range [4,6]; specimen-4's welfare screening HARD-holds on
  its own finding of an unresolved welfare flag; specimen-1 is then
  refused a SECOND transfer and a SECOND release off the dedicated
  `:transfer-finalized?`/`:released?` facts.

  Finally the SAME clean jurisdiction assessment that succeeded at
  phase 3 is replayed at phase 1, where `:jurisdiction/assess` is not
  yet an enabled write -- the governor clears it and the rollout phase
  gate holds it anyway, producing a hold fact with EMPTY `:violations`.

  Returns {:db store :grants [approval-granted facts]} -- every field
  read by `render` is real governor/store output, not a hand-typed
  copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        grants (atom [])
        approve-and-record! (fn [tid]
                              (swap! grants into (approval-grants (approve! actor tid))))]
    (exec! actor "t1-intake" {:op :specimen/intake :subject "specimen-1"
                              :patch {:id "specimen-1"
                                      :specimen-name "Red panda (Ailurus fulgens), juvenile"}})

    (exec! actor "t1-assess" {:op :jurisdiction/assess :subject "specimen-1"})
    (approve-and-record! "t1-assess")

    (exec! actor "t1-welfare" {:op :welfare/screen :subject "specimen-1"})
    (approve-and-record! "t1-welfare")

    (exec! actor "t1-transfer" {:op :specimen/transfer :subject "specimen-1"})
    (approve-and-record! "t1-transfer")

    (exec! actor "t1-release" {:op :specimen/release :subject "specimen-1"})
    (approve-and-record! "t1-release")

    (exec! actor "t2-assess" {:op :jurisdiction/assess :subject "specimen-2" :no-spec? true})

    (exec! actor "t3-assess" {:op :jurisdiction/assess :subject "specimen-3"})
    (approve-and-record! "t3-assess")

    (exec! actor "t3-transfer" {:op :specimen/transfer :subject "specimen-3"})

    (exec! actor "t4-welfare" {:op :welfare/screen :subject "specimen-4"})

    (exec! actor "t1-transfer-again" {:op :specimen/transfer :subject "specimen-1"})

    (exec! actor "t1-release-again" {:op :specimen/release :subject "specimen-1"})

    ;; Same clean op, earlier phase -> rollout-phase hold, empty :violations.
    (exec! actor "t1-assess-phase1" {:op :jurisdiction/assess :subject "specimen-1"}
           early-phase-operator)

    {:db db :grants @grants}))

;; ----------------------------- hold classification -----------------------------

(defn- hold-facts [ledger]
  (filter #(= :governor-hold (:t %)) ledger))

(defn- hard-holds
  "Conservation Governor refusals -- a non-empty `:violations` vector.
  Un-overridable: no approver can approve past these."
  [ledger]
  (filterv #(seq (:violations %)) (hold-facts ledger)))

(defn- phase-holds
  "Rollout-phase gate holds -- the governor itself was clean, so
  `:violations` is EMPTY and `:phase-reason` says which gate fired."
  [ledger]
  (filterv #(empty? (:violations %)) (hold-facts ledger)))

;; ----------------------------- approver attribution -----------------------------

(def ^:private approver-keys
  "Every key any backend in this repo could plausibly use to retain an
  approver on a committed artifact. Scanned at RENDER time so the page
  self-corrects if the store starts retaining one."
  [:approved-by :approver "approved_by" "approver"])

(defn- approver-of [m]
  (when (map? m) (some m approver-keys)))

(defn- register-attribution
  "Scans the committed registers (jurisdiction assessments, welfare
  screenings) and the committed actuation records (transfer/release
  drafts) for an approver key. Derived from the store at render time --
  NOT joined to the approval audit facts on [op subject], which is not
  a unique key here (specimen-1 is the subject of a transfer AND a
  release AND a refused second attempt of each, so such a join would
  silently attribute one op's approver to another op's record)."
  [db]
  (concat
   (for [sp (store/all-specimens db)
         [kind reg] [["jurisdiction assessment" (store/assessment-of db (:id sp))]
                     ["welfare screening" (store/welfare-screening-of db (:id sp))]]
         :when reg]
     {:artifact kind :subject (:id sp) :approver (approver-of reg)})
   (for [[kind rs] [["specimen-transfer draft" (store/transfer-history db)]
                    ["specimen-release draft" (store/release-history db)]]
         r rs]
     {:artifact kind :subject (get r "specimen_id") :record-id (get r "record_id")
      :approver (approver-of r)})))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nm [v] (if (keyword? v) (name v) (str v)))

(defn- last-fact-for [ledger specimen-id]
  (last (filter #(= (:subject %) specimen-id) ledger)))

(defn- status-cell [ledger specimen-id]
  (let [f (last-fact-for ledger specimen-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (and (= :governor-hold (:t f)) (seq (:violations f)))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (nm (-> f :violations first :rule))) "</span>")
      (= :governor-hold (:t f))
      (str "<span class=\"warn\">phase hold &middot; "
           (esc (nm (or (:phase-reason f) :phase-gate))) "</span>")
      :else "<span class=\"muted\">in progress</span>")))

(defn- lifecycle-cell [{:keys [transfer-finalized? released?]}]
  (cond
    (and transfer-finalized? released?) "<span class=\"ok\">transferred &amp; released</span>"
    released? "<span class=\"ok\">released</span>"
    transfer-finalized? "<span class=\"warn\">transferred, not released</span>"
    :else "<span class=\"muted\">in collection</span>"))

(defn- bcs-cell [{:keys [body-condition-score bcs-min-healthy bcs-max-healthy]}]
  (let [out? (or (< (double body-condition-score) (double bcs-min-healthy))
                 (> (double body-condition-score) (double bcs-max-healthy)))]
    (format "<span class=\"num %s\">%s</span> <span class=\"muted num\">[%s,%s]</span>"
            (if out? "critical" "ok")
            (esc body-condition-score) (esc bcs-min-healthy) (esc bcs-max-healthy))))

(defn- specimen-row [ledger {:keys [id specimen-name jurisdiction welfare-flag-resolved?] :as sp}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc specimen-name) (esc jurisdiction)
          (bcs-cell sp)
          (if welfare-flag-resolved?
            "<span class=\"ok\">resolved</span>"
            "<span class=\"critical\">unresolved</span>")
          (lifecycle-cell sp)
          (status-cell ledger id)))

(defn- hard-hold-row [{:keys [op subject violations confidence]}]
  (let [v (first violations)]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><span class=\"critical\">%s</span></td><td>%s</td><td class=\"num\">%s</td></tr>"
            (esc (nm op)) (esc subject) (esc (nm (:rule v))) (esc (:detail v)) (esc confidence))))

(defn- phase-hold-row [{:keys [op subject phase phase-reason violations]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td class=\"num\">%s</td><td><span class=\"warn\">%s</span></td><td>%s</td></tr>"
          (esc (nm op)) (esc subject) (esc phase) (esc (nm (or phase-reason :phase-gate)))
          (if (seq violations)
            (esc (str (count violations) " governor violation(s)"))
            "<span class=\"muted\">none — the Conservation Governor cleared this proposal</span>")))

(defn- record-row [{:strs [record_id specimen_id jurisdiction kind immutable]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc record_id) (esc specimen_id) (esc jurisdiction) (esc kind)
          (if immutable "<span class=\"ok\">immutable</span>" "<span class=\"warn\">mutable</span>")))

(defn- attribution-row [{:keys [artifact subject record-id approver]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc artifact) (esc subject)
          (if record-id (str "<code>" (esc record-id) "</code>") "<span class=\"muted\">—</span>")
          (if approver
            (str "<span class=\"ok\">" (esc approver) "</span>")
            "<span class=\"warn\">not retained on record</span>")))

(defn- grant-row [{:keys [op subject by]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
          (esc (nm op)) (esc subject) (esc by)))

(defn- coverage-row [iso3]
  (let [{:keys [name owner-authority legal-basis provenance required-evidence]}
        (facts/spec-basis iso3)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s</td><td><a href=\"%s\">source</a></td></tr>"
            (esc iso3) (esc name) (esc owner-authority) (esc legal-basis)
            (count required-evidence) (esc provenance))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
          (esc (nm t)) (esc (nm (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map nm) (str/join ", ")) (some-> disposition nm) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract (README
  ;; `Ops`, `conservation.governor`/`conservation.phase`) -- documentation
  ;; of fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run. The live
  ;; consequences of these gates are the tables above/below.
  ["        <tr><td><code>:specimen/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; directory normalization only, no living-specimen risk</span></td></tr>"
   "        <tr><td><code>:jurisdiction/assess</code></td><td><span class=\"warn\">phase-3: human approval (not yet auto-eligible) &middot; HARD hold without an official spec-basis</span></td></tr>"
   "        <tr><td><code>:welfare/screen</code></td><td><span class=\"warn\">never auto-eligible at any phase &middot; HARD holds on its own finding of an unresolved welfare flag</span></td></tr>"
   "        <tr><td><code>:specimen/transfer</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; body-condition-score independently recomputed &middot; double transfer refused</span></td></tr>"
   "        <tr><td><code>:specimen/release</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; irreversible &middot; double release refused</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a `run-demo!`
  result ({:db .. :grants ..})."
  [{:keys [db grants]}]
  (let [ledger (vec (store/ledger db))
        specimens (store/all-specimens db)
        hard (hard-holds ledger)
        phase (phase-holds ledger)
        attribution (register-attribution db)
        retained (filter :approver attribution)
        transfers (store/transfer-history db)
        releases (store/release-history db)
        cov (facts/coverage)]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-9103 &middot; botanical-zoological-conservation</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Botanical &amp; zoological gardens and nature reserves (ISIC 9103) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · specimen transfer/release always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Specimens</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>conservation.store</code> via <code>conservation.render-html</code> (<code>clojure -M:render-html</code>). Lifecycle and status columns are the actor's own committed writes, not a hand-typed summary.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Specimen</th><th>Name</th><th>Jurisdiction</th><th>Body condition [healthy]</th><th>Welfare flag</th><th>Custody</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial specimen-row ledger) specimens)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Conservation Governor + rollout phase)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by any approver. Body-condition scores are independently recomputed from the specimen's own permanent fields, never trusted from the proposal; a transfer or release is blocked outright on an unresolved welfare flag, on incomplete jurisdictional evidence, or on a specimen already transferred/released.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD governor holds — " (count hard) " this run</h2>\n"
     "    <p class=\"muted\">Proposals the Conservation Governor genuinely <strong>refused</strong>. Each carries a non-empty <code>:violations</code> vector and never reaches a human at all — there is no approval path past them.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Specimen</th><th>Rule</th><th>Detail</th><th>Confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hard-hold-row hard)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Rollout-phase gate holds — " (count phase) " this run</h2>\n"
     "    <p class=\"muted\">Structurally different from the table above: here the Conservation Governor <em>cleared</em> the proposal and the staged-rollout gate (<code>conservation.phase</code>) refused the write anyway, because the op is not yet enabled at that phase. These facts share the <code>:governor-hold</code> tag but carry an EMPTY <code>:violations</code> vector, so a naive hold count would report them as governor refusals. They are counted separately, and the build-time invariant in <code>-main</code> counts only the HARD kind.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Specimen</th><th>Phase</th><th>Gate reason</th><th>Governor violations</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map phase-hold-row phase)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Draft specimen-transfer / specimen-release records</h2>\n"
     "    <p class=\"muted\">Built by <code>conservation.registry</code> — the record an institution keeps, never the act itself. Every certificate this actor produces is <strong>unsigned</strong>: signature is the institution's own act.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Specimen</th><th>Jurisdiction</th><th>Kind</th><th>Mutability</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map record-row (concat transfers releases))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Approver attribution</h2>\n"
     "    <p class=\"muted\">Derived at render time by scanning the committed registers and actuation records for an approver key ("
     (esc (str/join ", " (map nm approver-keys)))
     ") — not by joining records to approval facts on <code>[op, specimen]</code>, which is not a unique key here (<code>specimen-1</code> is the subject of a transfer, a release and a refused second attempt of each, so such a join would attribute one op's approver to another op's record). "
     (if (seq retained)
       (str "This run: <strong>" (count retained) " of " (count attribution)
            "</strong> committed artifacts retain the approver on the record itself.")
       "This run: <strong>no</strong> committed artifact retained the approver.")
     "</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Artifact</th><th>Specimen</th><th>Record id</th><th>Approver on record</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map attribution-row attribution)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <h3>Approvals granted this run <span class=\"muted\">(audit only — not retained on record)</span></h3>\n"
     "    <p class=\"muted\">The human handoff is real: <code>interrupt-before #{:request-approval}</code> pauses the graph and the operator resumes it. These <code>:approval-granted</code> facts are emitted on the actor's <code>:audit</code> channel; the <code>:commit</code> node writes <code>conservation.operation/commit-fact</code> to the ledger, which has no approver field, so where the column above reads &ldquo;not retained on record&rdquo; the approver survives only here, in the run's audit channel.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Specimen</th><th>Approved by</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map grant-row grants)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Jurisdiction spec-basis coverage — " (:covered cov) " of " (:requested cov) " seeded</h2>\n"
     "    <p class=\"muted\">" (esc (:note cov)) " A jurisdiction absent from this catalog has NO spec-basis: the advisor must not fabricate one, and the governor HARD-holds if it tries (see <code>specimen-2</code> above, jurisdiction <code>ATL</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ISO3</th><th>Jurisdiction</th><th>Owner authority</th><th>Legal basis</th><th>Required evidence</th><th>Provenance</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map coverage-row (:covered-jurisdictions cov))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run) — " (count ledger) " facts</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and hold this scenario produced, in order.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Specimen</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>Generated by <code>conservation.render-html</code> from a real "
     "<code>conservation.operation</code> run — cloud-itonami-isic-9103, AGPL-3.0-or-later.</footer>\n"
     "</body></html>\n")))

(defn -main
  "Regenerates the operator console. BUILD-TIME INVARIANT: refuses to
  write the page if the run produced zero HARD governor holds.

  A console that shows only approvals would be indistinguishable from
  a page whose governor was never wired in at all, and a page is not
  evidence of a censor that never said no. Counting only holds with a
  non-empty `:violations` vector matters: the rollout-phase gate writes
  the same `:governor-hold` tag with EMPTY violations, so a naive count
  of `:governor-hold` facts would satisfy this check on a run in which
  the governor refused nothing."
  [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db grants] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        hard (hard-holds ledger)
        phase (phase-holds ledger)]
    (when (empty? hard)
      (throw (ex-info (str "refusing to write " out
                           ": the run produced ZERO HARD governor holds"
                           " (violations-carrying :governor-hold facts)."
                           " A console with no refusals is not evidence of a governor.")
                      {:out out
                       :ledger-facts (count ledger)
                       :governor-hold-facts (count (hold-facts ledger))
                       :hard-holds 0
                       :phase-holds (count phase)})))
    (spit out (render result))
    (println "wrote" out "-" (count ledger) "ledger facts,"
             (count hard) "HARD governor holds"
             (str "(" (str/join ", " (sort (distinct (map #(nm (:rule (first (:violations %)))) hard)))) "),")
             (count phase) "rollout-phase holds,"
             (count grants) "approvals,"
             (count (store/transfer-history db)) "transfer drafts,"
             (count (store/release-history db)) "release drafts")))
