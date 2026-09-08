(ns trustfund.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6/7): this repo previously had NO demo page
  and no generator at all. This namespace drives the REAL actor stack
  (`trustfund.operation` -> `trustfund.governor` -> `trustfund.store`)
  through a scenario adapted from this repo's own `trustfund.sim` demo
  driver (`clojure -M:dev:run`, confirmed BEFORE writing this file to
  produce a sensible ledger against the real seeded LP ids `lp-1` /
  `lp-2` and the documented upstream-fact fixtures -- every disposition
  it produces matches the TrustFundGovernor's own documented rules
  exactly), trimmed to a representative subset (clean subscription
  intake auto-commit, one full capital-call notice + distribution
  record + NAV disclosure lifecycle each human-approved, and six
  distinct HARD-hold reasons on the same store) and rendered
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verify
  by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [trustfund.store :as store]
            [trustfund.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :fund-officer :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- upstream-call-draft
  "Literal upstream `vcfund.registry/register-capital-call` shape -- same
  fixture helper as `trustfund.sim` (no code dependency on 6499)."
  [call-number call-amount allocations notice-date]
  {"record" {"record_id" call-number
             "kind" "capital-call-draft"
             "call_amount" call-amount
             "allocations" allocations
             "notice_date" notice-date
             "funding_due_days" 10
             "immutable" true}
   "call_number" call-number
   "certificate" {"@context" ["https://www.w3.org/ns/credentials/v2"]
                  "type" ["VerifiableCredential" "CapitalCallCertificate"]
                  "credentialSubject" {"id" call-number "record" call-number}
                  "proof" nil "issued_by_registry" false "status" "draft-unsigned"}})

(defn- upstream-distribution-fact
  "Literal upstream `vcfund.registry/register-distribution` shape -- same
  mixed-key waterfall fixture as `trustfund.sim`."
  [commitment-number waterfall effective-date]
  (let [record-id (str commitment-number "#exit@" effective-date)]
    {"record" {"record_id" record-id
               "kind" "distribution-draft"
               "commitment_number" commitment-number
               "waterfall" waterfall
               "effective_date" effective-date
               "immutable" true}
     "certificate" {"@context" ["https://www.w3.org/ns/credentials/v2"]
                    "type" ["VerifiableCredential" "ExitDistributionCertificate"]
                    "credentialSubject" {"id" commitment-number "record" record-id}
                    "proof" nil "issued_by_registry" false "status" "draft-unsigned"}}))

(defn- upstream-nav-report [nav]
  {:nav nav :net-cash 0.0 :held-fair-value nav})

(defn- upstream-lp-capital-account
  [lp-id commitment-amount called-amount ownership-pct]
  {:lp-id lp-id :commitment-amount commitment-amount :called-amount called-amount
   :unfunded (- commitment-amount called-amount) :ownership-pct ownership-pct
   :distributed-to-date 0.0 :nav-share (* ownership-pct 8000000.0)})

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach on a single store: lp-1/lp-2 subscription intake
  (phase-3 auto-commit, no capital risk); a clean capital-call NOTICE
  (ALWAYS escalates -- `:actuation/issue-notice` is permanently
  high-stakes) approved by a human fund officer; an unaccredited
  subscriber HARD-hold (`:unaccredited-subscriber`); a capital-call
  notice referencing lp-9 with no subscription on file (HARD
  `:subscription-missing` + `:allocation-mismatch`); a capital-call
  notice whose upstream allocations do not match this vehicle's
  independent pro-rata recomputation (HARD `:allocation-mismatch`); a
  clean distribution record (ALWAYS escalates --
  `:actuation/record-distribution`) approved; a double-recording of the
  same upstream commitment (HARD `:distribution-already-recorded`); a
  clean NAV disclosure (ALWAYS escalates -- `:actuation/disclose-nav`)
  approved; a NAV disclosure whose called-amount does not match this
  vehicle's own ledger (HARD `:called-amount-mismatch`); and a NAV
  disclosure referencing lp-9 with no subscription (HARD
  `:nav-disclosure-subscription-missing`). Every HARD hold never reaches
  a human. Returns the resulting store -- every field read by `render`
  below is real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1" {:op :subscription/record :subject "lp-1"
                       :lp-id "lp-1" :commitment-amount 5000000
                       :currency "USD" :jurisdiction "USA" :accredited? true})

    (exec! actor "t2" {:op :subscription/record :subject "lp-2"
                       :lp-id "lp-2" :commitment-amount 1000000
                       :currency "JPY" :jurisdiction "JPN" :accredited? true})

    (let [draft (upstream-call-draft "USA-CALL-000000" 2000000.0
                                     [{"lp_id" "lp-1" "allocation" (/ (* 2000000.0 5000000.0) 6000000.0)
                                       "new_called_amount" (/ (* 2000000.0 5000000.0) 6000000.0)}
                                      {"lp_id" "lp-2" "allocation" (/ (* 2000000.0 1000000.0) 6000000.0)
                                       "new_called_amount" (/ (* 2000000.0 1000000.0) 6000000.0)}]
                                     "2026-07-06")]
      (exec! actor "t3" {:op :capital-call/issue-notice :subject "fund"
                         :upstream-call-draft draft
                         :jurisdiction "USA" :notice-date "2026-07-06"})
      (approve! actor "t3"))

    (exec! actor "t4" {:op :subscription/record :subject "party-3"
                       :lp-id "party-3" :commitment-amount 250000
                       :currency "USD" :jurisdiction "USA" :accredited? false})

    (let [draft (upstream-call-draft "USA-CALL-000001" 500000.0
                                     [{"lp_id" "lp-9" "allocation" 500000.0 "new_called_amount" 500000.0}]
                                     "2026-07-06")]
      (exec! actor "t5" {:op :capital-call/issue-notice :subject "fund"
                         :upstream-call-draft draft
                         :jurisdiction "USA" :notice-date "2026-07-06"}))

    (let [draft (upstream-call-draft "USA-CALL-000002" 100000.0
                                     [{"lp_id" "lp-1" "allocation" 999999.0 "new_called_amount" 999999.0}
                                      {"lp_id" "lp-2" "allocation" 16666.67 "new_called_amount" 16666.67}]
                                     "2026-07-06")]
      (exec! actor "t6" {:op :capital-call/issue-notice :subject "fund"
                         :upstream-call-draft draft
                         :jurisdiction "USA" :notice-date "2026-07-06"}))

    (let [fact (upstream-distribution-fact "USA-00000000"
                                           {:model :deal-by-deal-simple-preferred
                                            :return-of-capital 2000000.0 :preferred-return-due 480000.0
                                            :preferred-return-paid 480000.0 :gp-carry 1904000.0
                                            :lp-residual-profit 7616000.0 :total-to-lp 10096000.0
                                            :total-to-gp 1904000.0}
                                           "3y")]
      (exec! actor "t7" {:op :distribution/record :subject "fund"
                         :upstream-distribution-fact fact
                         :jurisdiction "USA" :effective-date "2026-07-06"})
      (approve! actor "t7"))

    (let [fact (upstream-distribution-fact "USA-00000000"
                                           {:model :deal-by-deal-simple-preferred
                                            :return-of-capital 2000000.0 :preferred-return-due 480000.0
                                            :preferred-return-paid 480000.0 :gp-carry 1904000.0
                                            :lp-residual-profit 7616000.0 :total-to-lp 10096000.0
                                            :total-to-gp 1904000.0}
                                           "3y")]
      (exec! actor "t9" {:op :distribution/record :subject "fund"
                         :upstream-distribution-fact fact
                         :jurisdiction "USA" :effective-date "2026-07-06"}))

    (let [nav-report (upstream-nav-report 8000000.0)
          lp-accounts [(upstream-lp-capital-account "lp-1" 5000000.0 (/ (* 2000000.0 5000000.0) 6000000.0) (/ 5000000.0 6000000.0))
                       (upstream-lp-capital-account "lp-2" 1000000.0 (/ (* 2000000.0 1000000.0) 6000000.0) (/ 1000000.0 6000000.0))]]
      (exec! actor "t13" {:op :nav/disclose :subject "fund"
                          :upstream-nav-report nav-report
                          :upstream-lp-capital-accounts lp-accounts
                          :jurisdiction "USA" :as-of-date "2026-07-06"})
      (approve! actor "t13"))

    (let [nav-report (upstream-nav-report 8000000.0)
          lp-accounts [(upstream-lp-capital-account "lp-1" 5000000.0 4999999.0 (/ 5000000.0 6000000.0))
                       (upstream-lp-capital-account "lp-2" 1000000.0 (/ (* 2000000.0 1000000.0) 6000000.0) (/ 1000000.0 6000000.0))]]
      (exec! actor "t14" {:op :nav/disclose :subject "fund"
                          :upstream-nav-report nav-report
                          :upstream-lp-capital-accounts lp-accounts
                          :jurisdiction "USA" :as-of-date "2026-07-06"}))

    (let [nav-report (upstream-nav-report 8000000.0)
          lp-accounts [(upstream-lp-capital-account "lp-9" 500000.0 0.0 0.1)]]
      (exec! actor "t15" {:op :nav/disclose :subject "fund"
                          :upstream-nav-report nav-report
                          :upstream-lp-capital-accounts lp-accounts
                          :jurisdiction "USA" :as-of-date "2026-07-06"}))
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- lp-row [ledger {:keys [id commitment-amount called-amount currency jurisdiction accredited?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc commitment-amount) (esc called-amount) (esc currency) (esc jurisdiction)
          (if accredited? "<span class=\"ok\">accredited</span>" "<span class=\"critical\">unaccredited</span>")
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README `Ops`/`Actuation`, `trustfund.governor`/`trustfund.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:subscription/record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk &middot; HARD on unaccredited subscriber</span></td></tr>"
   "        <tr><td><code>:capital-call/issue-notice</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; independent pro-rata recompute vs upstream draft</span></td></tr>"
   "        <tr><td><code>:distribution/record</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; no-subscriptions &amp; double-record HARD holds</span></td></tr>"
   "        <tr><td><code>:nav/disclose</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; called-amount independently verified against this vehicle's ledger</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        lps (store/all-lps db)
        lp-rows (str/join "\n" (map (partial lp-row ledger) lps))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-6430 &middot; trusts-funds</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Trusts, funds and similar financial entities (ISIC 6430) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · capital-call notice / distribution / NAV disclosure always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>LP subscriptions</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>trustfund.store</code> via <code>trustfund.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>LP</th><th>Commitment</th><th>Called</th><th>Currency</th><th>Jurisdiction</th><th>Accreditation</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     lp-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (TrustFundGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Capital-call allocations are independently recomputed against this vehicle's own subscription ledger (never trusted from the upstream investment-actor draft); distribution double-records and NAV called-amount mismatches are blocked outright.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        parent (.getParentFile (java.io.File. out))
        _ (when parent (.mkdirs parent))
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/subscription-history db)) "subscriptions,"
             (count (store/notice-history db)) "capital-call notices,"
             (count (store/distribution-history db)) "distributions,"
             (count (store/nav-disclosure-history db)) "nav disclosures )")))