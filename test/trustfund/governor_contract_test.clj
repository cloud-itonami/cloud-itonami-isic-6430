(ns trustfund.governor-contract-test
  "The governor contract as executable tests -- the trust/fund-vehicle
  analog of `cloud-itonami-isic-6499`'s `vcfund.governor-contract-test`.
  The single invariant under test:

    TrustAdmin-LLM never issues a capital-call notice the TrustFund-
    Governor would reject, `:capital-call/issue-notice` NEVER auto-
    commits at any phase, `:subscription/record` (no capital risk) MAY
    auto-commit when clean, and every decision (commit OR hold) leaves
    exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [trustfund.store :as store]
            [trustfund.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :fund-officer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- upstream-call-draft [call-number call-amount allocations notice-date]
  {"record" {"record_id" call-number
             "kind" "capital-call-draft"
             "call_amount" call-amount
             "allocations" allocations
             "notice_date" notice-date
             "funding_due_days" 10
             "immutable" true}
   "call_number" call-number
   "certificate" {"proof" nil "issued_by_registry" false "status" "draft-unsigned"}})

(defn- clean-upstream-draft
  "An upstream call draft whose allocations are computed by the SAME
  pro-rata math the seeded fixture (lp-1 5,000,000 / lp-2 1,000,000)
  independently re-derives -- i.e. a draft that SHOULD pass governor
  re-verification cleanly."
  [call-amount]
  (upstream-call-draft "USA-CALL-000000" call-amount
                       [{"lp_id" "lp-1" "allocation" (/ (* call-amount 5000000.0) 6000000.0)
                         "new_called_amount" (/ (* call-amount 5000000.0) 6000000.0)}
                        {"lp_id" "lp-2" "allocation" (/ (* call-amount 1000000.0) 6000000.0)
                         "new_called_amount" (/ (* call-amount 1000000.0) 6000000.0)}]
                       "2026-07-06"))

(deftest clean-subscription-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1" {:op :subscription/record :subject "lp-3"
                                :lp-id "lp-3" :commitment-amount 250000
                                :currency "USD" :jurisdiction "USA" :accredited? true} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 250000.0 (:commitment-amount (store/lp db "lp-3"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest unaccredited-subscription-is-held-and-unoverridable
  (testing "an unaccredited subscriber -> HOLD, settles immediately, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :subscription/record :subject "party-3"
                                   :lp-id "party-3" :commitment-amount 250000
                                   :currency "USD" :jurisdiction "USA" :accredited? false} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:unaccredited-subscriber} (-> (store/ledger db) first :basis)))
      (is (nil? (store/lp db "party-3")) "no subscription written"))))

(deftest capital-call-notice-referencing-an-unsubscribed-lp-is-held
  (testing "an upstream draft allocating to an LP with no subscription on file -> HARD hold"
    (let [[db actor] (fresh)
          draft (upstream-call-draft "USA-CALL-000001" 500000.0
                                     [{"lp_id" "lp-9" "allocation" 500000.0 "new_called_amount" 500000.0}]
                                     "2026-07-06")
          res (exec-op actor "t3" {:op :capital-call/issue-notice :subject "fund"
                                   :upstream-call-draft draft
                                   :jurisdiction "USA" :notice-date "2026-07-06"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:subscription-missing} (-> (store/ledger db) first :basis)))
      (is (empty? (store/notice-history db))))))

(deftest capital-call-notice-with-tampered-allocation-is-held
  (testing "an upstream draft whose claimed allocation does not match this vehicle's own independent pro-rata recomputation -> HARD hold"
    (let [[db actor] (fresh)
          draft (upstream-call-draft "USA-CALL-000002" 100000.0
                                     [{"lp_id" "lp-1" "allocation" 999999.0 "new_called_amount" 999999.0}
                                      {"lp_id" "lp-2" "allocation" 16666.67 "new_called_amount" 16666.67}]
                                     "2026-07-06")
          res (exec-op actor "t4" {:op :capital-call/issue-notice :subject "fund"
                                   :upstream-call-draft draft
                                   :jurisdiction "USA" :notice-date "2026-07-06"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:allocation-mismatch} (-> (store/ledger db) first :basis)))
      (is (empty? (store/notice-history db))))))

(deftest capital-call-notice-overcalling-an-lp-is-held
  (testing "an upstream draft whose call-amount would overcall an LP (recomputed independently) -> HARD hold via allocation-mismatch"
    (let [[db actor] (fresh)
          draft (clean-upstream-draft 50000000.0)
          res (exec-op actor "t5" {:op :capital-call/issue-notice :subject "fund"
                                   :upstream-call-draft draft
                                   :jurisdiction "USA" :notice-date "2026-07-06"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:allocation-mismatch} (-> (store/ledger db) first :basis))
          "the recomputed allocation is flagged :overcall?, which always counts as a mismatch"))))

(deftest capital-call-notice-issuance-always-escalates-then-human-decides
  (testing "a clean, correctly-allocated notice still ALWAYS interrupts for human approval -- actuation/issue-notice is never auto"
    (let [[db actor] (fresh)
          draft (clean-upstream-draft 2000000.0)
          r1 (exec-op actor "t6" {:op :capital-call/issue-notice :subject "fund"
                                  :upstream-call-draft draft
                                  :jurisdiction "USA" :notice-date "2026-07-06"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, notice record drafted and LP called-amounts advance"
        (let [r2 (approve! actor "t6")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/notice-history db))) "one draft notice record")
          (is (pos? (:called-amount (store/lp db "lp-1"))))
          (is (pos? (:called-amount (store/lp db "lp-2"))))))))
  (testing "reject -> hold, nothing called"
    (let [[db actor] (fresh)
          draft (clean-upstream-draft 2000000.0)
          _ (exec-op actor "t7" {:op :capital-call/issue-notice :subject "fund"
                                 :upstream-call-draft draft
                                 :jurisdiction "USA" :notice-date "2026-07-06"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/notice-history db)) "nothing recorded on reject")
      (is (zero? (:called-amount (store/lp db "lp-1")))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :subscription/record :subject "lp-3"
                          :lp-id "lp-3" :commitment-amount 250000
                          :currency "USD" :jurisdiction "USA" :accredited? true} operator)
      (exec-op actor "b" {:op :subscription/record :subject "party-3"
                          :lp-id "party-3" :commitment-amount 250000
                          :currency "USD" :jurisdiction "USA" :accredited? false} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
