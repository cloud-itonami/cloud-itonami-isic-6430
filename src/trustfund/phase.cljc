(ns trustfund.phase
  "Phase 0->3 staged rollout -- the trust/fund-vehicle analog of
  `cloud-itonami-isic-6499`'s `vcfund.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- subscription intake allowed, every
                                 write needs human approval.
    Phase 2  same as 1        -- reserved for a future write category.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:subscription/record` (no capital risk)
                                 may auto-commit. `:capital-call/issue-
                                 notice`/`:distribution/record`/
                                 `:nav/disclose` NEVER auto-commit, at
                                 any phase.

  `:capital-call/issue-notice`/`:distribution/record`/`:nav/disclose` are
  deliberately ABSENT from every phase's `:auto` set, including phase 3
  -- a permanent structural fact, not a rollout milestone still to come.
  Issuing a capital-call notice, recording a distribution and disclosing
  NAV are all REAL legal acts (a binding demand for funds sent to LPs, an
  actual disbursement paid out to them, and a financial statement LPs
  will rely on); all three are always a human trustee/fund-officer call.
  `trustfund.governor`'s `:actuation/issue-notice`/`:actuation/record-
  distribution`/`:actuation/disclose-nav` high-stakes gate enforces the
  same invariant independently -- two layers, not one, agree on this.
  `:subscription/record` moves no capital (governed by its own HARD
  check in `trustfund.governor`, but never `high-stakes`), so it IS
  auto-eligible at phase 3.

  The decision core is delegated to the safety kernel
  `trustfund.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own battery
  and the parity matrix in `trustfund.kernels.gate-test` pin the two
  representations together."
  (:require [trustfund.kernels.gate :as kernel]))

(def read-ops  #{})
(def write-ops #{:subscription/record :capital-call/issue-notice
                 :distribution/record :nav/disclose})

;; NOTE the invariant: `:capital-call/issue-notice`/`:distribution/
;; record`/`:nav/disclose` are members of `write-ops` (governor-gated
;; like any write) but are NEVER members of any phase's `:auto` set
;; below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                       :auto #{}}
   1 {:label "assisted-intake" :writes #{:subscription/record}    :auto #{}}
   2 {:label "assisted-intake" :writes #{:subscription/record}    :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:subscription/record}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. This domain has NO read ops (`read-ops` is
  empty), so nothing ever maps to the reserved read code 0; unknown
  ops map to 5 (unknown write) — the kernel never write-enables
  either, so an unrecognized op fails closed to HOLD exactly as the
  old set-membership logic did."
  [op]
  (cond
    (contains? read-ops op)             0
    (= op :subscription/record)         1
    (= op :capital-call/issue-notice)   2
    (= op :distribution/record)         3
    (= op :nav/disclose)                4
    :else                               5))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:capital-call/issue-notice`/`:distribution/record`/`:nav/disclose`
    are never auto-eligible at any phase, so they always escalate once
    the governor clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map a TrustFundGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
