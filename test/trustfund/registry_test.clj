(ns trustfund.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [trustfund.registry :as r]))

(defn- close?
  "Float-safe equality -- never assert exact `=` on a value derived from
  dividing/multiplying by a pro-rata share."
  [a b]
  (< (Math/abs (- (double a) (double b))) 1e-6))

(deftest subscription-is-a-draft-not-a-real-execution
  (let [result (r/register-subscription "lp-1" 0 "USD" "USA" true 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest subscription-assigns-subscription-number
  (let [result (r/register-subscription "lp-1" 5000000 "USD" "USA" true 7)]
    (is (= (get result "subscription_number") "USA-SUB-00000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "subscription-draft"))
    (is (= (get-in result ["record" "accredited"]) true))))

(deftest subscription-validation-rules
  (is (thrown? Exception (r/register-subscription "" 0 "USD" "USA" true 1)))
  (is (thrown? Exception (r/register-subscription "lp-1" -1 "USD" "USA" true 1)))
  (is (thrown? Exception (r/register-subscription "lp-1" 0 "" "USA" true 1)))
  (is (thrown? Exception (r/register-subscription "lp-1" 0 "USD" "" true 1)))
  (is (thrown? Exception (r/register-subscription "lp-1" 0 "USD" "USA" true -1))))

;; ----------------------------- capital-call-allocations -----------------------------

(def subscriptions-fixture
  [{:id "lp-1" :commitment-amount 5000000 :called-amount 0}
   {:id "lp-2" :commitment-amount 1000000 :called-amount 0}])

(deftest capital-call-allocations-split-pro-rata-by-commitment-share
  (let [allocs (r/capital-call-allocations subscriptions-fixture 2000000)
        by-id (into {} (map (juxt :lp-id identity)) allocs)]
    (is (close? (/ 10000000.0 6) (:allocation (get by-id "lp-1"))))
    (is (close? (/ 2000000.0 6) (:allocation (get by-id "lp-2"))))
    (is (not (:overcall? (get by-id "lp-1"))))
    (is (not (:overcall? (get by-id "lp-2"))))))

(deftest capital-call-allocations-flag-overcall
  (testing "a call far exceeding total subscribed commitments overcalls every LP"
    (let [allocs (r/capital-call-allocations subscriptions-fixture 20000000)]
      (is (every? :overcall? allocs)))))

(deftest capital-call-allocations-validation-rules
  (is (thrown? Exception (r/capital-call-allocations subscriptions-fixture -1)))
  (is (thrown? Exception (r/capital-call-allocations [] 1000)))
  (is (thrown? Exception (r/capital-call-allocations [{:id "lp-1" :commitment-amount 0}] 1000))))

;; ----------------------------- capital-call-notice -----------------------------

(deftest capital-call-notice-is-a-draft-not-a-real-notice
  (let [allocs (r/capital-call-allocations subscriptions-fixture 2000000)
        result (r/register-capital-call-notice "USA-CALL-000000" allocs 2000000 "USA" 0 "2026-07-06")]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest capital-call-notice-assigns-notice-number-and-references-upstream
  (let [allocs (r/capital-call-allocations subscriptions-fixture 2000000)
        result (r/register-capital-call-notice "USA-CALL-000000" allocs 2000000 "USA" 7 "2026-07-06")]
    (is (= (get result "notice_number") "USA-NOTICE-000007"))
    (is (= (get-in result ["record" "upstream_call_number"]) "USA-CALL-000000"))
    (is (= (get-in result ["record" "kind"]) "capital-call-notice-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest capital-call-notice-validation-rules
  (let [allocs (r/capital-call-allocations subscriptions-fixture 2000000)]
    (is (thrown? Exception (r/register-capital-call-notice "" allocs 2000000 "USA" 0 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call-notice "USA-CALL-000000" [] 2000000 "USA" 0 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call-notice "USA-CALL-000000" allocs -1 "USA" 0 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call-notice "USA-CALL-000000" allocs 2000000 "" 0 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call-notice "USA-CALL-000000" allocs 2000000 "USA" -1 "2026-07-06")))
    (is (thrown? Exception (r/register-capital-call-notice "USA-CALL-000000" allocs 2000000 "USA" 0 nil)))))

(deftest notice-history-is-append-only
  (let [allocs (r/capital-call-allocations subscriptions-fixture 1000000)
        n1 (r/register-capital-call-notice "USA-CALL-000000" allocs 1000000 "USA" 0 "2026-07-06")
        hist (r/append [] n1)
        n2 (r/register-capital-call-notice "USA-CALL-000001" allocs 500000 "USA" 1 "2026-08-06")
        hist2 (r/append hist n2)]
    (is (= 2 (count hist2)))
    (is (= "USA-NOTICE-000000" (get-in hist2 [0 "record_id"])))
    (is (= "USA-NOTICE-000001" (get-in hist2 [1 "record_id"])))))
