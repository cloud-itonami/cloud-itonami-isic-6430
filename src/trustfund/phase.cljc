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
                                 notice` NEVER auto-commits, at any phase.

  `:capital-call/issue-notice` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Issuing a capital-call notice is a
  REAL legal act (a binding demand for funds sent to LPs); it is always a
  human trustee/fund-officer call. `trustfund.governor`'s
  `:actuation/issue-notice` high-stakes gate enforces the same invariant
  independently -- two layers, not one, agree on this.
  `:subscription/record` moves no capital (governed by its own HARD
  check in `trustfund.governor`, but never `high-stakes`), so it IS
  auto-eligible at phase 3.")

(def read-ops  #{})
(def write-ops #{:subscription/record :capital-call/issue-notice})

;; NOTE the invariant: `:capital-call/issue-notice` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                       :auto #{}}
   1 {:label "assisted-intake" :writes #{:subscription/record}    :auto #{}}
   2 {:label "assisted-intake" :writes #{:subscription/record}    :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:subscription/record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:capital-call/issue-notice` is never auto-eligible at any phase, so
    it always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a TrustFundGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
