(ns conservation.facts
  "Per-jurisdiction wildlife/plant-conservation regulatory catalog --
  the G2-style spec-basis table the Conservation Governor checks
  every jurisdiction/assess proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's living-specimen
  transfer/release requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official CITES-
  implementing/wildlife-conservation regulator (see `:provenance`);
  they are a STARTING catalog, not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to
  `catalog`, cite a real source, done -- never invent a jurisdiction's
  requirements to make coverage look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  health/veterinary-certificate/CITES-permit/transport-plan/
  accreditation evidence set submitted in some form; `:legal-basis` /
  `:owner-authority` / `:provenance` are the G2 citation the governor
  requires before any :jurisdiction/assess proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "環境省 (Ministry of the Environment)"
          :legal-basis "絶滅のおそれのある野生動植物の種の保存に関する法律 (Act on Conservation of Endangered Species of Wild Fauna and Flora)"
          :national-spec "特定第一種国内希少野生動植物種の譲渡・移動規定"
          :provenance "https://www.env.go.jp/nature/kisho/"
          :required-evidence ["健康診断書/獣医証明書 (health/veterinary certificate)"
                              "CITES許可証等証明書類 (CITES/permit documentation)"
                              "輸送/生息環境計画書 (transport/habitat plan)"
                              "施設認定記録 (institutional accreditation record)"]}
   "USA" {:name "United States"
          :owner-authority "U.S. Fish and Wildlife Service, Division of Management Authority"
          :legal-basis "Endangered Species Act + CITES implementing regulations (50 CFR Part 23)"
          :national-spec "CITES export/import/transfer permit requirements"
          :provenance "https://www.fws.gov/program/cites"
          :required-evidence ["Health/veterinary certificate"
                              "CITES/permit documentation"
                              "Transport/habitat plan"
                              "Institutional accreditation record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Animal and Plant Health Agency (APHA)"
          :legal-basis "Zoo Licensing Act 1981 + CITES (Kept Species) Regulations"
          :national-spec "Zoo Licensing Act specimen transfer conditions"
          :provenance "https://www.gov.uk/guidance/zoo-licensing-act-1981"
          :required-evidence ["Health/veterinary certificate"
                              "CITES/permit documentation"
                              "Transport/habitat plan"
                              "Institutional accreditation record"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesamt für Naturschutz (BfN)"
          :legal-basis "Bundesnaturschutzgesetz (BNatSchG) + Bundesartenschutzverordnung (BArtSchV)"
          :national-spec "Artenschutzrechtliche Vermittlungs-/Verbringungsvorschriften"
          :provenance "https://www.bfn.de/"
          :required-evidence ["Gesundheitszeugnis/Tierärztliche Bescheinigung (health/veterinary certificate)"
                              "CITES-/Genehmigungsnachweis (CITES/permit documentation)"
                              "Transport-/Habitatplan (transport/habitat plan)"
                              "Einrichtungsakkreditierungsnachweis (institutional accreditation record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to transfer or
  release a living specimen on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-9103 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `conservation.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
