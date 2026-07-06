(ns trustfund.store-contract-test
  "The Store contract, run against BOTH backends -- proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract, the
  same pattern `cloud-itonami-isic-6499`'s `vcfund.store-contract-test`
  uses."
  (:require [clojure.test :refer [deftest is testing]]
            [trustfund.store :as store]))

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
      (is (zero? (store/subscription-sequence s "USA")))
      (is (zero? (store/notice-sequence s "USA"))))))

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
