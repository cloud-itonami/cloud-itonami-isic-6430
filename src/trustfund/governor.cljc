(ns trustfund.governor
  "TrustFundGovernor -- the independent compliance layer for the trust/
  fund-vehicle actor. Matches `:itonami.blueprint/governor
  :trust-fund-governor` in this repo's `blueprint.edn`.

  This is the venue where `cloud-itonami-isic-6499`'s (the VC-fund
  investment actor's) capital-call PROPOSAL becomes a real, binding
  NOTICE the trust/fund vehicle actually sends to its subscribed LPs. The
  two repos are separate legal entities with separate fiduciary duties,
  so this governor NEVER trusts the upstream `vcfund` capital-call
  draft's own numbers -- it independently recomputes the pro-rata
  allocation from ITS OWN subscription ledger
  (`trustfund.registry/capital-call-allocations`, a deliberately separate
  re-implementation of the same well-known math, not a shared-code call
  into `vcfund.registry`) and HARD-holds on any mismatch, exactly the
  same 'never trust the advisor's self-check' discipline
  `vcfund.governor/overcall-violations` applies to the investment actor's
  own proposals.

  Four checks, in priority order. The first three are HARD violations: a
  human approver CANNOT override them.

    1. Unaccredited subscriber -- for `:subscription/record`, is the
       subscriber affirmed accredited? A fund cannot record a
       subscription for an unaccredited investor.
    2. Subscription missing -- for `:capital-call/issue-notice`, does
       EVERY LP the upstream call draft allocates to have an executed
       subscription on file with THIS trust/fund vehicle? A capital call
       cannot be issued to a party the fund has no legal subscription
       relationship with.
    3. Allocation mismatch -- for `:capital-call/issue-notice`, does the
       upstream `vcfund` draft's claimed per-LP allocation match what
       THIS vehicle's own subscription ledger, independently
       pro-rata-recomputed, says it should be (within floating-point
       tolerance)? A mismatch means either side's data has drifted, or
       the upstream draft was tampered with -- never silently accept it.
       Recomputing independently also catches an overcall (any LP's
       cumulative called amount exceeding their subscribed commitment)
       the SAME check surfaces, since an overcalled allocation can never
       match a correctly-recomputed one.
    4. Confidence floor / actuation gate -- LLM confidence below
       threshold, OR the op is `:capital-call/issue-notice` (a REAL
       legal act -- see README `Actuation`) -> escalate."
  (:require [clojure.string :as str]
            [trustfund.registry :as registry]
            [trustfund.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean. Issuing
  a capital-call notice is the one real-world actuation event this actor
  performs -- a legally binding demand for funds sent to LPs."
  #{:actuation/issue-notice})

(def ^:private allocation-tolerance
  "Floating-point tolerance for comparing an upstream draft's claimed
  allocation against this vehicle's own independent recomputation. Not a
  business tolerance for real money mismatches -- purely for double
  arithmetic, kept tiny."
  1e-6)

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) allocation-tolerance))

(defn- unaccredited-subscriber-violations
  "For `:subscription/record`, the subscriber must be affirmed
  accredited -- a fund cannot record a subscription for an unaccredited
  investor."
  [{:keys [op]} proposal]
  (when (= op :subscription/record)
    (when-not (true? (get-in proposal [:value :accredited?]))
      [{:rule :unaccredited-subscriber
        :detail "適格投資家確認が取れていない主体のsubscription登録は不可"}])))

(defn- subscription-missing-violations
  "For `:capital-call/issue-notice`, every LP the upstream draft
  allocates to must have an executed subscription on file with THIS
  trust/fund vehicle."
  [{:keys [op upstream-call-draft]} st]
  (when (= op :capital-call/issue-notice)
    (let [allocated-lp-ids (map #(get % "lp_id") (get-in upstream-call-draft ["record" "allocations"]))
          missing (remove #(store/lp st %) allocated-lp-ids)]
      (when (seq missing)
        [{:rule :subscription-missing
          :detail (str "subscriptionが無いLPへのcapital-call notice発行提案: " (str/join ", " missing))}]))))

(defn- allocation-mismatch-violations
  "For `:capital-call/issue-notice`, independently recompute the
  pro-rata allocation from THIS vehicle's own subscription ledger
  (`trustfund.registry/capital-call-allocations`) and compare it against
  the upstream `vcfund` draft's claimed per-LP allocation. ANY mismatch
  (including one caused by an overcall on the recomputed side) is a HARD
  violation -- never trust the upstream draft's numbers as-is."
  [{:keys [op upstream-call-draft]} st]
  (when (= op :capital-call/issue-notice)
    (let [claimed (get-in upstream-call-draft ["record" "allocations"])
          call-amount (get-in upstream-call-draft ["record" "call_amount"])
          subscriptions (map #(select-keys % [:id :commitment-amount :called-amount]) (store/all-lps st))]
      (if (empty? subscriptions)
        [{:rule :allocation-mismatch
          :detail "subscriptionが1件も無い状態でのcapital-call notice発行提案"}]
        (let [recomputed (registry/capital-call-allocations subscriptions call-amount)
              by-id (into {} (map (juxt :lp-id identity)) recomputed)
              mismatched (remove (fn [{:strs [lp_id allocation]}]
                                  (when-let [r (get by-id lp_id)]
                                    (and (close? allocation (:allocation r))
                                         (not (:overcall? r)))))
                                claimed)]
          (when (seq mismatched)
            [{:rule :allocation-mismatch
              :detail (str (count mismatched) "件のLP配分が独立再計算結果と一致しない、"
                          "または独立再計算がovercallを検出")}]))))))

(defn check
  "Censors a TrustAdmin-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (unaccredited-subscriber-violations request proposal)
                           (subscription-missing-violations request st)
                           (allocation-mismatch-violations request st)))
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
