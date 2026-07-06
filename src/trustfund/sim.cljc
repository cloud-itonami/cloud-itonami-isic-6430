(ns trustfund.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean LP subscription
  through subscription intake (no capital risk; auto-commits) ->
  capital-call NOTICE issuance off an UPSTREAM `cloud-itonami-isic-6499`
  (`vcfund`) capital-call draft -> LP-distribution recording off an
  upstream exit-distribution fact (both always escalate -- real legal
  acts) -> human approval -> commit, then shows five HARD holds (an
  unaccredited subscriber, a capital-call notice referencing an LP with
  no subscription on file, a capital-call notice whose upstream
  allocations do not match this vehicle's own independent pro-rata
  recomputation, a distribution recorded with no subscriptions on file,
  and a double-recording of an already-recorded distribution) that never
  reach a human at all, and prints the audit ledger + the draft
  subscription/notice/distribution records.

  The `upstream-call-draft`/`upstream-distribution-fact` fixtures below
  are literal EDN, hand-shaped to EXACTLY match what `vcfund.registry/
  register-capital-call`/`register-distribution` (in the separate
  `cloud-itonami-isic-6499` repo) actually return -- this repo has NO
  code dependency on that one; the two interoperate only through this
  documented data contract (see `trustfund.governor`'s docstring). In
  production, this fact would arrive over whatever transport the two
  deployed actors actually use (a message queue, a signed webhook, a
  shared kotoba-server pod), not a literal in a demo file."
  (:require [langgraph.graph :as g]
            [trustfund.store :as store]
            [trustfund.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :fund-officer :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- upstream-call-draft
  "A literal upstream `vcfund.registry/register-capital-call` result --
  see ns docstring."
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
  "A literal upstream `vcfund.registry/register-distribution` result --
  `waterfall` is a KEYWORD-keyed map nested inside the otherwise
  STRING-keyed \"record\" (the exact mixed-key shape `vcfund.registry/
  distribute-waterfall`'s own return, embedded as-is, actually has) --
  see ns docstring."
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

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== subscription/record lp-1 ($5,000,000, accredited; no capital risk; auto-commits) ==")
    (println (exec! actor "t1" {:op :subscription/record :subject "lp-1"
                                :lp-id "lp-1" :commitment-amount 5000000
                                :currency "USD" :jurisdiction "USA" :accredited? true} operator))

    (println "== subscription/record lp-2 (¥1,000,000, accredited; no capital risk; auto-commits) ==")
    (println (exec! actor "t2" {:op :subscription/record :subject "lp-2"
                                :lp-id "lp-2" :commitment-amount 1000000
                                :currency "JPY" :jurisdiction "JPN" :accredited? true} operator))

    (println "== capital-call/issue-notice (upstream vcfund call for $2,000,000, pro-rata across lp-1/lp-2's TRUSTFUND subscriptions; always escalates -- actuation/issue-notice) ==")
    (let [draft (upstream-call-draft "USA-CALL-000000" 2000000.0
                                     [{"lp_id" "lp-1" "allocation" (/ (* 2000000.0 5000000.0) 6000000.0)
                                       "new_called_amount" (/ (* 2000000.0 5000000.0) 6000000.0)}
                                      {"lp_id" "lp-2" "allocation" (/ (* 2000000.0 1000000.0) 6000000.0)
                                       "new_called_amount" (/ (* 2000000.0 1000000.0) 6000000.0)}]
                                     "2026-07-06")
          r (exec! actor "t3" {:op :capital-call/issue-notice :subject "fund"
                               :upstream-call-draft draft
                               :jurisdiction "USA" :notice-date "2026-07-06"} operator)]
      (println r)
      (println "-- human trustee/fund officer approves --")
      (println (approve! actor "t3")))

    (println "== subscription/record party-3 (unaccredited -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t4" {:op :subscription/record :subject "party-3"
                                :lp-id "party-3" :commitment-amount 250000
                                :currency "USD" :jurisdiction "USA" :accredited? false} operator))

    (println "== capital-call/issue-notice referencing lp-9 (no subscription on file -> HARD hold, never reaches a human) ==")
    (let [draft (upstream-call-draft "USA-CALL-000001" 500000.0
                                     [{"lp_id" "lp-9" "allocation" 500000.0 "new_called_amount" 500000.0}]
                                     "2026-07-06")]
      (println (exec! actor "t5" {:op :capital-call/issue-notice :subject "fund"
                                  :upstream-call-draft draft
                                  :jurisdiction "USA" :notice-date "2026-07-06"} operator)))

    (println "== capital-call/issue-notice with a tampered/mismatched upstream allocation (claims lp-1 owes far more than independent pro-rata recomputation -> HARD hold) ==")
    (let [draft (upstream-call-draft "USA-CALL-000002" 100000.0
                                     [{"lp_id" "lp-1" "allocation" 999999.0 "new_called_amount" 999999.0}
                                      {"lp_id" "lp-2" "allocation" 16666.67 "new_called_amount" 16666.67}]
                                     "2026-07-06")]
      (println (exec! actor "t6" {:op :capital-call/issue-notice :subject "fund"
                                  :upstream-call-draft draft
                                  :jurisdiction "USA" :notice-date "2026-07-06"} operator)))

    (println "== distribution/record (upstream vcfund exit-distribution: total_to_lp=10,096,000 for USA-00000000, the same deal-by-deal waterfall example from cloud-itonami-isic-6499's own demo; pro-rated across lp-1/lp-2's TRUSTFUND subscriptions; always escalates -- actuation/record-distribution) ==")
    (let [fact (upstream-distribution-fact "USA-00000000"
                                           {:model :deal-by-deal-simple-preferred
                                            :return-of-capital 2000000.0 :preferred-return-due 480000.0
                                            :preferred-return-paid 480000.0 :gp-carry 1904000.0
                                            :lp-residual-profit 7616000.0 :total-to-lp 10096000.0
                                            :total-to-gp 1904000.0}
                                           "3y")
          r (exec! actor "t7" {:op :distribution/record :subject "fund"
                               :upstream-distribution-fact fact
                               :jurisdiction "USA" :effective-date "2026-07-06"} operator)]
      (println r)
      (println "-- human trustee/fund officer approves --")
      (println (approve! actor "t7")))

    (println "== distribution/record with NO subscriptions on file (blank store -> HARD hold, never reaches a human) ==")
    (let [blank-store (store/->MemStore (atom {:lps {} :ledger [] :subscription-sequences {}
                                               :notice-sequences {} :distribution-sequences {}
                                               :subscription-history [] :notice-history []
                                               :distribution-history []}))
          actor2 (op/build blank-store)
          fact (upstream-distribution-fact "USA-00000001"
                                           {:model :deal-by-deal-simple-preferred
                                            :return-of-capital 500000.0 :preferred-return-due 40000.0
                                            :preferred-return-paid 40000.0 :gp-carry 0.0
                                            :lp-residual-profit 0.0 :total-to-lp 540000.0
                                            :total-to-gp 0.0}
                                           "1y")]
      (println (exec! actor2 "t8" {:op :distribution/record :subject "fund"
                                   :upstream-distribution-fact fact
                                   :jurisdiction "USA" :effective-date "2026-07-06"} operator)))

    (println "== distribution/record USA-00000000 AGAIN (double-recording of an already-recorded distribution -> HARD hold) ==")
    (let [fact (upstream-distribution-fact "USA-00000000"
                                           {:model :deal-by-deal-simple-preferred
                                            :return-of-capital 2000000.0 :preferred-return-due 480000.0
                                            :preferred-return-paid 480000.0 :gp-carry 1904000.0
                                            :lp-residual-profit 7616000.0 :total-to-lp 10096000.0
                                            :total-to-gp 1904000.0}
                                           "3y")]
      (println (exec! actor "t9" {:op :distribution/record :subject "fund"
                                  :upstream-distribution-fact fact
                                  :jurisdiction "USA" :effective-date "2026-07-06"} operator)))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft subscription records ==")
    (doseq [r (store/subscription-history db)] (println r))

    (println "== draft capital-call-notice records ==")
    (doseq [r (store/notice-history db)] (println r))

    (println "== draft distribution-notice records ==")
    (doseq [r (store/distribution-history db)] (println r))))
