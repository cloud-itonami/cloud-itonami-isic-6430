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
