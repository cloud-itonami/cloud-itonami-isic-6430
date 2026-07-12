(ns trustfund.kernels.gate
  "Safety kernel for the TrustFundGovernor + phase gate — the decision
  CORE of `trustfund.governor/check` and `trustfund.phase/gate`,
  extracted into the safe-kotoba subset (cloud-itonami kernels
  discipline, superproject ADR-2607101200), following
  `cloud-itonami-isic-6511`'s `underwriting.kernels.gate`.

  Everything here is integer-coded and stays inside the emit-ready
  vocabulary: `defn`, `def` constants, nested `if`, `=`, `<`, integer
  arithmetic, recursion-free composition through named combinators. No
  keywords, strings, maps, atoms, host interop or I/O — the façades
  (`trustfund.governor`, `trustfund.phase`) reduce their inputs to
  flags/codes at the boundary and map the result codes back to
  keywords. `.kotoba`/wasm emission is deliberately NOT wired yet
  (owner decision 2026-07-12: ClojureScript + kotoba-datomic first);
  staying inside the subset is what keeps that door open without a
  rewrite.

  This is a THIN-repo adaptation: the allocation-mismatch and
  called-amount-mismatch checks compare COLLECTIONS (per-LP maps with
  id matching and overcall flags), which the subset cannot express —
  so the per-LP iteration stays façade-side, and what lives in-kernel
  is (a) the scalar amount comparison every per-LP test reduces to
  (`amount-mismatch` over x1e6 micro-unit integers against
  `allocation-tolerance-x1e6`, the integer restatement of the old
  |a − b| < 1e-6 float tolerance) and (b) the whole verdict/phase
  decision over the resulting flags.

  Wire codes:
    flag        0 = no, anything else = yes (norm-flag, fail-closed)
    confidence  int x100 (0..100); out-of-range counts as LOW (fail-closed)
    amounts     int x1e6 (micro-units), rounded by the façade; compared
                in-kernel against `allocation-tolerance-x1e6`
    op          0 read (RESERVED — this domain has NO read ops, its
                `read-ops` set is empty; code 0 is never write-enabled
                and fails closed exactly like an unknown op)
                1 :subscription/record   2 :capital-call/issue-notice
                3 :distribution/record   4 :nav/disclose
                5+ unknown write (never enabled)
    phase       0..3 (phase 2 is the SAME row as phase 1 — reserved for
                a future write category; anything else: no writes at
                all — the façade normalizes unknown phases to its own
                default BEFORE the kernel, so an out-of-range phase
                reaching the kernel is a bug and fails closed)
    verdict     0 ok/commit-eligible  1 escalate  2 hard-hold
    reason      0 none  1 phase-disabled  2 phase-approval
    disposition 0 commit  1 escalate  2 hold

  Fail-closed direction: every invalid/unknown input degrades toward
  LESS autonomy (hold/escalate), never more. Ops 2/3/4
  (:capital-call/issue-notice, :distribution/record, :nav/disclose) —
  the three real-world legal acts this vehicle performs — are
  auto-enabled at NO phase: the same structural invariant the phase
  table and the governor's actuation gate state independently."
  )

;; --------------------------- combinators ---------------------------

(defn not-flag [a] (if (= a 0) 1 0))
(defn norm-flag
  "Fail-closed flag normalization: only exact 0 counts as 'no'."
  [a]
  (if (= a 0) 0 1))
(defn and2 [a b] (if (= a 1) (if (= b 1) 1 0) 0))
(defn or2 [a b] (if (= a 1) 1 (if (= b 1) 1 0)))
(defn or3 [a b c] (or2 a (or2 b c)))

;; --------------------------- governor core -------------------------

(def confidence-floor-x100 60)

(def allocation-tolerance-x1e6
  "Amount tolerance in x1e6 (micro-unit) integers: the integer
  restatement of the façade's old |a − b| < 1e-6 double-arithmetic
  tolerance — after the façade rounds both sides to micro-units, any
  difference of one micro-unit or more is a mismatch."
  1)

(defn confidence-low
  "1 when the advisor confidence requires a human look. Out-of-range
  values (negative, or above 100) are treated as LOW — an advisor
  reporting impossible confidence is a reason for MORE scrutiny, not
  auto-commit."
  [x100]
  (if (< x100 0)
    1
    (if (< 100 x100)
      1
      (if (< x100 confidence-floor-x100) 1 0))))

(defn abs-diff
  "Absolute difference of two integers (subset-safe: nested if + -)."
  [a b]
  (if (< a b) (- b a) (- a b)))

(defn amount-mismatch
  "1 when two amounts (x1e6 micro-unit integers) differ by at least
  `allocation-tolerance-x1e6`. The scalar core every per-LP
  allocation/called-amount comparison in the façade reduces to —
  never trust an upstream draft's figure as-is."
  [a-x1e6 b-x1e6]
  (if (< (abs-diff a-x1e6 b-x1e6) allocation-tolerance-x1e6) 0 1))

(defn hard-violation
  "1 when any HARD (human-un-overridable) violation flag is set:
  unaccredited subscriber / subscription missing for a capital call /
  allocation mismatch / no subscriptions for a distribution /
  distribution already recorded / NAV-disclosure subscription missing /
  called-amount mismatch."
  [unaccredited subscription-missing allocation-mismatch
   no-subscriptions distribution-recorded nav-subscription-missing
   called-amount-mismatch]
  (or3 (or3 (norm-flag unaccredited)
            (norm-flag subscription-missing)
            (norm-flag allocation-mismatch))
       (or3 (norm-flag no-subscriptions)
            (norm-flag distribution-recorded)
            (norm-flag nav-subscription-missing))
       (norm-flag called-amount-mismatch)))

(defn verdict-code
  "Governor verdict: 2 hard-hold wins over 1 escalate wins over 0 ok."
  [unaccredited subscription-missing allocation-mismatch
   no-subscriptions distribution-recorded nav-subscription-missing
   called-amount-mismatch confidence-x100 actuation]
  (if (= 1 (hard-violation unaccredited subscription-missing
                           allocation-mismatch no-subscriptions
                           distribution-recorded nav-subscription-missing
                           called-amount-mismatch))
    2
    (if (= 1 (or2 (confidence-low confidence-x100) (norm-flag actuation)))
      1
      0)))

;; ---------------------------- phase core ---------------------------

(defn op-write-enabled
  "1 when `op` may WRITE at `phase` (phase table row, :writes column).
  Phase 2 is the SAME row as phase 1 (reserved for a future write
  category). Op 0 (the reserved read code) is enabled NOWHERE: this
  domain's `read-ops` set is empty, so unlike sibling kernels there is
  no read pass-through — every op code must be write-enabled or it
  holds."
  [phase op]
  (if (= phase 1)
    (if (= op 1) 1 0)
    (if (= phase 2)
      (if (= op 1) 1 0)
      (if (= phase 3)
        (if (= op 1) 1 (if (= op 2) 1 (if (= op 3) 1 (if (= op 4) 1 0))))
        0))))

(defn op-auto-enabled
  "1 when `op` may AUTO-COMMIT at `phase` (phase table row, :auto
  column). Exactly one cell is ever 1: phase 3 x :subscription/record
  (moves no capital). Ops 2/3/4 (:capital-call/issue-notice,
  :distribution/record, :nav/disclose) are 0 at every phase —
  permanent structural fact, not a rollout milestone."
  [phase op]
  (if (= phase 3) (if (= op 1) 1 0) 0))

(defn phase-disposition
  "Resolve the final disposition code from phase, op code and the
  governor's disposition code. Mirrors `trustfund.phase/gate`:
  governor hold always wins; a write not enabled at this phase holds
  (there is no read pass-through — this domain has no read ops); a
  governor-clean write without auto rights escalates; otherwise the
  governor's disposition stands."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    2
    (if (= 0 (op-write-enabled phase op))
      2
      (if (= governor-disposition 0)
        (if (= 1 (op-auto-enabled phase op)) 0 1)
        governor-disposition))))

(defn phase-reason
  "Reason code companion of `phase-disposition` (same branch order)."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    0
    (if (= 0 (op-write-enabled phase op))
      1
      (if (= governor-disposition 0)
        (if (= 1 (op-auto-enabled phase op)) 0 2)
        0))))

;; ----------------------------- battery -----------------------------
;; Executable spec, kernels-style: each check returns 1 on pass, the
;; battery sums them, and the test suite locks the sum against
;; `battery-case-count` so a silently-skipped case can't pass review.

(defn check-verdict [unacc submiss alloc nosubs dup navmiss called conf act expected]
  (if (= (verdict-code unacc submiss alloc nosubs dup navmiss called conf act)
         expected)
    1 0))

(defn check-amount [a b expected]
  (if (= (amount-mismatch a b) expected) 1 0))

(defn check-phase [phase op gov expected-disposition expected-reason]
  (and2 (if (= (phase-disposition phase op gov) expected-disposition) 1 0)
        (if (= (phase-reason phase op gov) expected-reason) 1 0)))

(def battery-case-count 43)

(defn battery-pass-count []
  (+
   ;; -- verdict: each hard flag dominates alone (conf 100, act 0)
   (check-verdict 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 1 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 1 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 1 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 1 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 1 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 1 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 1 100 0 2)
   (check-verdict 1 1 1 1 1 1 1 100 0 2)
   (check-verdict 1 0 0 0 0 0 1 100 0 2)
   ;; -- verdict: confidence floor boundary + fail-closed range
   (check-verdict 0 0 0 0 0 0 0 59 0 1)
   (check-verdict 0 0 0 0 0 0 0 60 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 1)
   (check-verdict 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 0 0 0 0 0 0 0 -5 0 1)
   (check-verdict 0 0 0 0 0 0 0 150 0 1)
   ;; -- verdict: actuation always escalates; hard still wins over it
   (check-verdict 0 0 0 0 0 0 0 100 1 1)
   (check-verdict 1 0 0 0 0 0 0 100 1 2)
   (check-verdict 0 0 0 0 0 0 0 40 1 1)
   ;; -- verdict: non-0/1 flags normalize to violation (fail-closed)
   (check-verdict 7 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 100 9 1)
   ;; -- amounts: the scalar core of allocation/called-amount matching
   ;;    (x1e6 micro-units, in-kernel tolerance)
   (check-amount 5000000 5000000 0)
   (check-amount 5000000 5000001 1)
   (check-amount 5000001 5000000 1)
   (check-amount 0 0 0)
   ;; -- phase: governor hold always wins
   (check-phase 3 1 2 2 0)
   ;; -- phase: write disabled at this phase -> hold, phase-disabled
   (check-phase 0 1 0 2 1)
   (check-phase 1 2 0 2 1)
   (check-phase 2 2 0 2 1)
   (check-phase 2 3 0 2 1)
   (check-phase 2 4 0 2 1)
   (check-phase 3 5 0 2 1)
   ;; -- phase: op 0 (reserved read code; this domain has NO read ops)
   ;;    is write-enabled nowhere, even at phase 3 (fail-closed)
   (check-phase 3 0 0 2 1)
   ;; -- phase: enabled but not auto -> escalate, phase-approval
   (check-phase 1 1 0 1 2)
   (check-phase 2 1 0 1 2)
   (check-phase 3 2 0 1 2)
   (check-phase 3 3 0 1 2)
   (check-phase 3 4 0 1 2)
   ;; -- phase: the single auto cell
   (check-phase 3 1 0 0 0)
   ;; -- phase: governor escalate passes through an enabled write
   (check-phase 3 1 1 1 0)
   (check-phase 1 1 1 1 0)
   ;; -- phase: out-of-range phases have no writes (fail-closed)
   (check-phase -1 1 0 2 1)
   (check-phase 4 1 0 2 1)))
