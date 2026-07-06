# cloud-itonami-isic-6430

Open Business Blueprint for **ISIC Rev.5 6430**: Trusts, funds and similar
financial entities.

This repository is the **trust/fund VEHICLE** in a three-actor VC-fund
system, the other two being `cloud-itonami-isic-6499` (the investment
decision-maker: DD, deal sourcing, capital-call/commitment/distribution
PROPOSALS) and `cloud-itonami-isic-6630` (the management company: fee
drawdown, mandate compliance). This repo is the legal entity that
actually HOLDS the LP subscriptions and issues the binding capital-call
NOTICE off an upstream investment-actor proposal -- run by a qualified,
licensed operator so a community or independent professional never
surrenders customer data and ledgers to a closed SaaS.

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

## Core Contract

```text
upstream vcfund capital-call draft (a separate repo's proposal, read as a fact)
        |
        v
   ┌──────────────┐   proposal      ┌──────────────────┐
   │ TrustAdmin-LLM│ ─────────────▶ │ TrustFundGovernor  │  (independent system)
   │  (sealed)    │  + citations    │ unaccredited ·     │
   └──────────────┘                 │ subscription-      │
                             commit ◀────┼──────────▶ hold │ missing · allocation-
                                 │             │           │ mismatch (independent
                           record + ledger  escalate ─▶ human   pro-rata recompute)
                                             (ALWAYS for
                                              :capital-call/issue-notice)
```

No automated proposal, by itself, can issue a capital-call notice without
`TrustFundGovernor` approval and a human trustee/fund-officer sign-off.

## Actuation

**Issuing a capital-call notice is never autonomous, at any phase, by
construction.** It is the one real-world legal act this vehicle performs
-- a binding demand for funds sent to LPs. `trustfund.governor/high-
stakes` has one member, `:actuation/issue-notice`; `trustfund.phase`
never puts it in any phase's `:auto` set. Two independent layers enforce
this. `:subscription/record` moves no capital (still HARD-gated -- an
unaccredited subscriber is un-overridable -- but not `high-stakes`), so
it IS auto-eligible at phase 3.

## Run

```bash
clojure -M:dev:run     # walk one clean subscription+notice lifecycle + three HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Layout

| File | Role |
|---|---|
| `src/trustfund/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + subscription/capital-call-notice history |
| `src/trustfund/registry.cljc` | Subscription-agreement draft + capital-call-NOTICE draft records, `capital-call-allocations` (an INDEPENDENT re-implementation of `vcfund.registry`'s pro-rata math -- see "Relationship") |
| `src/trustfund/advisor.cljc` | **TrustAdmin-LLM** -- `mock-advisor`; subscription-intake/capital-call-notice proposals (the latter reads an upstream `vcfund` capital-call draft as a fact) |
| `src/trustfund/governor.cljc` | **TrustFundGovernor** -- 3 HARD checks (unaccredited-subscriber · subscription-missing · allocation-mismatch, independently re-verified) + 1 soft (confidence/actuation gate) |
| `src/trustfund/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → supervised (capital-call notice always human; subscription intake auto-eligible, no capital risk) |
| `src/trustfund/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/trustfund/sim.cljc` | demo driver -- includes literal upstream-draft fixtures matching `vcfund.registry/register-capital-call`'s exact output shape |
| `test/trustfund/*_test.clj` | governor contract · phase invariants · store parity · registry conformance |

## Business-process coverage (honest)

This actor covers the ONE flagship cross-repo integration point (capital-
call notice issuance off an upstream investment-actor proposal) plus the
subscription-intake foundation it depends on. It does **not** yet cover
every offer this blueprint's `docs/business-model.md` lists:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| LP/beneficiary subscription-agreement intake, HARD-gated on accreditation (`:subscription/record`) | NAV/valuation disclosure proposal (this blueprint's own Offer lists it; not yet a governed op here) |
| Capital-call NOTICE issuance off an upstream `vcfund` (`cloud-itonami-isic-6499`) capital-call draft, independently re-verified against THIS vehicle's own subscription ledger, never trusting the upstream numbers as-is (`:capital-call/issue-notice`) | Distribution disbursement recording off an upstream `vcfund` exit-distribution fact (the SAME integration pattern, not yet implemented) |
| Immutable audit ledger for every subscription/notice decision | Real transfer-agent/banking integration, tax/regulatory reporting, fund-formation/LPA drafting |

Extending coverage is additive: add the next gate as its own governed
op with its own HARD checks and tests, following the SAME cross-repo
"read an upstream fact, never trust it, independently re-verify" pattern
this repo's one flagship op already establishes.

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
