(ns trustfund.kernels.gate-test
  "The safety kernel's executable spec, three ways:

  1. battery lock — the kernel's own in-subset battery must pass
     case-for-case (`battery-case-count` == `(battery-pass-count)`),
     so a silently dropped case can't survive review.
  2. parity matrix — the kernel's phase core is compared against an
     independent reference copy of the ORIGINAL set-based cond logic
     over the FULL input space (all phases incl. out-of-range, all op
     codes incl. the reserved read code and unknown, all governor
     dispositions). The façade delegates, so this is the guard that
     delegation didn't change semantics.
  3. governor boundary — the confidence floor boundary, the
     fail-closed treatment of out-of-range confidence, and the
     amount-tolerance parity against the old |a − b| < 1e-6 predicate,
     exercised through the real `trustfund.governor/check` façade and
     representative values."
  (:require [clojure.test :refer [deftest is testing]]
            [trustfund.governor :as governor]
            [trustfund.kernels.gate :as gate]))

(deftest battery-lock
  (is (= gate/battery-case-count (gate/battery-pass-count))
      "every battery case must pass; update battery-case-count only when adding cases"))

(deftest confidence-floor-pinned-to-facade-constant
  (is (= gate/confidence-floor-x100
         (Math/round (* 100.0 governor/confidence-floor)))
      "the façade's documented 0.6 and the kernel's deciding 60 must not drift"))

(deftest allocation-tolerance-pinned-to-facade-constant
  (is (= gate/allocation-tolerance-x1e6
         (Math/round (* 1000000.0 governor/allocation-tolerance)))
      "the façade's documented 1e-6 and the kernel's deciding 1 micro-unit must not drift")
  (testing "kernel x1e6 comparison == the old |a - b| < 1e-6 predicate over representative values"
    (doseq [[a b] [[5000000.0 5000000.0]              ; exact match
                   [5000000.0 5000001.0]              ; whole-unit mismatch
                   [333333.33333333326 333333.3333333333] ; double-arithmetic noise
                   [0.0 0.0]
                   [0.25 0.75]]]                       ; sub-unit but way over tolerance
      (is (= (< (Math/abs (- (double a) (double b))) 1e-6)
             (= 0 (gate/amount-mismatch (Math/round (* 1000000.0 (double a)))
                                        (Math/round (* 1000000.0 (double b))))))
          (str "tolerance divergence at a=" a " b=" b)))))

;; ---------------------------------------------------------------
;; Independent oracle for the parity matrix: the pre-kernel phase
;; logic (sets + cond) restated over wire codes, PLUS the kernel's
;; fail-closed contract for out-of-range phases (no writes at all).
;; The original façade normalized an unknown phase to default-phase 3
;; BEFORE this logic and still does — so out-of-range rows here pin
;; the kernel's own contract, not a façade behavior change. This
;; domain's read-ops set is EMPTY (there are no read ops), so the
;; read pass-through branch is restated as vacuously dead — op 0 is
;; reserved and must fail closed like an unknown write. Phase 2 is
;; the SAME row as phase 1 (reserved for a future write category).

(def ^:private ref-read-ops #{})
(def ^:private ref-phases
  {0 {:writes #{}          :auto #{}}
   1 {:writes #{1}         :auto #{}}
   2 {:writes #{1}         :auto #{}}
   3 {:writes #{1 2 3 4}   :auto #{1}}})

(defn- ref-gate [phase op gov]
  (let [{:keys [writes auto]} (get ref-phases phase {:writes #{} :auto #{}})]
    (cond
      (= gov 2)                        {:d 2 :r 0}
      (contains? ref-read-ops op)      {:d gov :r 0}
      (not (contains? writes op))      {:d 2 :r 1}
      (and (= gov 0)
           (not (contains? auto op)))  {:d 1 :r 2}
      :else                            {:d gov :r 0})))

(deftest phase-parity-matrix
  (testing "kernel == reference over the full input space (162 combos)"
    (doseq [phase [-1 0 1 2 3 4 7 100 -99]
            op    [0 1 2 3 4 5]
            gov   [0 1 2]]
      (let [expected (ref-gate phase op gov)]
        (is (= (:d expected) (gate/phase-disposition phase op gov))
            (str "disposition mismatch at phase=" phase " op=" op " gov=" gov))
        (is (= (:r expected) (gate/phase-reason phase op gov))
            (str "reason mismatch at phase=" phase " op=" op " gov=" gov))))))

(deftest actuation-ops-auto-enabled-nowhere
  (testing "ops 2 (:capital-call/issue-notice), 3 (:distribution/record)
            and 4 (:nav/disclose) are auto-enabled at NO phase — the
            kernel restates the phase table's permanent structural
            invariant"
    (doseq [phase [-1 0 1 2 3 4 7]
            op    [2 3 4]]
      (is (= 0 (gate/op-auto-enabled phase op))
          (str "op " op " must not be auto-enabled at phase " phase)))))

;; ---------------------------------------------------------------
;; Governor boundary through the real façade. op :subscription/record
;; touches no store-backed check (only the accreditation flag on the
;; proposal itself), so with :accredited? true the verdict is decided
;; purely by confidence/actuation — nil store is safe.

(defn- verdict [proposal]
  (governor/check {:op :subscription/record :subject "lp-x"} {}
                  (assoc proposal :value {:accredited? true}) nil))

(deftest confidence-floor-boundary
  (testing "0.59 escalates, 0.60 clears (kernel decides at integer x100)"
    (is (true?  (:escalate? (verdict {:confidence 0.59}))))
    (is (false? (:ok? (verdict {:confidence 0.59}))))
    (is (true?  (:ok? (verdict {:confidence 0.6}))))
    (is (false? (:escalate? (verdict {:confidence 0.6}))))))

(deftest out-of-range-confidence-fails-closed
  (testing "an advisor reporting impossible confidence gets MORE scrutiny,
            not auto-commit (kernel is deliberately stricter than the old
            inline `(< conf floor)` here)"
    (is (true? (:escalate? (verdict {:confidence 1.5}))))
    (is (false? (:ok? (verdict {:confidence 1.5}))))
    (is (true? (:escalate? (verdict {:confidence -0.2}))))))

(deftest actuation-still-escalates-and-hard-still-wins
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/issue-notice}))))
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/record-distribution}))))
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/disclose-nav}))))
  (testing "a hard violation dominates actuation escalation"
    (let [v (governor/check {:op :subscription/record :subject "lp-x"} {}
                            {:confidence 0.99 :stake :actuation/issue-notice
                             :value {:accredited? false}} nil)]
      (is (true? (:hard? v)))
      (is (false? (:escalate? v)))
      (is (some #{:unaccredited-subscriber} (mapv :rule (:violations v)))))))
