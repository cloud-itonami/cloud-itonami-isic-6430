# wasm/ — kotoba-wasm deployment of the pro-rata capital-call allocation + overcall check

`capital_call_allocation.kotoba` is a port of a SINGLE LP's slice of
`trustfund.registry/capital-call-allocations`'s pro-rata capital-call
allocation formula (`share = commitment-amount / total-committed`,
`allocation = share * call-amount`, `new-called-amount = called-amount +
allocation`, `overcall? = new-called-amount > commitment-amount`) — the
independent recompute `trustfund.governor`'s `:allocation-mismatch` HARD
check runs against an upstream `vcfund` capital-call draft's claimed
per-LP allocation, including catching an overcall on the recomputed side
(see `src/trustfund/governor.cljc`'s `allocation-mismatch-violations`
docstring: "ANY mismatch (including one caused by an overcall on the
recomputed side) is a HARD violation") — into the minimal `.kotoba`
language subset, compiled to a real WASM module via `kotoba wasm emit`,
and hosted via `kototama.tender` (`test/wasm/capital_call_allocation_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pipeline
`cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba` and
`cloud-itonami-isic-6630`'s `wasm/fee_accrual.kotoba` established
(ADR-2607062330 addendum 5) — `capital_call_allocation.kotoba` is closest
in shape to `fee_accrual.kotoba`: a formula recompute over integer
inputs, no host imports.

## Why the source differs from `trustfund.registry/capital-call-allocations`

The `.kotoba` compiler's actual WASM code-generator supports only a
small, empirically-verified subset: the special forms `do`/`let`/`if`
plus `+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by
reading `compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj`
— no `pos?`/`neg?`/`and`/`or`/`when`, no map destructuring, no
collections, same finding `cloud-itonami-isic-6492`/`-6511`/`-6630`
document). The real registry function computes this formula for EVERY LP
in a `subscriptions` collection at once (`mapv` over the coll, with each
LP's input pulled out of a `{:keys [id commitment-amount called-amount]}`
map); collections and maps are out of scope for the wasm-compilable
subset entirely, so this port is a **single-LP scalar extraction**: the
SAME formula, applied to one LP's four plain integer inputs
(`commitment-amount`, `total-committed`, `call-amount`, `called-amount`)
instead of iterating a coll. `total-committed` — the sum of every LP's
`commitment-amount` — is passed in as a plain scalar the host has
already summed (a `reduce +` over a collection is equally out of scope);
a real deployment would call this guest once per LP in the allocation,
with the host doing the coll `map`/`reduce` orchestration around it, the
same "guest does the pure per-item math, host does the collection
plumbing" split every `.kotoba` port in this pattern uses (extraction
rationale also documented in `capital_call_allocation.kotoba`'s own ns-
adjacent header comment). Concretely, the port:

- Uses plain positional args instead of `{:keys [...]}` map
  destructuring (no maps in the wasm-compilable subset), and processes
  one LP instead of a `subscriptions` coll (no `mapv`/`reduce`).
- Drops the two `throw`/`ex-info` precondition guards
  (`capital-call-allocations` throws if `call-amount` is negative or if
  `subscriptions` is empty / `total-committed` sums to zero) entirely —
  a WASM export can't throw a JVM exception, same "the guest only ever
  sees facts a governor already validated" posture
  `underwriting_decision.kotoba`/`fee_accrual.kotoba` document. Unlike
  `fee_accrual.kotoba` (whose formula has no division by a
  precondition-guarded value, so a bad input just flows through to a
  comparison that naturally fails), this formula genuinely DOES divide
  by `total-committed` — a `total-committed = 0` input is **not**
  guarded with an `if` inside this guest (there is no meaningful "safe"
  result to return for a fund with zero total commitment; the real
  `trustfund.registry/capital-call-allocations` treats that as a hard
  error, not a value) and would trap the WASM `i32.div_s` instruction
  instead of returning 0 or 1. `total-committed > 0` is therefore a
  documented host-side precondition — the host (mirroring
  `trustfund.governor`'s own `(empty? subscriptions)` short-circuit
  before it ever calls `registry/capital-call-allocations`) must never
  call this guest for a fund with no committed capital on file.
- Uses **integer cross-multiplication** (`quot (* commitment-amount
  call-amount) total-committed`) instead of the real function's two
  float divisions (`share = commitment-amount / total-committed`,
  `allocation = share * call-amount`) — avoids floating point entirely
  (no floats in the wasm-compilable subset), the same technique
  `affordability.kotoba` uses for its debt-to-income ratio check. All
  four inputs are already integers (smallest currency unit, i.e. cents)
  in the original formula's domain — no fixed-point rescaling (like
  `fee_accrual.kotoba`'s basis-points/hundredths-of-a-year scaling) is
  needed here, only reordering the multiply before the divide so the
  division is exact-or-truncated integer division instead of two
  compounding float roundings. This integer truncation is at least as
  faithful as the original: the real function's doubles are also
  inexact, and `trustfund.governor`'s own comparison
  (`allocation-mismatch-violations`) already tolerates float noise via
  `close?`/`allocation-tolerance`.
- Returns the polarity **`trustfund.governor`'s check actually uses**:
  `1` means this LP's recomputed allocation does NOT overcall it (a
  well-formed, non-violating result — mirrors
  `allocation-mismatch-violations`'s `(not (:overcall? r))` conjunct,
  and the sibling `vcfund.governor/overcall-violations` in
  `cloud-itonami-isic-6499` — see that ns's `filter :overcall?` +
  "if any exist, HARD violation" — corroborates the same formula's
  `:overcall?` polarity from the investment-actor side), `0` means it
  IS overcalled (a HARD violation, never a soft/overridable one).

**Known scope limit (i32 range):** the guest computes
`commitment-amount * call-amount` in a single 32-bit WASM `i32.mul`
before dividing, so that intermediate product must stay under the signed
i32 ceiling (~2.147e9) or it silently wraps instead of trapping (the
same limitation `fee_accrual.kotoba`'s README documents for its
three-way product). The illustrative values this module and its tests
use are modest (hundreds to low thousands of dollars, in cents) to stay
inside that bound with headroom — a PoC-scale limitation, not a design
claim that the formula holds for realistic fund-sized (many-million-
dollar) capital calls. Raising it would mean promoting to `i64`
arithmetic (`i64*`/`i64-` exist in the subset, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`) or restructuring the
multiply/divide order, either a follow-up, not attempted in this pass.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` —
the compiler only ever exports a 0-arity `main`, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are
passed through the guest's exported linear memory instead — the same
convention `cloud-itonami-isic-6492`'s `affordability.kotoba`,
`cloud-itonami-isic-6511`'s `underwriting_decision.kotoba` and
`cloud-itonami-isic-6630`'s `fee_accrual.kotoba` use. A host writes four
little-endian i32 values before calling `main()`:

| offset | field                | unit                                                                 |
|--------|----------------------|-----------------------------------------------------------------------|
| 0      | `commitment-amount`  | smallest currency unit (cents) — this LP's total subscribed commitment |
| 4      | `total-committed`    | cents — sum of ALL LPs' commitment-amount (host-summed; MUST be > 0)  |
| 8      | `call-amount`        | cents — the fund-wide amount this capital call is drawing down        |
| 12     | `called-amount`      | cents — this LP's cumulative amount already called before this call   |

`main()` returns `1` (this LP's recomputed allocation does NOT overcall
it) or `0` (overcalled — `trustfund.governor`'s `:allocation-mismatch`
HARD violation). All four offsets are well below `heap-base` (2048), so
they never collide with anything the compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6430/wasm/capital_call_allocation.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6430/wasm/capital_call_allocation.wasm --json
```

Fleet deployment: not attempted in this pass — see
`cloud-itonami-isic-6492`/`cloud-itonami-isic-6511` for the established
pattern.
