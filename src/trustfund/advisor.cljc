(ns trustfund.advisor
  "TrustAdmin-LLM client -- the *contained intelligence node* for the
  trust/fund-vehicle actor. It normalizes LP subscription intake and
  drafts a capital-call NOTICE off an UPSTREAM `cloud-itonami-isic-6499`
  (`vcfund`) capital-call draft. CRITICAL: it is a smart-but-untrusted
  advisor -- it returns a *proposal*, never a committed record or a real
  legal act. Every output is censored downstream by `trustfund.governor`
  before anything touches the SSoT, and `:capital-call/issue-notice`
  NEVER auto-commits at any phase -- see README `Actuation`.

  Like `vcfund.ddllm`, this is a deterministic mock so the actor graph
  runs offline and the governor contract is exercised end-to-end. In
  production this calls a real LLM with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/issue-notice | nil
     :confidence 0..1}")

(defn- normalize-subscription
  "Directory intake -- the LLM only normalizes/validates the patch; it
  does not invent a subscriber's commitment amount or accreditation
  status. High confidence, low stakes."
  [_st {:keys [lp-id commitment-amount currency jurisdiction accredited?]}]
  {:summary    (str lp-id " のsubscription登録 (" commitment-amount " " currency ")")
   :rationale  "入力されたsubscription事実の正規化のみ。新規事実の生成なし。"
   :cites      [:lp-id :commitment-amount :currency :jurisdiction :accredited?]
   :effect     :subscription/recorded
   :value      {:lp-id lp-id :commitment-amount commitment-amount
               :currency currency :jurisdiction jurisdiction :accredited? accredited?}
   :stake      nil
   :confidence 0.95})

(defn- propose-capital-call-notice
  "Draft the capital-call NOTICE action -- the trust/fund vehicle's own
  legal act of demanding funds from its subscribed LPs, off an UPSTREAM
  `vcfund.registry/register-capital-call`-shaped draft (`:upstream-call-
  draft`, the exact `{\"record\" .. \"call_number\" .. \"certificate\" ..}`
  map the investment actor produced -- a REAL fact this advisor reads,
  never invents). ALWAYS `:stake :actuation/issue-notice` -- a REAL-WORLD
  act, never a draft the actor may auto-run. See README `Actuation`."
  [_st {:keys [upstream-call-draft jurisdiction notice-date]}]
  (let [record (get upstream-call-draft "record")
        upstream-call-number (get record "record_id")
        call-amount (get record "call_amount")]
    {:summary    (str "upstream call " upstream-call-number " (" call-amount ") 向けnotice発行提案")
     :rationale  (str "upstream vcfund capital-call draft: " upstream-call-number)
     :cites      [upstream-call-number]
     :effect     :capital-call/notice-issued
     :value      {:upstream-call-number upstream-call-number
                 :call-amount call-amount
                 :jurisdiction jurisdiction
                 :notice-date notice-date}
     :stake      :actuation/issue-notice
     :confidence (if (and upstream-call-number call-amount) 0.9 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [st {:keys [op] :as request}]
  (case op
    :subscription/record       (normalize-subscription st request)
    :capital-call/issue-notice (propose-capital-call-notice st request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
