# cloud-itonami-isic-6430

Open Business Blueprint for **ISIC Rev.5 6430**: Trusts, funds and similar
financial entities.

This repository is the **trust/fund VEHICLE** in a three-actor VC-fund
system, the other two being `cloud-itonami-isic-6499` (the investment
decision-maker: DD, deal sourcing, capital-call/commitment/distribution
PROPOSALS) and `cloud-itonami-isic-6630` (the management company: fee
drawdown, mandate compliance). This repo is the legal entity that
actually HOLDS the LP subscriptions and issues the binding capital-call
NOTICE, LP-distribution and NAV disclosure off upstream investment-actor
facts -- run by a qualified, licensed operator so a community or
independent professional never surrenders customer data and ledgers to
a closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a secure document-custody robot manages physical trust-deed/fund-prospectus custody,
under an actor that proposes actions and an independent **TrustFundGovernor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Relationship to `cloud-itonami-isic-6499` (the investment actor)

**Three separate legal entities, three separate repos, no shared code --
only a documented DATA CONTRACT.** A real VC fund is legally three
things: the investment decision-maker (6499), the fund vehicle itself
(this repo, 6430) and the management company (6630). Each is its own
governed actor with its own fiduciary duty and its own governor; none
requires or imports another's source code (the same "self-contained
sibling" posture `vcfund.*` already takes toward `kotoba-lang/insurance`).

`vcfund.registry/register-capital-call` (in the SEPARATE `cloud-itonami-
isic-6499` repo) drafts a capital-call PROPOSAL -- a pro-rata allocation
computed from the investment actor's OWN view of the LP directory. That
draft is not itself a legal act. It becomes one only when THIS vehicle's
`trustfund.advisor` reads it as an upstream fact and `:capital-call/
issue-notice` proposes the actual NOTICE the trustee sends to LPs.
`trustfund.governor` NEVER trusts the upstream draft's numbers as-is: it
independently re-derives the SAME pro-rata-by-commitment-share math
(`trustfund.registry/capital-call-allocations`, a deliberately SEPARATE
re-implementation, not a shared-library call) against THIS vehicle's own
subscription ledger, and HARD-holds on any mismatch (see `Actuation`
below) -- exactly the same "never trust the advisor's self-check"
discipline `vcfund.governor/overcall-violations` applies inside its own
repo, now applied ACROSS the repo boundary.

A second cross-repo op, `:distribution/record`, reads an upstream
`vcfund.registry/register-distribution` exit-distribution fact and
records the LP-side disbursement. Its scope is **honestly narrower**
than the capital-call op: `register-distribution`'s waterfall exposes
only a fund-WIDE `total_to_lp` aggregate, never a per-LP breakdown, so
there is no upstream per-LP claim for this vehicle to cross-check
against (unlike the capital-call case, where `vcfund` DOES compute a
genuine per-LP allocation this vehicle can compare its own recomputation
to). `trustfund.registry/distribution-allocations` therefore COMPUTES
the authoritative per-LP split itself (the same pro-rata-by-commitment-
share math, applied to a distribution instead of a call), and
`trustfund.governor` can only guard the two checks that ARE possible
without an upstream per-LP claim: that at least one subscription exists
to distribute against, and that the same upstream commitment is never
recorded twice.

A third cross-repo op, `:nav/disclose`, reads an upstream `vcfund.nav/
fund-nav-report`/`lp-capital-account-report` fact and drafts the NAV
statement this vehicle sends its LPs. Narrower still: `vcfund.nav`'s NAV
and per-LP ownership/distribution shares depend on portfolio valuations
this vehicle has no data to independently recompute at all -- unlike the
other two ops, there is no math this vehicle can re-derive here. The ONE
figure it CAN independently verify is each LP's `:called-amount`
(tracked by THIS vehicle's own `:capital-call/issue-notice` history, a
book of record separate from `vcfund`'s own) -- `trustfund.governor`
HARD-holds if that figure does not match, or if the disclosure names an
LP with no subscription on file, but otherwise carries the upstream
report through as a reported fact, not an independently verified one.

## Core Contract

```text
upstream vcfund capital-call draft / exit-distribution fact / NAV report (a separate repo's proposal, read as a fact)
        |
        v
   ┌──────────────┐   proposal      ┌──────────────────┐
   │ TrustAdmin-LLM│ ─────────────▶ │ TrustFundGovernor  │  (independent system)
   │  (sealed)    │  + citations    │ unaccredited ·     │
   └──────────────┘                 │ subscription-      │
                             commit ◀────┼──────────▶ hold │ missing · allocation-
                                 │             │           │ mismatch · no-subscriptions-
                           record + ledger  escalate ─▶ human   for-distribution ·
                                             (ALWAYS for      distribution-already-
                                              :capital-call/    recorded · nav-
                                              issue-notice,     disclosure-subscription-
                                              :distribution/     missing · called-amount-
                                              record AND        mismatch
                                              :nav/disclose)
```

No automated proposal, by itself, can issue a capital-call notice,
record an LP distribution, or disclose NAV without `TrustFundGovernor`
approval and a human trustee/fund-officer sign-off.

## Actuation

**Issuing a capital-call notice, recording an LP distribution, or
disclosing NAV is never autonomous, at any phase, by construction.**
These are the three real-world legal acts this vehicle performs -- a
binding demand for funds sent to LPs, a binding disbursement recorded
against them, and a financial statement LPs will rely on.
`trustfund.governor/high-stakes` has three members, `:actuation/issue-
notice`, `:actuation/record-distribution` and `:actuation/disclose-nav`;
`trustfund.phase` never puts any of them in any phase's `:auto` set. Two
independent layers enforce this. `:subscription/record` moves no capital
(still HARD-gated -- an unaccredited subscriber is un-overridable -- but
not `high-stakes`), so it IS auto-eligible at phase 3.

## Run

```bash
clojure -M:dev:run     # walk three clean lifecycles (subscription+notice, distribution-record, nav-disclose) + seven HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Layout

| File | Role |
|---|---|
| `src/trustfund/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + subscription/capital-call-notice/distribution/nav-disclosure history |
| `src/trustfund/registry.cljc` | Subscription-agreement + capital-call-NOTICE + distribution-NOTICE + NAV-disclosure draft records; `capital-call-allocations`/`distribution-allocations` (INDEPENDENT re-implementations of `vcfund.registry`'s pro-rata math -- see "Relationship") |
| `src/trustfund/advisor.cljc` | **TrustAdmin-LLM** -- `mock-advisor`; subscription-intake/capital-call-notice/distribution-record/nav-disclosure proposals (the latter three read an upstream `vcfund` fact) |
| `src/trustfund/governor.cljc` | **TrustFundGovernor** -- 7 HARD checks (unaccredited-subscriber · subscription-missing · allocation-mismatch · no-subscriptions-for-distribution · distribution-already-recorded · nav-disclosure-subscription-missing · called-amount-mismatch) + 1 soft (confidence/actuation gate) |
| `src/trustfund/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → supervised (capital-call notice, distribution record and NAV disclosure always human; subscription intake auto-eligible, no capital risk) |
| `src/trustfund/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/trustfund/sim.cljc` | demo driver -- includes literal upstream-fact fixtures matching `vcfund.registry/register-capital-call`/`register-distribution`/`vcfund.nav`'s exact output shapes |
| `test/trustfund/*_test.clj` | governor contract · phase invariants · store parity · registry conformance |

## Business-process coverage (honest)

This actor covers THREE flagship cross-repo integration points (capital-
call notice issuance, LP-distribution recording and NAV disclosure, all
off upstream investment-actor facts) plus the subscription-intake
foundation they depend on. It does **not** yet cover every offer this
blueprint's `docs/business-model.md` lists:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| LP/beneficiary subscription-agreement intake, HARD-gated on accreditation (`:subscription/record`) | Real transfer-agent/banking integration, tax/regulatory reporting, fund-formation/LPA drafting |
| Capital-call NOTICE issuance off an upstream `vcfund` (`cloud-itonami-isic-6499`) capital-call draft, independently re-verified against THIS vehicle's own subscription ledger, never trusting the upstream numbers as-is (`:capital-call/issue-notice`) | |
| Distribution disbursement recording off an upstream `vcfund` exit-distribution fact (`:distribution/record`) -- **honestly narrower** than the capital-call op: `vcfund`'s waterfall exposes only a fund-wide `total_to_lp`, never a per-LP claim, so this vehicle COMPUTES (not verifies) the per-LP split itself, and can only HARD-check that subscriptions exist and the same commitment is never recorded twice | |
| NAV disclosure off an upstream `vcfund.nav` report/capital-account fact (`:nav/disclose`) -- **narrower still**: NAV and ownership/distribution shares depend on portfolio valuations this vehicle has no data to recompute at all, so it can only HARD-check the ONE figure it independently tracks (each LP's called-amount) and that every named LP has a subscription on file | |
| Immutable audit ledger for every subscription/notice/distribution/disclosure decision | |

Extending coverage is additive: add the next gate as its own governed
op with its own HARD checks and tests, following the SAME cross-repo
"read an upstream fact, never trust it, independently re-verify (or,
where no upstream math can be re-derived at all, verify only the one
figure this vehicle itself tracks, and say so honestly)" pattern this
repo's three flagship ops already establish.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6430`). `trustfund.*` is a self-contained governed implementation -- it
does not require the sibling `kotoba-lang/securities` capability lib
directly, the same "self-contained sibling" relationship `vcfund.*` has
to `kotoba-lang/insurance`.

See [`docs/business-model.md`](docs/business-model.md),
[`docs/operator-guide.md`](docs/operator-guide.md) and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md).

## Maturity

`:implemented` -- `TrustAdmin-LLM` + `TrustFundGovernor` run as real,
tested code (see `Run` above), promoted from the originally-published
`:blueprint`-tier scaffold. See `docs/adr/0001-architecture.md` for the
history and the cross-repo integration design.

## License

Code and implementation templates are AGPL-3.0-or-later.
