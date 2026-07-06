# ADR-0001: cloud-itonami-isic-6430 -- TrustAdmin-LLM as a contained intelligence node, cross-repo integration with `cloud-itonami-isic-6499`

- Status: Accepted (2026-07-06)
- Related: `cloud-itonami-isic-6499` ADR-0001 (DD-LLM ⊣
  InvestmentCommitteeGovernor, the venture-fund investment actor this
  repo interoperates with), `cloud-itonami-isic-6630` ADR-0001
  (FundManager-LLM ⊣ FundManagementGovernor, the third actor in the same
  three-repo VC-fund system), `cloud-itonami-isic-6511` ADR-0001
  (Underwriter-LLM ⊣ UnderwritingGovernor, the original pattern this
  family ports), langgraph-clj ADR-0001

## Context and history

This repository was originally published (2026-07-04) as a generic
`:blueprint`-tier ISIC 6430 scaffold ("trusts, funds and similar
financial entities") -- README/blueprint.edn/docs only, no running code,
generic `TrustAdmin-LLM`/`Trust/Fund Governor` names with no actual
implementation behind them.

The owner asked (2026-07-06) whether `cloud-itonami-isic-6499` (the
venture-capital investment actor, `:implemented` in the same session)
was itself "連携" (linked/integrated) with any other ISIC classification.
The honest answer at the time: `6430` (this repo, the fund vehicle) and
`6630` (fee-based fund management) were named in `6499`'s own ADR as
"adjacent" pieces of a real fund's legal structure, but the relationship
was documentation-only -- neither had a line of code, and `6499` did not
actually exchange any data with them. Asked to design real 連携/連動
(interoperation), the owner picked the largest of three offered scopes:
implement BOTH `6430` and `6630` as real governed actors and wire them
to genuinely interoperate with `6499`.

## Problem

A real VC fund is legally three separate things:

1. **The investment decision-maker** (`6499`, already implemented) --
   decides WHAT capital moves: sourcing, DD, term sheets, capital-call
   PROPOSALS, commitment, follow-on, exit-distribution PROPOSALS.
2. **The fund vehicle itself** (this repo, `6430`) -- the legal entity
   that actually HOLDS the LP subscriptions and fund cash, and whose
   signature/authority actually BINDS an LP under their subscription
   agreement when a capital call is issued.
3. **The management company** (`6630`) -- the GP entity that earns
   management fees and carry for running the fund.

These are three separate legal entities in the real world, and -- per
this workspace's established discipline (`vcfund.*`'s own "self-
contained sibling, not a shared-code dependency" relationship to
`kotoba-lang/insurance`) -- three separate repos here too. So "連携/連動
する設計" cannot mean a shared library or a shared database; it has to
mean a **documented DATA CONTRACT**: what fact one actor's committed
proposal produces, that another actor's governed op reads as an input,
and how that second actor avoids just rubber-stamping the first actor's
numbers.

## Decision

### 1. `capital-call/issue-notice` is the flagship integration op

`vcfund.registry/register-capital-call` (in the SEPARATE `cloud-itonami-
isic-6499` repo) drafts a capital-call PROPOSAL: a pro-rata allocation
computed from the investment actor's own view of the LP directory. That
proposal, by itself, moves no capital and binds nobody -- it becomes a
real legal act only when THIS vehicle, the entity whose subscription
agreements actually exist with the LPs, issues the NOTICE.

`trustfund.advisor/propose-capital-call-notice` reads an upstream call
draft (`:upstream-call-draft`, the EXACT map shape
`vcfund.registry/register-capital-call` returns --
`{"record" {"record_id" .. "call_amount" .. "allocations" [...] ..}
"call_number" .. "certificate" ..}`) as a fact, never invented. It
extracts `call_amount`/`record_id`/`notice_date` from it and proposes
`:capital-call/notice-issued`.

`trustfund.governor` NEVER trusts that upstream draft's numbers as-is.
Two independent HARD checks fire on it:

- **`subscription-missing-violations`** -- every LP the upstream draft
  allocates to must have an executed subscription on file with THIS
  vehicle (`trustfund.store/lp`). Cannot issue a notice to a party the
  fund vehicle itself has no legal relationship with, no matter what the
  upstream investment actor's own LP directory claims.
- **`allocation-mismatch-violations`** -- `trustfund.registry/capital-
  call-allocations` INDEPENDENTLY re-derives the pro-rata split from
  THIS vehicle's own subscription ledger and compares it, per LP, against
  the upstream draft's claimed allocation (within float tolerance). ANY
  mismatch is a HARD hold -- including the case where the recomputed
  allocation is itself flagged `:overcall?` (an upstream draft can never
  claim a number that matches an overcalled recomputation, since the
  independent math would produce a DIFFERENT, correctly-capped figure).

### 2. Deliberately SEPARATE re-implementation of the pro-rata math

`trustfund.registry/capital-call-allocations` is NOT a call into
`vcfund.registry/capital-call-allocations` -- it is an independent
re-implementation of the same well-known, unambiguous math (pro-rata by
commitment share). This is deliberate, not duplication to refactor away:
if both sides called through ONE shared implementation, a bug in that
shared code would silently defeat the entire point of "never trust the
upstream numbers, independently re-verify" -- the two sides would always
agree, correctly or not, together. Two independent implementations of the
same formula is what makes the cross-check meaningful.

### 3. Subscription intake is the foundation, non-actuation write

`:subscription/record` (an LP's own subscription-agreement record with
THIS vehicle) has a HARD gate (`unaccredited-subscriber-violations`) but
is NOT `high-stakes` -- no capital moves in recording paperwork -- so it
is auto-eligible at phase 3, the same lighter-touch posture `vcfund.
phase` gives `:deal/advance-stage`/`:portfolio/report`.

### 4. `:actuation/issue-notice` is the one high-stakes member

Unlike `vcfund.governor/high-stakes` (four members: call/deploy/
distribute/clawback, since one investment actor spans four capital
directions), this vehicle performs exactly ONE real-world act today:
issuing a capital-call notice. `trustfund.phase` never puts it in any
phase's `:auto` set -- two independent layers (governor high-stakes gate,
phase table) agree, the same invariant `vcfund.*` establishes.

### 5. Scoped to ONE flagship op for this R0, not the full blueprint Offer

This blueprint's own `docs/business-model.md` Offer list (unchanged from
the original `:blueprint`-tier publication) also names "NAV/valuation
disclosure proposal" and "distribution proposal." This R0 implements only
subscription intake + capital-call-notice issuance -- the ONE op that
concretely demonstrates the cross-repo integration pattern end-to-end,
correctly and fully tested, rather than three ops implemented shallowly.
NAV publication and distribution recording (which would follow the SAME
"read an upstream `vcfund` fact, independently re-verify, never trust
it" pattern -- against `vcfund.nav/fund-nav-report` and `vcfund.registry/
register-distribution` respectively) are documented as the explicit next
steps in README's coverage table, not silently claimed as done.

## Consequences

- (+) The cross-repo integration is PROVEN, not merely asserted: `trust-
  fund.governor`'s allocation-mismatch check is exercised by both a
  CLEAN upstream draft (escalates then commits) and a TAMPERED one
  (HARD-held) in both the demo (`clojure -M:dev:run`) and the test suite
  (`governor_contract_test.clj`).
- (+) No shared-code dependency between `cloud-itonami-isic-6499` and
  this repo -- each is independently forkable, independently deployable,
  matches the "self-contained sibling" convention the whole
  `cloud-itonami-*` fleet already follows.
- (-) Because there is no shared library, the DATA CONTRACT (the exact
  shape of `vcfund.registry/register-capital-call`'s return value) is
  documented but not type-checked across the repo boundary -- a future
  `vcfund.registry` change to that shape would silently break this
  repo's ingestion unless BOTH READMEs are kept in sync by hand. Accepted
  for this R0; a shared schema/contract-test package would be the
  eventual fix if the two repos' actual field shapes ever drift.
- (-) NAV publication and distribution recording remain blueprint-only
  for this vehicle (see README's coverage table) -- a real deployment
  still needs a proper fund-administration system for those functions.

## Test/lint status

`test/trustfund/*` -- 26 tests / 116 assertions, lint-clean
(`clojure -M:lint`), demo (`clojure -M:dev:run`) runs end-to-end with no
exceptions: one clean subscription+notice lifecycle (escalate → approve
→ commit) plus three HARD-hold cases (unaccredited subscriber, notice
referencing an unsubscribed LP, tampered/mismatched upstream allocation)
that never reach a human.

## Addendum 1: `:distribution/record`, the second cross-repo integration op

### Context

Decision point 5 above ("Scoped to ONE flagship op for this R0") and
README's coverage table both named distribution recording as the
explicit next step, following the SAME "read an upstream `vcfund` fact,
independently re-verify, never trust it" pattern `:capital-call/issue-
notice` already establishes. This addendum closes that gap.

### Discovery: no upstream per-LP claim exists for distributions

Before writing any code, `vcfund.registry/register-distribution`'s
actual return shape was re-checked. Unlike `register-capital-call`
(which computes and returns a genuine per-LP allocation list this
vehicle can independently recompute and compare against), `register-
distribution`'s embedded `distribute-waterfall` result carries ONLY a
fund-WIDE aggregate (`:total-to-lp`, alongside `:return-of-capital` /
`:preferred-return-due` / `:gp-carry` / `:lp-residual-profit`, all still
fund-wide) -- there is no per-LP field anywhere in it.

This means the "independently re-verify the upstream's own per-LP
claim" pattern the capital-call op uses does not apply here: there is no
upstream per-LP claim to verify. Two options were considered: (a) narrow
the op to record only the fund-wide total (no LP-level ledger effect at
all), or (b) have THIS vehicle compute the authoritative per-LP split
itself, from its own subscription ledger, treating the upstream fact as
authorizing only the FUND-WIDE amount to disburse. (b) was chosen --
it is the only option that produces an actual per-LP distribution
record (the whole point of the op), and it keeps the vehicle, not the
investment actor, as the authority over its own LP directory and
allocations, consistent with `trustfund.*` already being the SSoT for
LP records. This narrower scope (compute, not verify) is stated
explicitly in `trustfund.registry/distribution-allocations`'s docstring,
`trustfund.governor`'s docstring, and README/business-model.md -- not
silently glossed over as if it worked exactly like the capital-call op.

### Decision points

1. **`trustfund.registry/distribution-allocations`** is a NEW pure
   function, structurally the pro-rata-by-commitment-share twin of
   `capital-call-allocations`, but for OUTGOING distributions: it has no
   `:called-amount`/`:overcall?` concept (a distribution cannot "overcall"
   an LP -- there is no cap to exceed). `register-distribution-notice`
   builds a `{JURISDICTION}-DIST-{6-digit}` draft record referencing the
   upstream `commitment_number`, mirroring `register-capital-call-notice`'s
   shape exactly (unsigned certificate, `"immutable" true`, sequence
   number from a dedicated per-jurisdiction `distribution-sequences` map).
2. **Only two HARD checks are possible, not three.** Because there is no
   upstream per-LP claim, there is no "allocation-mismatch" check to
   write for this op. `no-subscriptions-for-distribution-violations`
   guards against recording a distribution when the LP directory is
   empty (nothing to allocate against); `distribution-already-recorded-
   violations` guards against recording the same upstream
   `commitment_number` twice (an idempotency/double-spend guard, the same
   shape `fundmgmt.governor/double-distribution-violations` uses for GP
   carry in the sibling `cloud-itonami-isic-6630` repo). `trustfund.
   governor/high-stakes` grew to `#{:actuation/issue-notice :actuation/
   record-distribution}`; `trustfund.phase/write-ops` grew to include
   `:distribution/record`, never auto at any phase.
3. **A real bug was caught before any test ran.** The first draft of
   `distribution-already-recorded-violations` destructured a top-level
   `:upstream-commitment-number` key directly off the raw governor
   request -- but the raw request only ever carries the NESTED
   `:upstream-distribution-fact`; the commitment number is something the
   ADVISOR derives from it, not something present on the request map
   itself. Caught by re-reading the advisor's actual proposal shape
   before wiring the check, and fixed to `get-in` the commitment number
   from `upstream-distribution-fact` directly, mirroring exactly how the
   capital-call checks already derive fields from `upstream-call-draft`.

### Consequences

- (+) The second cross-repo integration point is PROVEN the same way the
  first one is: exercised by a clean distribution fact (escalates then
  commits), a no-subscriptions fact (HARD-held), and a double-recording
  of the same commitment (HARD-held), in both the demo and
  `governor_contract_test.clj`.
- (+) The honest scope-narrowing (compute, not verify, the per-LP split)
  is documented in code and docs rather than silently presented as
  symmetric with the capital-call op -- consistent with this repo's
  "honestly bounded, not silently narrowed" posture throughout.
- (-) Because this vehicle computes its own per-LP split from its own
  subscription ledger rather than verifying an upstream claim, a
  discrepancy between `vcfund`'s intended LP-level distribution (which it
  does not expose) and this vehicle's recomputation would go undetected
  by construction -- there is nothing to compare against. Accepted for
  this addendum; would only be closable if `vcfund.registry/
  register-distribution` were changed to also expose a per-LP
  breakdown, which is out of scope for this repo to demand.
- NAV publication remains the one blueprint-only offer left uncovered
  (see README's coverage table).

`test/trustfund/*` after this addendum -- 36 tests / 174 assertions,
lint-clean (`clojure -M:lint`), demo (`clojure -M:dev:run`) runs
end-to-end with no exceptions: two clean lifecycles (capital-call notice,
distribution record; both escalate → approve → commit) plus five
HARD-hold cases (unaccredited subscriber, notice referencing an
unsubscribed LP, tampered/mismatched upstream allocation, distribution
recorded with no subscriptions on file, double-recording of an
already-recorded distribution) that never reach a human.

## Addendum 2: `:nav/disclose`, the third cross-repo integration op, and the limit of what this vehicle can ever verify

### Context

README's coverage table named NAV/valuation disclosure as the one
offer this blueprint originally published (in its `:blueprint`-tier
scaffold) that neither R0 nor Addendum 1 implemented. Before writing any
code, `vcfund.nav`'s actual functions (`fund-nav-report`,
`lp-capital-account-report`) were read in full to find the genuine
cross-check this op could perform, following the same discipline
Addendum 1 used to discover distribution-record's narrower scope.

### Discovery: this vehicle can verify called-amount, and NOTHING else

`vcfund.nav/fund-nav-report`'s NAV figure derives from portfolio-level
facts entirely outside this vehicle's data (deal cost-bases, fair-value
marks, exit proceeds); `lp-capital-account-report`'s per-LP ownership-pct/
distributed-to-date/nav-share all derive from that same whole-fund NAV.
This vehicle holds none of that data -- it has no deal/portfolio ledger
at all, only its own subscription and capital-call-notice history. So
UNLIKE `:distribution/record` (where this vehicle at least COMPUTES an
authoritative per-LP split from its own ledger), for `:nav/disclose`
there is no math of any kind this vehicle can independently perform on
NAV or ownership shares -- it can only carry the upstream figures through
as reported facts.

The ONE exception: `lp-capital-account-report`'s `:called-amount` per LP
is not a NAV-derived figure at all -- `vcfund.nav`'s own `unfunded-
commitments` reads it straight from `vcfund.store`'s LP directory, which
`vcfund`'s OWN `:capital-call/call` op maintains. THIS vehicle
independently maintains its OWN called-amount per LP too (advanced by
`:capital-call/issue-notice`, a completely separate write path from
`vcfund`'s). The two are genuinely independent books of record for the
same fact, so comparing them is a real cross-check -- the same kind
`allocation-mismatch-violations` performs, just a direct comparison
instead of a recompute (there is no formula to re-derive a stored
called-amount from). This is the honest floor of what integration is
possible for this op, and it is stated explicitly in `trustfund.
registry/register-nav-disclosure`'s docstring, `trustfund.governor`'s
docstring, and README/business-model.md.

### Decision points

1. **`trustfund.registry/register-nav-disclosure`** is a NEW pure
   function building a `{JURISDICTION}-NAV-{6-digit}` draft record,
   mirroring the other two notice-registration functions' shape (unsigned
   certificate, `"immutable" true`, dedicated per-jurisdiction
   `nav-disclosure-sequences` map) -- but its `lp-accounts` are carried
   through VERBATIM from the upstream fact rather than computed by this
   vehicle, since (per the discovery above) there is nothing for this
   vehicle to compute here.
2. **The upstream fact has a genuinely different shape from the other
   two.** `vcfund.registry/register-capital-call`/`register-distribution`
   return string-keyed `{"record" .. "certificate" ..}` draft/certificate
   envelopes (they represent THAT repo's own signed-eventually legal
   proposals). `vcfund.nav/fund-nav-report`/`lp-capital-account-report`
   are plain read-only report adapters with NO such envelope (computing
   NAV is not itself a legal act by `vcfund` -- see that repo's own
   `vcfund.nav` docstring) -- just keyword-keyed maps. `trustfund.sim`'s
   `upstream-nav-report`/`upstream-lp-capital-account` fixtures and
   `trustfund.governor`'s checks read them as such, rather than forcing a
   string-keyed shape that does not reflect what `vcfund.nav` actually
   returns.
3. **Two HARD checks, both direct comparisons, neither a recompute.**
   `nav-disclosure-subscription-missing-violations` (an LP in the
   upstream capital-account report with no subscription on file --
   structurally identical to `subscription-missing-violations`) and
   `called-amount-mismatch-violations` (the one genuine cross-check
   described above; deliberately skips any LP already flagged missing,
   since there is nothing local to compare a missing LP's amount
   against). `trustfund.governor/high-stakes` grew to `#{:actuation/
   issue-notice :actuation/record-distribution :actuation/disclose-nav}`;
   `trustfund.phase/write-ops` grew to include `:nav/disclose`, never
   auto at any phase -- disclosing NAV moves no capital directly, but it
   is a financial statement an LP will rely on, the same actuation
   seriousness as the other two ops.

### Consequences

- (+) The third cross-repo integration point is PROVEN the same way the
  first two are: exercised by a clean disclosure (escalates then
  commits), a called-amount-mismatch fact (HARD-held), and a
  missing-subscription fact (HARD-held), in both the demo and
  `governor_contract_test.clj`.
- (+) The progressively narrowing verification scope across all three
  ops (capital-call: full independent recompute → distribution: this
  vehicle computes its own split, nothing to verify → NAV: only ONE
  figure is even comparable) is documented as a real, honest gradient,
  not glossed over as uniform coverage -- consistent with this repo's
  "honestly bounded, not silently narrowed" posture throughout.
- (-) A NAV or ownership-share figure this vehicle discloses could be
  wrong (stale portfolio marks, an upstream calculation bug) with NO
  chance of this vehicle's own governor catching it -- by construction,
  since it holds no portfolio data to check against. This is the honest
  floor of what a fund vehicle without its own valuation data can ever
  verify; closing it would require this vehicle to acquire independent
  portfolio-valuation data, which is out of scope for a trust/fund
  vehicle (that is `vcfund`'s domain, not this repo's).
- This closes the LAST offer named in this blueprint's original
  `docs/business-model.md` Offer list; remaining gaps (real transfer-
  agent/banking integration, tax/regulatory reporting, fund-formation/
  LPA drafting) were never part of this blueprint's Offer and remain
  out of scope by design, not by omission.

`test/trustfund/*` after this addendum -- 44 tests / 221 assertions,
lint-clean (`clojure -M:lint`), demo (`clojure -M:dev:run`) runs
end-to-end with no exceptions: three clean lifecycles (capital-call
notice, distribution record, NAV disclosure; all escalate → approve →
commit) plus seven HARD-hold cases (unaccredited subscriber, notice
referencing an unsubscribed LP, tampered/mismatched upstream allocation,
distribution recorded with no subscriptions on file, double-recording of
an already-recorded distribution, NAV disclosure with a called-amount
mismatch, NAV disclosure referencing an unsubscribed LP) that never reach
a human.
