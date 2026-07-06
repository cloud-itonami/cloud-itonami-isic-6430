(ns trustfund.registry
  "Pure-function subscription-agreement and capital-call-NOTICE record
  construction -- an append-only trust/fund-vehicle book-of-record draft.

  This is the LEGAL entity's own registry, distinct from (and never a
  code dependency of) `cloud-itonami-isic-6499`'s `vcfund.registry`. The
  two repos interoperate ONLY through a documented DATA CONTRACT: this
  namespace's `capital-call-allocations` independently REIMPLEMENTS the
  same pro-rata-by-commitment-share math `vcfund.registry/capital-call-
  allocations` does, deliberately -- a shared library here would mean a
  bug in ONE implementation silently defeats the whole point of
  `trustfund.governor`'s independent re-verification of an upstream
  `vcfund` capital-call draft (see its docstring). Two independent
  implementations of well-known, unambiguous math (pro-rata shares) is a
  feature, not duplication to be refactored away.

  Like `vcfund.registry`, there is no single international identifier
  standard for a fund vehicle's subscription/capital-call-notice record,
  so this namespace does not invent one -- it validates required fields
  and assigns a fund-scoped sequence number."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is a
  human trustee/fund-officer's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-subscription
  "Validate + construct an LP subscription-agreement registration DRAFT --
  the trust/fund vehicle's own record of a limited partner's executed
  commitment. Pure function -- does not touch any real transfer-agent/
  fund-accounting system."
  [lp-id commitment-amount currency jurisdiction accredited? sequence]
  (when-not (and lp-id (not= lp-id ""))
    (throw (ex-info "subscription: lp-id required" {})))
  (when (neg? commitment-amount)
    (throw (ex-info "subscription: commitment-amount must be >= 0" {})))
  (when-not (and currency (not= currency ""))
    (throw (ex-info "subscription: currency required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "subscription: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "subscription: sequence must be >= 0" {})))
  (let [subscription-number (str (str/upper-case jurisdiction) "-SUB-" (zero-pad sequence 8))
        record {"record_id" subscription-number
                "kind" "subscription-draft"
                "lp_id" lp-id
                "commitment_amount" (double commitment-amount)
                "currency" currency
                "jurisdiction" jurisdiction
                "accredited" (boolean accredited?)
                "immutable" true}]
    {"record" record "subscription_number" subscription-number
     "certificate" (unsigned-certificate "SubscriptionCertificate" subscription-number subscription-number)}))

(defn capital-call-allocations
  "Pure pro-rata capital-call allocation across subscribed LPs, by
  commitment share -- INDEPENDENTLY reimplemented from `vcfund.registry/
  capital-call-allocations` (see ns docstring for why that is
  deliberate, not duplication).

  `subscriptions` -- coll of `{:id .. :commitment-amount ..
  :called-amount ..}` (the SAME shape `trustfund.store/all-lps` returns
  -- `:called-amount` defaults to 0).
  `call-amount` -- the total amount the fund is drawing down.

  Returns one map per LP: `{:lp-id :commitment-amount :called-amount
  :allocation :new-called-amount :overcall?}` (`:lp-id` here, mirroring
  `vcfund.registry/capital-call-allocations`'s own input->output
  convention: the OUTPUT row is keyed `:lp-id`, the INPUT directory
  entry is keyed `:id`)."
  [subscriptions call-amount]
  (when (neg? call-amount) (throw (ex-info "capital-call: call-amount must be >= 0" {})))
  (when (empty? subscriptions) (throw (ex-info "capital-call: no subscriptions on file" {})))
  (let [total-committed (reduce + (map :commitment-amount subscriptions))]
    (when (zero? total-committed)
      (throw (ex-info "capital-call: total subscribed commitment is zero" {})))
    (mapv (fn [{:keys [id commitment-amount called-amount]}]
            (let [commitment-amount (double commitment-amount)
                  called-amount (double (or called-amount 0))
                  share (/ commitment-amount (double total-committed))
                  allocation (* share (double call-amount))
                  new-called (+ called-amount allocation)]
              {:lp-id id
               :commitment-amount commitment-amount
               :called-amount called-amount
               :allocation allocation
               :new-called-amount new-called
               :overcall? (> new-called commitment-amount)}))
          subscriptions)))

(defn register-capital-call-notice
  "Validate + construct the capital-call NOTICE DRAFT -- the trust/fund
  vehicle's own legal act of demanding funds from its subscribed LPs,
  issued off an UPSTREAM `vcfund.registry/register-capital-call` draft
  (`upstream-call-number`, for traceability back to the investment
  actor's proposal that triggered this). Pure function -- does not touch
  any real banking/wire system; it builds the RECORD the trustee/fund
  officer would keep and actually send to LPs. `trustfund.governor`
  independently re-verifies `allocations` against its OWN subscription
  ledger before this is ever allowed to commit -- never trusts the
  upstream call draft's numbers as-is."
  [upstream-call-number allocations call-amount jurisdiction sequence notice-date]
  (when-not (and upstream-call-number (not= upstream-call-number ""))
    (throw (ex-info "capital-call-notice: upstream-call-number required" {})))
  (when (empty? allocations)
    (throw (ex-info "capital-call-notice: allocations required" {})))
  (when (neg? call-amount)
    (throw (ex-info "capital-call-notice: call-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "capital-call-notice: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "capital-call-notice: sequence must be >= 0" {})))
  (when-not (and notice-date (not= notice-date ""))
    (throw (ex-info "capital-call-notice: notice-date required" {})))
  (let [notice-number (str (str/upper-case jurisdiction) "-NOTICE-" (zero-pad sequence 6))
        record {"record_id" notice-number
                "kind" "capital-call-notice-draft"
                "upstream_call_number" upstream-call-number
                "call_amount" (double call-amount)
                "allocations" (mapv (fn [{:keys [lp-id allocation new-called-amount]}]
                                      {"lp_id" lp-id "allocation" allocation
                                       "new_called_amount" new-called-amount})
                                    allocations)
                "notice_date" notice-date
                "immutable" true}]
    {"record" record "notice_number" notice-number
     "certificate" (unsigned-certificate "CapitalCallNoticeCertificate" notice-number notice-number)}))

(defn append
  "Append a subscription/notice record, returning a NEW list (never
  mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
