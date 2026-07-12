(ns trustfund.governor
  "TrustFundGovernor -- the independent compliance layer for the trust/
  fund-vehicle actor. Matches `:itonami.blueprint/governor
  :trust-fund-governor` in this repo's `blueprint.edn`.

  This is the venue where `cloud-itonami-isic-6499`'s (the VC-fund
  investment actor's) capital-call PROPOSAL becomes a real, binding
  NOTICE the trust/fund vehicle actually sends to its subscribed LPs, its
  exit-distribution fact becomes a real disbursement THIS vehicle
  actually pays out to them, AND its NAV/capital-account report becomes a
  real disclosure THIS vehicle actually sends its LPs. The two repos are
  separate legal entities with separate fiduciary duties, so this
  governor NEVER trusts the upstream `vcfund` capital-call draft's own
  numbers -- it independently recomputes the pro-rata allocation from ITS
  OWN subscription ledger (`trustfund.registry/capital-call-allocations`,
  a deliberately separate re-implementation of the same well-known math,
  not a shared-code call into `vcfund.registry`) and HARD-holds on any
  mismatch, exactly the same 'never trust the advisor's self-check'
  discipline `vcfund.governor/overcall-violations` applies to the
  investment actor's own proposals.

  Distribution recording is honestly NARROWER: unlike a capital call
  (where `vcfund` itself computes a per-LP allocation this governor can
  independently re-verify against), `vcfund.registry/register-
  distribution`'s waterfall reports only a fund-wide `total_to_lp`
  aggregate -- it never computes a per-LP breakdown at all. There is no
  upstream claim to check `:distribution/record` against; THIS vehicle's
  own subscription ledger (`trustfund.registry/distribution-
  allocations`) is the ONLY source for the per-LP split, since it alone
  holds the legal subscription relationships. So this op's HARD checks
  are narrower: a non-empty subscription base to pro-rate across, and no
  double-recording of the same upstream commitment -- see
  `distribution-already-recorded-violations`'s docstring.

  NAV disclosure is narrower still: `vcfund.nav`'s reports carry NAV and
  per-LP ownership/distribution shares this vehicle has NO WAY to
  independently recompute at all (that math depends on portfolio
  valuations, a domain entirely `vcfund`'s own -- this vehicle has no
  deal/portfolio data). The ONE figure this vehicle DOES independently
  track -- each LP's `:called-amount`, advanced only by its OWN
  `:capital-call/issue-notice` history -- is the only cross-check
  possible here; everything else in the disclosure is carried through as
  a reported fact, not a verified one. See `called-amount-mismatch-
  violations`'s docstring.

  Eight checks, in priority order. The first seven are HARD violations: a
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
    4. No subscriptions for distribution -- for `:distribution/record`,
       does this vehicle have at least one subscription on file to
       pro-rate the distribution across? Cannot disburse to an empty
       subscriber base.
    5. Distribution already recorded -- for `:distribution/record`, has
       a distribution already been recorded for this upstream
       `commitment_number`? Refuses a double-recording, off this
       vehicle's own history -- no upstream comparison needed (see ns
       docstring for why this op has no allocation-mismatch check).
    6. NAV-disclosure subscription missing -- for `:nav/disclose`, does
       EVERY LP in the upstream capital-account report have an executed
       subscription on file with THIS trust/fund vehicle?
    7. Called-amount mismatch -- for `:nav/disclose`, does each LP's
       called-amount in the upstream capital-account report match THIS
       vehicle's own subscription ledger? A direct comparison, not a
       recompute (see ns docstring for why NAV/ownership shares
       themselves cannot be independently re-verified here).
    8. Confidence floor / actuation gate -- LLM confidence below
       threshold, OR the op is `:capital-call/issue-notice`/
       `:distribution/record`/`:nav/disclose` (REAL legal acts -- see
       README `Actuation`) -> escalate."
  (:require [clojure.string :as str]
            [trustfund.kernels.gate :as gate]
            [trustfund.registry :as registry]
            [trustfund.store :as store]))

(def confidence-floor
  "Documented threshold. The DECIDING copy is
  `trustfund.kernels.gate/confidence-floor-x100` (integer x100 in the
  safety kernel); this def is kept for callers/docs and pinned equal
  by `trustfund.kernels.gate-test`."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Issuing a capital-call notice and recording an LP distribution are two
  real-world actuation events moving capital between this vehicle and its
  subscribed LPs; disclosing NAV is a third -- a statement of fact an LP
  will rely on financially, even though it moves no capital itself."
  #{:actuation/issue-notice :actuation/record-distribution :actuation/disclose-nav})

(def allocation-tolerance
  "Documented tolerance for comparing an upstream draft's claimed
  allocation against this vehicle's own independent recomputation. Not a
  business tolerance for real money mismatches -- purely for double
  arithmetic, kept tiny. The DECIDING copy is
  `trustfund.kernels.gate/allocation-tolerance-x1e6` (one x1e6
  micro-unit in the safety kernel); this def is kept for callers/docs
  and pinned equal by `trustfund.kernels.gate-test`."
  1e-6)

(defn- confidence->x100
  "Host bridge (façade-side, not kernel vocabulary): scale a 0.0..1.0
  advisor confidence to the kernel's integer x100 wire code."
  [c]
  (Math/round (* 100.0 (double c))))

(defn- amount->x1e6
  "Host bridge (façade-side, not kernel vocabulary): round a monetary
  amount to the kernel's x1e6 (micro-unit) integer wire value.
  Rounding both sides to micro-units before the in-kernel 1-micro-unit
  tolerance matches the old |a - b| < 1e-6 predicate and absorbs
  double-arithmetic noise the same way."
  [v]
  (Math/round (* 1000000.0 (double v))))

(defn- close?
  "Scalar amount comparison, DECIDED by the safety kernel
  (`gate/amount-mismatch` over x1e6 micro-unit integers) — the per-LP
  collection matching around it stays in this façade (see the kernel's
  ns docstring for the thin-repo adaptation)."
  [a b]
  (= 0 (gate/amount-mismatch (amount->x1e6 a) (amount->x1e6 b))))

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

(defn- no-subscriptions-for-distribution-violations
  "For `:distribution/record`, this vehicle must actually have at least
  one subscription on file to pro-rate the distribution across."
  [{:keys [op]} st]
  (when (= op :distribution/record)
    (when (empty? (store/all-lps st))
      [{:rule :no-subscriptions-for-distribution
        :detail "subscriptionが1件も無い状態でのLP distribution記録提案"}])))

(defn- distribution-already-recorded-violations
  "For `:distribution/record`, refuses to record a distribution for the
  SAME upstream `commitment_number` twice, off this vehicle's own
  distribution history -- needs no upstream comparison at all (see ns
  docstring for why distribution recording has no allocation-mismatch
  check the way capital-call notices do)."
  [{:keys [op upstream-distribution-fact]} st]
  (when (= op :distribution/record)
    (let [upstream-commitment-number (get-in upstream-distribution-fact ["record" "commitment_number"])]
      (when (store/commitment-already-distributed? st upstream-commitment-number)
        [{:rule :distribution-already-recorded
          :detail (str upstream-commitment-number " は既にdistribution記録済み")}]))))

(defn- nav-disclosure-subscription-missing-violations
  "For `:nav/disclose`, every LP in the upstream `vcfund.nav/lp-capital-
  account-report` must have an executed subscription on file with THIS
  trust/fund vehicle -- it cannot disclose a capital-account slice for a
  party it has no legal subscription relationship with."
  [{:keys [op upstream-lp-capital-accounts]} st]
  (when (= op :nav/disclose)
    (let [lp-ids (map :lp-id upstream-lp-capital-accounts)
          missing (remove #(store/lp st %) lp-ids)]
      (when (seq missing)
        [{:rule :nav-disclosure-subscription-missing
          :detail (str "subscriptionが無いLPを含むNAV開示提案: " (str/join ", " missing))}]))))

(defn- called-amount-mismatch-violations
  "For `:nav/disclose`, the ONE figure this vehicle CAN independently
  verify: each LP's `:called-amount` in the upstream `vcfund.nav`
  capital-account report must match THIS vehicle's own subscription
  ledger (advanced only by this vehicle's own `:capital-call/issue-
  notice` history -- a SEPARATE book of record from `vcfund`'s own).
  Unlike `allocation-mismatch-violations`, this is NOT an independent
  RECOMPUTE (there is no pro-rata formula to re-derive for a disclosure)
  -- just a direct comparison against this vehicle's own stored fact, the
  same shape `subscription-missing-violations` already uses. Skips any
  LP already flagged missing above -- there is nothing local to compare
  a missing LP's amount against."
  [{:keys [op upstream-lp-capital-accounts]} st]
  (when (= op :nav/disclose)
    (let [comparable (filter #(store/lp st (:lp-id %)) upstream-lp-capital-accounts)
          mismatched (remove (fn [{:keys [lp-id called-amount]}]
                               (close? called-amount (:called-amount (store/lp st lp-id))))
                             comparable)]
      (when (seq mismatched)
        [{:rule :called-amount-mismatch
          :detail (str (count mismatched) "件のLP called-amountが本ビークル自身の台帳と一致しない")}]))))

(defn check
  "Censors a TrustAdmin-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [unacc-v (unaccredited-subscriber-violations request proposal)
        smiss-v (subscription-missing-violations request st)
        alloc-v (allocation-mismatch-violations request st)
        nosub-v (no-subscriptions-for-distribution-violations request st)
        dup-v   (distribution-already-recorded-violations request st)
        nmiss-v (nav-disclosure-subscription-missing-violations request st)
        callm-v (called-amount-mismatch-violations request st)
        hard (into [] (concat unacc-v smiss-v alloc-v nosub-v dup-v nmiss-v callm-v))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))
        ;; The decision itself is delegated to the safety kernel
        ;; (trustfund.kernels.gate, integer-coded fail-closed core);
        ;; this façade only gathers evidence (violation lists with
        ;; human-readable details, incl. the per-LP collection matching
        ;; whose scalar comparisons the kernel already decided via
        ;; close?) and maps codes back to keywords. Kernel is stricter
        ;; than the old inline logic on ONE case by design: an
        ;; out-of-range confidence (< 0 or > 1.0) now escalates instead
        ;; of counting as high confidence.
        code (gate/verdict-code (if (seq unacc-v) 1 0)
                                (if (seq smiss-v) 1 0)
                                (if (seq alloc-v) 1 0)
                                (if (seq nosub-v) 1 0)
                                (if (seq dup-v) 1 0)
                                (if (seq nmiss-v) 1 0)
                                (if (seq callm-v) 1 0)
                                (confidence->x100 conf)
                                (if stakes? 1 0))]
    {:ok?          (= 0 code)
     :violations   hard
     :confidence   conf
     :hard?        (= 2 code)
     :escalate?    (= 1 code)
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
