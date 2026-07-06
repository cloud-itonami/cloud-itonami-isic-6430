(ns trustfund.registry
  "Pure-function subscription-agreement, capital-call-NOTICE,
  LP-distribution-NOTICE and NAV-DISCLOSURE record construction -- an
  append-only trust/fund-vehicle book-of-record draft.

  This is the LEGAL entity's own registry, distinct from (and never a
  code dependency of) `cloud-itonami-isic-6499`'s `vcfund.registry`. The
  two repos interoperate ONLY through a documented DATA CONTRACT: this
  namespace's `capital-call-allocations`/`distribution-allocations`
  independently REIMPLEMENT the same pro-rata-by-commitment-share math
  `vcfund.registry/capital-call-allocations` does (for capital moving IN
  from LPs and OUT to LPs respectively), deliberately -- a shared
  library here would mean a bug in ONE implementation silently defeats
  the whole point of `trustfund.governor`'s independent re-verification
  of an upstream `vcfund` fact (see its docstring). Two independent
  implementations of well-known, unambiguous math (pro-rata shares) is a
  feature, not duplication to be refactored away.

  Like `vcfund.registry`, there is no single international identifier
  standard for a fund vehicle's subscription/capital-call-notice/
  distribution-notice record, so this namespace does not invent one --
  it validates required fields and assigns a fund-scoped sequence
  number."
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

(defn distribution-allocations
  "Pure pro-rata DISTRIBUTION allocation across subscribed LPs, by
  commitment share -- the SAME math `capital-call-allocations` uses,
  applied to capital going OUT to LPs instead of capital being called IN
  from them (the simplest, most common LPA convention: distributions are
  pro-rated by commitment share, the same base every capital call
  already uses -- no per-LP waterfall tiers modeled here).

  `subscriptions` -- the SAME shape `capital-call-allocations` takes.
  `distribution-amount` -- the total amount (the upstream `vcfund`
  exit-distribution fact's `total_to_lp`) being paid out to LPs.

  Returns one map per LP: `{:lp-id :commitment-amount :allocation}`."
  [subscriptions distribution-amount]
  (when (neg? distribution-amount) (throw (ex-info "distribution: distribution-amount must be >= 0" {})))
  (when (empty? subscriptions) (throw (ex-info "distribution: no subscriptions on file" {})))
  (let [total-committed (reduce + (map :commitment-amount subscriptions))]
    (when (zero? total-committed)
      (throw (ex-info "distribution: total subscribed commitment is zero" {})))
    (mapv (fn [{:keys [id commitment-amount]}]
            (let [commitment-amount (double commitment-amount)
                  share (/ commitment-amount (double total-committed))]
              {:lp-id id
               :commitment-amount commitment-amount
               :allocation (* share (double distribution-amount))}))
          subscriptions)))

(defn register-distribution-notice
  "Validate + construct the LP-DISTRIBUTION NOTICE DRAFT -- the
  trust/fund vehicle's own legal act of disbursing exit proceeds to its
  subscribed LPs, issued off an UPSTREAM `vcfund.registry/register-
  distribution` fact (`upstream-commitment-number`, for traceability
  back to the investment actor's exit-distribution proposal that
  triggered this). Pure function -- does not touch any real banking/wire
  system; it builds the RECORD the trustee/fund officer would keep and
  actually pay out to LPs. `trustfund.governor` independently
  re-verifies `allocations` against its OWN subscription ledger, and
  blocks a double-recording of the same upstream commitment number,
  before this is ever allowed to commit -- never trusts the upstream
  fact's numbers as-is."
  [upstream-commitment-number allocations distribution-amount jurisdiction sequence effective-date]
  (when-not (and upstream-commitment-number (not= upstream-commitment-number ""))
    (throw (ex-info "distribution-notice: upstream-commitment-number required" {})))
  (when (empty? allocations)
    (throw (ex-info "distribution-notice: allocations required" {})))
  (when (neg? distribution-amount)
    (throw (ex-info "distribution-notice: distribution-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "distribution-notice: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "distribution-notice: sequence must be >= 0" {})))
  (when-not (and effective-date (not= effective-date ""))
    (throw (ex-info "distribution-notice: effective-date required" {})))
  (let [distribution-number (str (str/upper-case jurisdiction) "-DIST-" (zero-pad sequence 6))
        record {"record_id" distribution-number
                "kind" "distribution-notice-draft"
                "upstream_commitment_number" upstream-commitment-number
                "distribution_amount" (double distribution-amount)
                "allocations" (mapv (fn [{:keys [lp-id allocation]}]
                                      {"lp_id" lp-id "allocation" allocation})
                                    allocations)
                "effective_date" effective-date
                "immutable" true}]
    {"record" record "distribution_number" distribution-number
     "certificate" (unsigned-certificate "DistributionNoticeCertificate" distribution-number distribution-number)}))

(defn register-nav-disclosure
  "Validate + construct the NAV-DISCLOSURE DRAFT -- the trust/fund
  vehicle's own act of disclosing its current whole-fund NAV and each
  LP's own capital-account slice, issued off an UPSTREAM
  `vcfund.nav/fund-nav-report`/`lp-capital-account-report` fact. Unlike a
  capital-call or distribution notice, this vehicle does NOT
  independently recompute the NAV or any LP's ownership/distribution
  shares -- it has no portfolio-valuation data of its own to derive them
  from, only its own subscription/capital-call ledger. `trustfund.
  governor` therefore verifies the ONE figure it CAN independently check
  (each LP's called-amount, tracked by THIS vehicle's own capital-call-
  notice history) and refuses to disclose about an LP with no
  subscription on file -- see governor docstring for why a NAV/ownership-
  share mismatch check is not possible here.

  `lp-accounts` -- coll of `vcfund.nav/lp-capital-account`-shaped maps
  (`{:lp-id :commitment-amount :called-amount :unfunded :ownership-pct
  :distributed-to-date :nav-share}`), carried through into the record
  verbatim once the governor has verified `:called-amount`."
  [nav as-of-date lp-accounts jurisdiction sequence]
  (when (nil? nav)
    (throw (ex-info "nav-disclosure: nav required" {})))
  (when-not (and as-of-date (not= as-of-date ""))
    (throw (ex-info "nav-disclosure: as-of-date required" {})))
  (when (empty? lp-accounts)
    (throw (ex-info "nav-disclosure: lp-accounts required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "nav-disclosure: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "nav-disclosure: sequence must be >= 0" {})))
  (let [disclosure-number (str (str/upper-case jurisdiction) "-NAV-" (zero-pad sequence 6))
        record {"record_id" disclosure-number
                "kind" "nav-disclosure-draft"
                "nav" (double nav)
                "as_of_date" as-of-date
                "lp_accounts" (mapv (fn [{:keys [lp-id commitment-amount called-amount unfunded
                                                 ownership-pct distributed-to-date nav-share]}]
                                      {"lp_id" lp-id "commitment_amount" commitment-amount
                                       "called_amount" called-amount "unfunded" unfunded
                                       "ownership_pct" ownership-pct
                                       "distributed_to_date" distributed-to-date
                                       "nav_share" nav-share})
                                    lp-accounts)
                "immutable" true}]
    {"record" record "disclosure_number" disclosure-number
     "certificate" (unsigned-certificate "NavDisclosureCertificate" disclosure-number disclosure-number)}))

(defn append
  "Append a subscription/notice/distribution/nav-disclosure record,
  returning a NEW list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
