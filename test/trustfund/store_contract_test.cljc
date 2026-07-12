(ns trustfund.store-contract-test
  "The Store contract, run against BOTH backends -- proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract, the
  same pattern `cloud-itonami-isic-6499`'s `vcfund.store-contract-test`
  uses."
  (:require [clojure.test :refer [deftest is testing]]
            [trustfund.store :as store]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (== 5000000 (:commitment-amount (store/lp s "lp-1"))))
      (is (zero? (:called-amount (store/lp s "lp-1"))))
      (is (true? (:accredited? (store/lp s "lp-1"))))
      (is (= ["lp-1" "lp-2"] (mapv :id (store/all-lps s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/subscription-history s)))
      (is (= [] (store/notice-history s)))
      (is (= [] (store/distribution-history s)))
      (is (= [] (store/nav-disclosure-history s)))
      (is (zero? (store/subscription-sequence s "USA")))
      (is (zero? (store/notice-sequence s "USA")))
      (is (zero? (store/distribution-sequence s "USA")))
      (is (zero? (store/nav-disclosure-sequence s "USA")))
      (is (false? (store/commitment-already-distributed? s "USA-00000000"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "subscription recording drafts a subscription record and seeds the LP directory"
        (store/commit-record! s {:effect :subscription/recorded
                                 :payload {:lp-id "lp-3" :commitment-amount 250000
                                          :currency "USD" :jurisdiction "USA" :accredited? true}})
        (is (= 1 (count (store/subscription-history s))))
        (is (= "USA-SUB-00000000" (get (first (store/subscription-history s)) "record_id")))
        (is (= 1 (store/subscription-sequence s "USA")))
        (is (= 250000.0 (:commitment-amount (store/lp s "lp-3"))))
        (is (zero? (:called-amount (store/lp s "lp-3")))))
      (testing "capital-call notice issuance recomputes allocations and advances called-amounts"
        (store/commit-record! s {:effect :capital-call/notice-issued
                                 :payload {:upstream-call-number "USA-CALL-000000"
                                          :call-amount 2000000 :jurisdiction "USA"
                                          :notice-date "2026-07-06"}})
        (is (= 1 (count (store/notice-history s))))
        (is (= "USA-NOTICE-000000" (get (first (store/notice-history s)) "record_id")))
        (is (= "USA-CALL-000000" (get (first (store/notice-history s)) "upstream_call_number")))
        (is (= 1 (store/notice-sequence s "USA")))
        (is (pos? (:called-amount (store/lp s "lp-1"))))
        (is (pos? (:called-amount (store/lp s "lp-2")))))
      (testing "distribution recording independently pro-rates across the subscription ledger and drafts the record"
        (store/commit-record! s {:effect :distribution/recorded
                                 :payload {:upstream-commitment-number "USA-00000000"
                                          :distribution-amount 10096000 :jurisdiction "USA"
                                          :effective-date "2026-07-06"}})
        (is (= 1 (count (store/distribution-history s))))
        (is (= "USA-DIST-000000" (get (first (store/distribution-history s)) "record_id")))
        (is (= "USA-00000000" (get (first (store/distribution-history s)) "upstream_commitment_number")))
        (is (= 1 (store/distribution-sequence s "USA")))
        (is (true? (store/commitment-already-distributed? s "USA-00000000")))
        (is (false? (store/commitment-already-distributed? s "USA-00000001")))
        (let [allocs (get (first (store/distribution-history s)) "allocations")
              by-id (into {} (map (juxt #(get % "lp_id") identity)) allocs)]
          ;; by this point in the sequential test, lp-3 (250,000) has ALSO
          ;; been subscribed above -- total subscribed commitment is
          ;; 5,000,000 + 1,000,000 + 250,000 = 6,250,000, not just lp-1/lp-2's 6,000,000
          (is (close? (/ (* 10096000.0 5000000.0) 6250000.0) (get-in by-id ["lp-1" "allocation"])))))
      (testing "nav-disclosure carries the upstream LP capital-account rows through into a draft record"
        (store/commit-record! s {:effect :nav/disclosed
                                 :payload {:nav 8000000.0 :as-of-date "2026-07-06" :jurisdiction "USA"
                                          :lp-accounts [{:lp-id "lp-1" :commitment-amount 5000000.0
                                                        :called-amount 1600000.0 :unfunded 3400000.0
                                                        :ownership-pct 0.8 :distributed-to-date 0.0
                                                        :nav-share 6400000.0}]}})
        (is (= 1 (count (store/nav-disclosure-history s))))
        (is (= "USA-NAV-000000" (get (first (store/nav-disclosure-history s)) "record_id")))
        (is (= 8000000.0 (get (first (store/nav-disclosure-history s)) "nav")))
        (is (= 1 (store/nav-disclosure-sequence s "USA")))
        (is (close? 1600000.0 (get-in (first (store/nav-disclosure-history s)) ["lp_accounts" 0 "called_amount"]))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/lp s "nope")))
    (is (= [] (store/all-lps s)))
    (is (= [] (store/ledger s)))
    (is (zero? (store/subscription-sequence s "USA")))
    (store/with-lps s {"x" {:id "x" :commitment-amount 100 :called-amount 0
                            :currency "USD" :jurisdiction "USA" :accredited? true}})
    (is (== 100 (:commitment-amount (store/lp s "x"))))))
