(ns conservation.conservationopsllm
  "ConservationOps-LLM client -- the *contained intelligence node* for
  the zoo/botanical-garden/conservation actor.

  It normalizes living-specimen intake, drafts a per-jurisdiction
  wildlife/plant-conservation evidence checklist, screens specimens
  for an unresolved welfare (health/behavior) flag, drafts the
  specimen-transfer action, and drafts the specimen-release action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real transfer/release. Every output is
  censored downstream by `conservation.governor` before anything
  touches the SSoT, and `:specimen/transfer`/`:specimen/release`
  proposals NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/transfer-specimen | :actuation/release-specimen | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [conservation.facts :as facts]
            [conservation.registry :as registry]
            [conservation.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the specimen, body-condition figures or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "個体記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :specimen/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction wildlife/plant-conservation evidence checklist
  draft. `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `conservation.facts` -- the Conservation Governor must reject
  this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [sp (store/specimen db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction sp))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "conservation.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-welfare
  "Welfare-flag screening draft. `:welfare-flag-resolved?` on the
  specimen record injects the failure mode: the Conservation Governor
  must HOLD, un-overridably, on any unresolved welfare flag."
  [db {:keys [subject]}]
  (let [sp (store/specimen db subject)]
    (cond
      (nil? sp)
      {:summary "対象個体が見つかりません" :rationale "no specimen record"
       :cites [] :effect :welfare-screening/set :value {:specimen-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (false? (:welfare-flag-resolved? sp))
      {:summary    (str (:specimen-name sp) ": 未解決の福祉(健康/行動)フラグを検出")
       :rationale  "スクリーニングが未解決の福祉フラグを検出。人手確認とホールドが必須。"
       :cites      [:welfare-check]
       :effect     :welfare-screening/set
       :value      {:specimen-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:specimen-name sp) ": 福祉フラグ解決済み")
       :rationale  "福祉フラグスクリーニング完了。"
       :cites      [:welfare-check]
       :effect     :welfare-screening/set
       :value      {:specimen-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- propose-specimen-transfer
  "Draft the actual SPECIMEN-TRANSFER action -- transferring a real
  living specimen to another institution. ALWAYS `:stake :actuation/
  transfer-specimen` -- this is a REAL-WORLD act (a living animal/
  plant leaves institutional custody), never a draft the actor may
  auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`conservation.phase`); the governor also always
  escalates on `:actuation/transfer-specimen`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [sp (store/specimen db subject)
        out-of-range? (and sp (registry/body-condition-out-of-range? sp))]
    {:summary    (str subject " 向け移動提案"
                      (when sp (str " (specimen=" (:specimen-name sp) ")")))
     :rationale  (if sp
                   (str "body-condition-score=" (:body-condition-score sp)
                        " healthy-range=[" (:bcs-min-healthy sp) "," (:bcs-max-healthy sp) "]")
                   "個体が見つかりません")
     :cites      (if sp [subject] [])
     :effect     :specimen/mark-transferred
     :value      {:specimen-id subject}
     :stake      :actuation/transfer-specimen
     :confidence (if out-of-range? 0.3 0.9)}))

(defn- propose-specimen-release
  "Draft the actual SPECIMEN-RELEASE action -- releasing a real living
  specimen (e.g. to the wild or a rehabilitation program). ALWAYS
  `:stake :actuation/release-specimen` -- this is a REAL-WORLD,
  irreversible act, never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`conservation.phase`); the governor also always escalates on
  `:actuation/release-specimen`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [sp (store/specimen db subject)
        out-of-range? (and sp (registry/body-condition-out-of-range? sp))]
    {:summary    (str subject " 向け放出/移送提案"
                      (when sp (str " (specimen=" (:specimen-name sp) ")")))
     :rationale  (if sp
                   (str "body-condition-score=" (:body-condition-score sp)
                        " welfare-flag-resolved?=" (:welfare-flag-resolved? sp))
                   "個体が見つかりません")
     :cites      (if sp [subject] [])
     :effect     :specimen/mark-released
     :value      {:specimen-id subject}
     :stake      :actuation/release-specimen
     :confidence (if out-of-range? 0.3 0.9)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :specimen/intake             (normalize-intake db request)
    :jurisdiction/assess             (assess-jurisdiction db request)
    :welfare/screen                      (screen-welfare db request)
    :specimen/transfer                       (propose-specimen-transfer db request)
    :specimen/release                            (propose-specimen-release db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは動植物園の個体移動・放出エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:specimen/upsert|:assessment/set|:welfare-screening/set|"
       ":specimen/mark-transferred|:specimen/mark-released) "
       ":stake(:actuation/transfer-specimen か :actuation/release-specimen か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:specimen (store/specimen st subject)}
    :welfare/screen       {:specimen (store/specimen st subject)}
    :specimen/transfer    {:specimen (store/specimen st subject)}
    :specimen/release     {:specimen (store/specimen st subject)}
    {:specimen (store/specimen st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Conservation Governor
  escalates/holds -- an LLM hiccup can never auto-transfer or auto-
  release a specimen."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :conservationopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
