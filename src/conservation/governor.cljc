(ns conservation.governor
  "Conservation Governor -- the independent compliance layer that
  earns the ConservationOps-LLM the right to commit. The LLM has no
  notion of jurisdictional wildlife/plant-conservation law, whether a
  specimen's own body-condition score falls outside its species-
  specific healthy range, whether a specimen's own welfare flag is
  still unresolved, or when an act stops being a draft and becomes a
  real-world transfer or release, so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD -- the zoo/
  botanical-garden analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete evidence, an out-of-
  range body-condition score, an unresolved welfare flag, or a double
  transfer/release). The confidence/actuation gate is SOFT: it asks a
  human to look (low confidence / actuation), and the human may
  approve -- but see `conservation.phase`: for `:stake :actuation/
  transfer-specimen`/`:actuation/release-specimen` (a real transfer or
  release) NO phase ever allows auto-commit either. Two independent
  layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`conservation.
                                       facts`), or invent one?
    2. Evidence incomplete         -- for `:specimen/transfer`/
                                       `:specimen/release`, has the
                                       jurisdiction actually been
                                       assessed with a full specimen-
                                       evidence checklist on file?
    3. Body condition out of range -- for `:specimen/transfer`/
                                       `:specimen/release`,
                                       INDEPENDENTLY recompute whether
                                       the specimen's own `:body-
                                       condition-score` falls outside
                                       its own `:bcs-min-healthy`/
                                       `:bcs-max-healthy` range
                                       (`conservation.registry/body-
                                       condition-out-of-range?`) --
                                       needs no proposal inspection or
                                       stored-verdict lookup at all.
                                       The SECOND check in this fleet
                                       to combine BOTH directions in
                                       ONE check (`testlab.governor/
                                       out-of-tolerance-violations`
                                       established the first), and the
                                       FIRST to apply per-ENTITY
                                       species-specific acceptance
                                       bounds rather than per-test-
                                       protocol bounds.
    4. Welfare flag unresolved     -- for `:specimen/transfer`/
                                       `:specimen/release`, reported by
                                       THIS proposal itself (a
                                       `:welfare/screen` that just
                                       found an unresolved flag), or
                                       already on file for the specimen
                                       (`:welfare/screen`/either
                                       actuation op). Evaluated
                                       UNCONDITIONALLY (not scoped to a
                                       specific op), the SAME discipline
                                       `casualty.governor/sanctions-
                                       violations`/`marketadmin.
                                       governor/surveillance-flag-
                                       unresolved-violations`/`testlab.
                                       governor/calibration-not-current-
                                       violations`/`clinic.governor/
                                       credential-not-current-
                                       violations`/`registrar.governor/
                                       integrity-flag-unresolved-
                                       violations`/`wagering.governor/
                                       patron-flag-unresolved-
                                       violations`/`veterinary.
                                       governor/credential-not-current-
                                       violations`/`funeral.governor/
                                       authorization-unverified-
                                       violations`/`repairshop.
                                       governor/safety-test-not-passed-
                                       violations`/`parksafety.
                                       governor/inspection-not-passed-
                                       violations`/`eldercare.governor/
                                       incident-flag-unresolved-
                                       violations`/`museum.governor/
                                       incident-flag-unresolved-
                                       violations` established -- the
                                       TWELFTH distinct application of
                                       this exact discipline. Like
                                       `parksafety.governor`'s/
                                       `eldercare.governor`'s/`museum.
                                       governor`'s equivalent checks,
                                       this is exercised in tests/demo
                                       via `:welfare/screen` DIRECTLY,
                                       not via an actuation op against
                                       an unscreened specimen -- see
                                       this ns's own test suite.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:specimen/
                                       transfer`/`:specimen/release`
                                       (REAL acts) -> escalate.

  Two more guards, double-transfer/double-release prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-transferred-
  violations`/`already-released-violations` refuse to transfer/release
  the SAME specimen twice, off dedicated `:transfer-finalized?`/
  `:released?` facts (never a `:status` value) -- the SAME 'check a
  dedicated boolean, not status' discipline `accounting.governor`'s/
  `marketadmin.governor`'s/`testlab.governor`'s/`clinic.governor`'s/
  `registrar.governor`'s/`wagering.governor`'s/`veterinary.
  governor`'s/`funeral.governor`'s/`repairshop.governor`'s/
  `parksafety.governor`'s/`eldercare.governor`'s/`museum.governor`'s
  guards establish, informed by `cloud-itonami-isic-6492`'s status-
  lifecycle bug (ADR-2607071320)."
  (:require [conservation.facts :as facts]
            [conservation.registry :as registry]
            [conservation.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Transferring a real living specimen and releasing a real living
  specimen are the two real-world actuation events this actor
  performs -- a two-member set, matching `cloud-itonami-isic-6512`'s/
  `6622`'s/`6520`'s/`6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/
  `9200`'s/`9521`'s/`8730`'s/`9102`'s dual-actuation shape."
  #{:actuation/transfer-specimen :actuation/release-specimen})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:specimen/transfer`/`:specimen/
  release`) proposal with no spec-basis citation is a HARD violation
  -- never invent a jurisdiction's wildlife/plant-conservation
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :specimen/transfer :specimen/release} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:specimen/transfer`/`:specimen/release`, the jurisdiction's
  required health/CITES/transport/accreditation evidence must actually
  be satisfied -- do not trust the advisor's self-reported confidence
  alone."
  [{:keys [op subject]} st]
  (when (contains? #{:specimen/transfer :specimen/release} op)
    (let [sp (store/specimen st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction sp) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(健康診断書/CITES証明書類/輸送計画書/施設認定記録等)が充足していない状態での提案"}]))))

(defn- body-condition-out-of-range-violations
  "For `:specimen/transfer`/`:specimen/release`, INDEPENDENTLY
  recompute whether the specimen's own body-condition-score falls
  outside its own bcs-min-healthy/bcs-max-healthy range via
  `conservation.registry/body-condition-out-of-range?` -- needs no
  proposal inspection or stored-verdict lookup at all, since its
  inputs are permanent ground-truth fields already on the specimen."
  [{:keys [op subject]} st]
  (when (contains? #{:specimen/transfer :specimen/release} op)
    (let [sp (store/specimen st subject)]
      (when (registry/body-condition-out-of-range? sp)
        [{:rule :body-condition-out-of-range
          :detail (str subject " のbody-condition-score(" (:body-condition-score sp)
                      ")が健康範囲[" (:bcs-min-healthy sp) "," (:bcs-max-healthy sp)
                      "]を外れている")}]))))

(defn- welfare-flag-unresolved-violations
  "An unresolved welfare flag -- reported by THIS proposal (e.g. a
  `:welfare/screen` that itself just found an unresolved flag), or
  already on file in the store for the specimen (`:welfare/screen`/
  either actuation op) -- is a HARD, un-overridable hold. Evaluated
  UNCONDITIONALLY (not scoped to a specific op) so the screening op
  itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        specimen-id (when (contains? #{:welfare/screen :specimen/transfer :specimen/release} op) subject)
        hit-on-file? (and specimen-id (= :unresolved (:verdict (store/welfare-screening-of st specimen-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :welfare-flag-unresolved
        :detail "未解決の福祉(健康/行動)フラグが残っている個体に対する提案は進められない"}])))

(defn- already-transferred-violations
  "For `:specimen/transfer`, refuses to finalize the SAME specimen's
  transfer twice, off a dedicated `:transfer-finalized?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :specimen/transfer)
    (when (store/specimen-already-transferred? st subject)
      [{:rule :already-transferred
        :detail (str subject " は既に移動確定済み")}])))

(defn- already-released-violations
  "For `:specimen/release`, refuses to release the SAME specimen
  twice, off a dedicated `:released?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :specimen/release)
    (when (store/specimen-already-released? st subject)
      [{:rule :already-released
        :detail (str subject " は既に放出/移送済み")}])))

(defn check
  "Censors a ConservationOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (body-condition-out-of-range-violations request st)
                           (welfare-flag-unresolved-violations request proposal st)
                           (already-transferred-violations request st)
                           (already-released-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
