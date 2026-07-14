(ns wasm.capital-call-allocation-test
  "Hosts wasm/capital_call_allocation.wasm (compiled from
  wasm/capital_call_allocation.kotoba, see wasm/README.md) via
  kototama.tender -- proves the pro-rata capital-call allocation +
  overcall check `trustfund.governor/allocation-mismatch-violations`
  applies (via `trustfund.registry/capital-call-allocations`'s
  `:overcall?` field, see registry.cljc lines 69-103 and governor.cljc's
  'including one caused by an overcall on the recomputed side') runs as a
  real WASM guest, not just as JVM Clojure -- for a SINGLE LP's scalar
  extraction from that collection-oriented registry function (maps/
  collections are out of the `.kotoba` wasm-compilable subset).

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the four real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/capital_call_allocation.kotoba's ns-adjacent header comment for
  the offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/capital_call_allocation.wasm"))))

(defn- run-capital-call-not-overcalled?
  [commitment-amount total-committed call-amount called-amount]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 commitment-amount)
    (.writeI32 memory 4 total-committed)
    (.writeI32 memory 8 call-amount)
    (.writeI32 memory 12 called-amount)
    (tender/call-main instance)))

(deftest capital-call-wasm-approves-nontrivial-prorata
  (testing "commitment=25000, total-committed=100000 (this LP holds a 25% share), call-amount=40000, called-amount=0 -> allocation=10000, new-called=10000 <= commitment -> approves"
    ;; hand-verified: share = 25000 / 100000 = 0.25; allocation = 0.25 *
    ;; 40000 = 10000; fixed-point (integer cross-multiply, no float
    ;; division): quot(25000 * 40000, 100000) = quot(1_000_000_000,
    ;; 100000) = 10000; new-called = 0 + 10000 = 10000, not > 25000 ->
    ;; not overcalled. Intermediate product 25000*40000 = 1e9, well under
    ;; the i32 ceiling (~2.147e9, see wasm/README.md's overflow note).
    (is (= 1 (run-capital-call-not-overcalled? 25000 100000 40000 0)))))

(deftest capital-call-wasm-approves-exact-boundary
  (testing "new-called-amount lands EXACTLY on commitment-amount (not strictly greater) -> still approves"
    ;; hand-verified: allocation = quot(20000 * 60000, 80000) =
    ;; quot(1_200_000_000, 80000) = 15000; called-amount is chosen so
    ;; new-called = 5000 + 15000 = 20000, exactly equal to
    ;; commitment-amount (20000) -- `>` is strict, so equal-to-commitment
    ;; is the boundary of "not overcalled", not itself a violation
    ;; (mirrors trustfund.registry/capital-call-allocations's own
    ;; `(> new-called commitment-amount)` predicate).
    (is (= 1 (run-capital-call-not-overcalled? 20000 80000 60000 5000)))))

(deftest capital-call-wasm-rejects-clear-overcall
  (testing "same commitment/total/call-amount as the boundary case but a higher prior called-amount pushes new-called past commitment -> rejects"
    ;; hand-verified: same allocation = 15000 (call/total/commitment
    ;; ratio unchanged); called-amount=10000 -> new-called = 10000 +
    ;; 15000 = 25000, which IS > commitment-amount (20000) -> overcalled,
    ;; trustfund.governor's :allocation-mismatch HARD violation.
    (is (= 0 (run-capital-call-not-overcalled? 20000 80000 60000 10000)))))

(deftest capital-call-wasm-handles-zero-call-amount
  (testing "zero call-amount -> allocation is 0 (not a crash); new-called-amount is just the pre-existing called-amount, so overcall depends only on whether that alone already exceeds commitment"
    ;; hand-verified: allocation = quot(20000 * 0, 80000) = 0.
    ;; called-amount=15000 -> new-called=15000, not > 20000 -> approves.
    ;; called-amount=25000 (already past commitment, e.g. from a data
    ;; error upstream) -> new-called=25000 > 20000 -> rejects, even
    ;; though THIS call adds nothing.
    (is (= 1 (run-capital-call-not-overcalled? 20000 80000 0 15000)))
    (is (= 0 (run-capital-call-not-overcalled? 20000 80000 0 25000)))))
