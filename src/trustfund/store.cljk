(ns trustfund.store
  "SSoT for the trust/fund-vehicle actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam
  `cloud-itonami-isic-6499`'s `vcfund.store` uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert).

  Both implement the same protocol and pass the same contract
  (test/trustfund/store_contract_test.clj). The ledger stays append-only
  on every backend: 'which LP subscribed for how much, which capital-call
  notice, LP-distribution notice or NAV disclosure was actually issued,
  off which upstream investment-actor fact, approved by whom' is always
  a query over an immutable log."
  (:require [trustfund.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (lp [s id])
  (all-lps [s])
  (ledger [s])
  (subscription-history [s] "the append-only subscription-agreement history (trustfund.registry drafts)")
  (notice-history [s] "the append-only capital-call-notice history (trustfund.registry drafts)")
  (distribution-history [s] "the append-only LP-distribution-notice history (trustfund.registry drafts)")
  (nav-disclosure-history [s] "the append-only NAV-disclosure history (trustfund.registry drafts)")
  (subscription-sequence [s jurisdiction] "next subscription-number sequence for a jurisdiction")
  (notice-sequence [s jurisdiction] "next notice-number sequence for a jurisdiction")
  (distribution-sequence [s jurisdiction] "next distribution-number sequence for a jurisdiction")
  (nav-disclosure-sequence [s jurisdiction] "next NAV-disclosure-number sequence for a jurisdiction")
  (commitment-already-distributed? [s upstream-commitment-number] "has a distribution already been recorded for this upstream commitment?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-lps [s lps] "replace/seed the LP subscription directory (map id->lp)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained LP subscription set so the actor + tests run
  offline. Deliberately its OWN book of record -- not a copy of
  `vcfund.store/demo-data`'s LPs, since this is a separate legal entity's
  data (an operator would reconcile the two directories during real
  onboarding, this repo does not assume they are byte-identical)."
  []
  {:lps
   {"lp-1" {:id "lp-1" :commitment-amount 5000000 :called-amount 0
            :currency "USD" :jurisdiction "USA" :accredited? true}
    "lp-2" {:id "lp-2" :commitment-amount 1000000 :called-amount 0
            :currency "JPY" :jurisdiction "JPN" :accredited? true}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- record-subscription!
  "Backend-agnostic `:subscription/record` -- drafts the subscription
  record and returns {:result ..} for the caller to persist (append-only
  history; the LP directory gains a fresh `:called-amount` 0 entry)."
  [s {:keys [lp-id commitment-amount currency jurisdiction accredited?]}]
  (let [seq-n (subscription-sequence s jurisdiction)
        result (registry/register-subscription lp-id commitment-amount currency jurisdiction accredited? seq-n)]
    {:result result}))

(defn- issue-capital-call-notice!
  "Backend-agnostic `:capital-call/issue-notice` -- recomputes the
  pro-rata allocation INDEPENDENTLY from the current subscription ledger
  (never trusts the upstream `vcfund` capital-call draft's own
  allocation numbers), drafts the notice record referencing the upstream
  call-number for traceability, and returns {:result .. :allocations ..}
  for the caller to persist (each LP's `:called-amount` advances to its
  `:new-called-amount`)."
  [s {:keys [upstream-call-number call-amount jurisdiction notice-date]}]
  (let [allocations (registry/capital-call-allocations
                     (map #(select-keys % [:id :commitment-amount :called-amount])
                          (all-lps s))
                     call-amount)
        seq-n (notice-sequence s jurisdiction)
        result (registry/register-capital-call-notice
                upstream-call-number allocations call-amount jurisdiction seq-n notice-date)]
    {:result result :allocations allocations}))

(defn- record-distribution!
  "Backend-agnostic `:distribution/record` -- recomputes the pro-rata
  distribution allocation INDEPENDENTLY from the current subscription
  ledger (never trusts the upstream `vcfund` exit-distribution fact's
  own per-LP breakdown), drafts the distribution-notice record
  referencing the upstream commitment-number for traceability, and
  returns {:result .. :allocations ..} for the caller to persist."
  [s {:keys [upstream-commitment-number distribution-amount jurisdiction effective-date]}]
  (let [allocations (registry/distribution-allocations
                     (map #(select-keys % [:id :commitment-amount])
                          (all-lps s))
                     distribution-amount)
        seq-n (distribution-sequence s jurisdiction)
        result (registry/register-distribution-notice
                upstream-commitment-number allocations distribution-amount jurisdiction seq-n effective-date)]
    {:result result :allocations allocations}))

(defn- disclose-nav!
  "Backend-agnostic `:nav/disclose` -- drafts the NAV-disclosure record
  referencing the upstream `vcfund.nav` report/capital-account facts, and
  returns {:result ..} for the caller to persist. Does NOT recompute NAV
  or ownership shares (this vehicle has no portfolio data of its own --
  see `trustfund.registry/register-nav-disclosure` docstring); the
  upstream lp-accounts are carried through verbatim once `trustfund.
  governor` has independently verified each LP's called-amount against
  THIS vehicle's own ledger."
  [s {:keys [nav as-of-date lp-accounts jurisdiction]}]
  (let [seq-n (nav-disclosure-sequence s jurisdiction)
        result (registry/register-nav-disclosure nav as-of-date lp-accounts jurisdiction seq-n)]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (lp [_ id] (get-in @a [:lps id]))
  (all-lps [_] (sort-by :id (vals (:lps @a))))
  (ledger [_] (:ledger @a))
  (subscription-history [_] (:subscription-history @a))
  (notice-history [_] (:notice-history @a))
  (distribution-history [_] (:distribution-history @a))
  (nav-disclosure-history [_] (:nav-disclosure-history @a))
  (subscription-sequence [_ jurisdiction] (get-in @a [:subscription-sequences jurisdiction] 0))
  (notice-sequence [_ jurisdiction] (get-in @a [:notice-sequences jurisdiction] 0))
  (distribution-sequence [_ jurisdiction] (get-in @a [:distribution-sequences jurisdiction] 0))
  (nav-disclosure-sequence [_ jurisdiction] (get-in @a [:nav-disclosure-sequences jurisdiction] 0))
  (commitment-already-distributed? [_ upstream-commitment-number]
    (boolean (some #(= upstream-commitment-number (get % "upstream_commitment_number")) (:distribution-history @a))))
  (commit-record! [s {:keys [effect payload]}]
    (case effect
      :subscription/recorded
      (let [{:keys [result]} (record-subscription! s payload)
            lp-id (:lp-id payload)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:subscription-sequences (:jurisdiction payload)] (fnil inc 0))
                       (assoc-in [:lps lp-id]
                                 {:id lp-id
                                  :commitment-amount (double (:commitment-amount payload))
                                  :called-amount 0.0
                                  :currency (:currency payload)
                                  :jurisdiction (:jurisdiction payload)
                                  :accredited? (boolean (:accredited? payload))})
                       (update :subscription-history registry/append result))))
        result)

      :capital-call/notice-issued
      (let [{:keys [result allocations]} (issue-capital-call-notice! s payload)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:notice-sequences (:jurisdiction payload)] (fnil inc 0))
                       (update :lps (fn [lps]
                                      (reduce (fn [m {:keys [lp-id new-called-amount]}]
                                                (update m lp-id assoc :called-amount new-called-amount))
                                              lps allocations)))
                       (update :notice-history registry/append result))))
        result)

      :distribution/recorded
      (let [{:keys [result]} (record-distribution! s payload)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:distribution-sequences (:jurisdiction payload)] (fnil inc 0))
                       (update :distribution-history registry/append result))))
        result)

      :nav/disclosed
      (let [{:keys [result]} (disclose-nav! s payload)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:nav-disclosure-sequences (:jurisdiction payload)] (fnil inc 0))
                       (update :nav-disclosure-history registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-lps [s lps] (when (seq lps) (swap! a update :lps merge lps)) s))

(defn seed-db
  "A MemStore seeded with the demo LP-subscription set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :subscription-sequences {} :notice-sequences {} :distribution-sequences {}
                           :nav-disclosure-sequences {}
                           :subscription-history [] :notice-history [] :distribution-history []
                           :nav-disclosure-history []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  The same convention `vcfund.store` uses -- compound values (ledger
  facts, subscription/notice payloads) are stored as EDN strings."
  {:lp/id             {:db/unique :db.unique/identity}
   :ledger/seq        {:db/unique :db.unique/identity}
   :subscription-history/seq {:db/unique :db.unique/identity}
   :notice-history/seq {:db/unique :db.unique/identity}
   :distribution-history/seq {:db/unique :db.unique/identity}
   :nav-disclosure-history/seq {:db/unique :db.unique/identity}
   :subscription-sequence/jurisdiction {:db/unique :db.unique/identity}
   :notice-sequence/jurisdiction {:db/unique :db.unique/identity}
   :distribution-sequence/jurisdiction {:db/unique :db.unique/identity}
   :nav-disclosure-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- lp->tx [{:keys [id commitment-amount called-amount currency jurisdiction accredited?]}]
  (cond-> {:lp/id id}
    commitment-amount (assoc :lp/commitment-amount commitment-amount)
    (some? called-amount) (assoc :lp/called-amount called-amount)
    currency (assoc :lp/currency currency)
    jurisdiction (assoc :lp/jurisdiction jurisdiction)
    (some? accredited?) (assoc :lp/accredited? accredited?)))

(def ^:private lp-pull
  [:lp/id :lp/commitment-amount :lp/called-amount :lp/currency :lp/jurisdiction :lp/accredited?])

(defn- pull->lp [m]
  (when (:lp/id m)
    {:id (:lp/id m) :commitment-amount (:lp/commitment-amount m)
     :called-amount (or (:lp/called-amount m) 0)
     :currency (:lp/currency m) :jurisdiction (:lp/jurisdiction m)
     :accredited? (boolean (:lp/accredited? m))}))

(defrecord DatomicStore [conn]
  Store
  (lp [_ id] (pull->lp (d/pull (d/db conn) lp-pull [:lp/id id])))
  (all-lps [_]
    (->> (d/q '[:find [?id ...] :where [?e :lp/id ?id]] (d/db conn))
         (map #(pull->lp (d/pull (d/db conn) lp-pull [:lp/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (subscription-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :subscription-history/seq ?s] [?e :subscription-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (notice-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :notice-history/seq ?s] [?e :notice-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (distribution-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :distribution-history/seq ?s] [?e :distribution-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (nav-disclosure-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :nav-disclosure-history/seq ?s] [?e :nav-disclosure-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (subscription-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :subscription-sequence/jurisdiction ?j] [?e :subscription-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (notice-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :notice-sequence/jurisdiction ?j] [?e :notice-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (distribution-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :distribution-sequence/jurisdiction ?j] [?e :distribution-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (nav-disclosure-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :nav-disclosure-sequence/jurisdiction ?j] [?e :nav-disclosure-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (commitment-already-distributed? [s upstream-commitment-number]
    (boolean (some #(= upstream-commitment-number (get % "upstream_commitment_number")) (distribution-history s))))
  (commit-record! [s {:keys [effect payload]}]
    (case effect
      :subscription/recorded
      (let [{:keys [result]} (record-subscription! s payload)
            lp-id (:lp-id payload)
            next-n (inc (subscription-sequence s (:jurisdiction payload)))]
        (d/transact! conn
                     [{:subscription-sequence/jurisdiction (:jurisdiction payload) :subscription-sequence/next next-n}
                      (lp->tx {:id lp-id :commitment-amount (double (:commitment-amount payload))
                              :called-amount 0.0 :currency (:currency payload)
                              :jurisdiction (:jurisdiction payload) :accredited? (boolean (:accredited? payload))})
                      {:subscription-history/seq (count (subscription-history s))
                       :subscription-history/record (ls/enc (get result "record"))}])
        result)

      :capital-call/notice-issued
      (let [{:keys [result allocations]} (issue-capital-call-notice! s payload)
            next-n (inc (notice-sequence s (:jurisdiction payload)))]
        (d/transact! conn
                     (into [{:notice-sequence/jurisdiction (:jurisdiction payload) :notice-sequence/next next-n}
                            {:notice-history/seq (count (notice-history s))
                             :notice-history/record (ls/enc (get result "record"))}]
                           (map (fn [{:keys [lp-id new-called-amount]}]
                                  {:lp/id lp-id :lp/called-amount new-called-amount}))
                           allocations))
        result)

      :distribution/recorded
      (let [{:keys [result]} (record-distribution! s payload)
            next-n (inc (distribution-sequence s (:jurisdiction payload)))]
        (d/transact! conn
                     [{:distribution-sequence/jurisdiction (:jurisdiction payload) :distribution-sequence/next next-n}
                      {:distribution-history/seq (count (distribution-history s))
                       :distribution-history/record (ls/enc (get result "record"))}])
        result)

      :nav/disclosed
      (let [{:keys [result]} (disclose-nav! s payload)
            next-n (inc (nav-disclosure-sequence s (:jurisdiction payload)))]
        (d/transact! conn
                     [{:nav-disclosure-sequence/jurisdiction (:jurisdiction payload) :nav-disclosure-sequence/next next-n}
                      {:nav-disclosure-history/seq (count (nav-disclosure-history s))
                       :nav-disclosure-history/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-lps [s lps]
    (when (seq lps) (d/transact! conn (mapv lp->tx (vals lps)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:lps ..});
  empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [lps]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-lps s lps))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo LP-subscription set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
