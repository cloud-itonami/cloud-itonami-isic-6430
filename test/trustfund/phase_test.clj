(ns trustfund.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:capital-call/issue-notice`, `:distribution/record` and
  `:nav/disclose` may never be members of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [trustfund.phase :as phase]))

(deftest capital-call-issue-notice-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-issues a capital-call notice to LPs"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :capital-call/issue-notice))
          (str "phase " n " must not auto-commit :capital-call/issue-notice")))))

(deftest distribution-record-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-records an LP distribution"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :distribution/record))
          (str "phase " n " must not auto-commit :distribution/record")))))

(deftest nav-disclose-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-discloses NAV"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :nav/disclose))
          (str "phase " n " must not auto-commit :nav/disclose")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-the-no-capital-risk-op
  (testing ":subscription/record moves no capital -- auto-eligible"
    (is (= #{:subscription/record} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :subscription/record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :capital-call/issue-notice} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :distribution/record} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :nav/disclose} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :subscription/record} :commit)))))
