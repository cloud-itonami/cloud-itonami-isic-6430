(ns trustfund.advisor
  "TrustAdmin-LLM client -- the *contained intelligence node* for the
  trust/fund-vehicle actor. It normalizes LP subscription intake, drafts
  a capital-call NOTICE off an UPSTREAM `cloud-itonami-isic-6499`
  (`vcfund`) capital-call draft, AND drafts an LP-DISTRIBUTION record off
  an upstream exit-distribution fact. CRITICAL: it is a smart-but-
  untrusted advisor -- it returns a *proposal*, never a committed record
  or a real legal act. Every output is censored downstream by
  `trustfund.governor` before anything touches the SSoT, and
  `:capital-call/issue-notice`/`:distribution/record` NEVER auto-commit
  at any phase -- see README `Actuation`.

  Like `vcfund.ddllm`, this is a deterministic mock so the actor graph
  runs offline and the governor contract is exercised end-to-end. In
  production this calls a real LLM with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/issue-notice | :actuation/record-distribution | nil
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

(defn- propose-distribution-record
  "Draft the LP-DISTRIBUTION recording action -- the trust/fund
  vehicle's own legal act of paying out exit proceeds to its subscribed
  LPs, off an UPSTREAM `vcfund.registry/register-distribution`-shaped
  fact (`:upstream-distribution-fact`, the exact `{\"record\"
  {\"commitment_number\" .. \"waterfall\" {:total-to-lp .. ..} ..} ..}` map
  the investment actor produced -- a REAL fact this advisor reads, never
  invents; the per-LP SPLIT itself is computed by THIS vehicle from its
  own subscription ledger, not claimed by the upstream fact at all --
  `vcfund.registry/distribute-waterfall` reports only a fund-wide
  `total_to_lp` aggregate, see `trustfund.governor`'s docstring). ALWAYS
  `:stake :actuation/record-distribution` -- a REAL-WORLD act, never a
  draft the actor may auto-run. See README `Actuation`."
  [_st {:keys [upstream-distribution-fact jurisdiction effective-date]}]
  (let [record (get upstream-distribution-fact "record")
        upstream-commitment-number (get record "commitment_number")
        distribution-amount (:total-to-lp (get record "waterfall"))]
    {:summary    (str "upstream distribution " upstream-commitment-number
                      " (total_to_lp=" distribution-amount ") 向け記録提案")
     :rationale  (str "upstream vcfund exit-distribution fact: " upstream-commitment-number)
     :cites      [upstream-commitment-number]
     :effect     :distribution/recorded
     :value      {:upstream-commitment-number upstream-commitment-number
                 :distribution-amount distribution-amount
                 :jurisdiction jurisdiction
                 :effective-date effective-date}
     :stake      :actuation/record-distribution
     :confidence (if (and upstream-commitment-number distribution-amount) 0.9 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [st {:keys [op] :as request}]
  (case op
    :subscription/record       (normalize-subscription st request)
    :capital-call/issue-notice (propose-capital-call-notice st request)
    :distribution/record       (propose-distribution-record st request)
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
