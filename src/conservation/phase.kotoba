(ns conservation.phase
  "Phase 0->3 staged rollout -- the zoo/botanical-garden/conservation
  analog of `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- specimen intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment + welfare
                                 screening writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:specimen/intake` (no capital risk
                                 yet) may auto-commit. `:specimen/
                                 transfer`/`:specimen/release` NEVER
                                 auto-commit, at any phase.

  `:specimen/transfer`/`:specimen/release` are deliberately ABSENT
  from every phase's `:auto` set, including phase 3 -- a permanent
  structural fact, not a rollout milestone still to come. Transferring
  a real living specimen and releasing a real living specimen are the
  two real-world legal acts this actor performs; both are always a
  human conservationist/veterinarian call. `conservation.governor`'s
  `:actuation/transfer-specimen`/`:actuation/release-specimen` high-
  stakes gate enforces the same invariant independently -- two layers,
  not one, agree on this. `:welfare/screen` is likewise never auto-
  eligible, at any phase -- the same posture every sibling's KYC/
  conflict/independence/surveillance/calibration/credential/integrity/
  patron/authorization/safety-test/inspection/incident-flag screening
  op has. Like `credit.phase`/`accounting.phase`/`marketadmin.phase`/
  `testlab.phase`/`clinic.phase`/`registrar.phase`/`wagering.phase`/
  `veterinary.phase`/`funeral.phase`/`repairshop.phase`/`parksafety.
  phase`/`eldercare.phase`/`museum.phase`, phase 3's `:auto` set here
  has only ONE member (`:specimen/intake`) -- this domain has no
  separate no-capital-risk 'file' lifecycle distinct from the specimen
  record itself.")

(def read-ops  #{})
(def write-ops #{:specimen/intake :jurisdiction/assess :welfare/screen
                 :specimen/transfer :specimen/release})

;; NOTE the invariant: `:specimen/transfer`/`:specimen/release` are
;; members of `write-ops` (governor-gated like any write) but are
;; NEVER members of any phase's `:auto` set below. Do not add them
;; there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                       :auto #{}}
   1 {:label "assisted-intake" :writes #{:specimen/intake}                                       :auto #{}}
   2 {:label "assisted-assess" :writes #{:specimen/intake :jurisdiction/assess :welfare/screen}   :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:specimen/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:specimen/transfer`/`:specimen/release` are never auto-eligible
    at any phase, so they always escalate once the governor clears
    them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Conservation Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
