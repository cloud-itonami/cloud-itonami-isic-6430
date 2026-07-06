# Business Model: Trusts, funds and similar financial entities

## Classification

- Repository: `cloud-itonami-isic-6430`
- ISIC Rev.5: `6430`
- Activity: administration of trusts, funds and similar financial entities that pool investor capital on behalf of shareholders/beneficiaries
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- independent trust administrators
- community investment pools
- cooperative fund vehicles

## Offer

- beneficiary/unitholder (LP) subscription-agreement intake, HARD-gated
  on accreditation
- capital-call NOTICE issuance off an upstream investment-actor
  (`cloud-itonami-isic-6499`) capital-call proposal, independently
  re-verified against this vehicle's own subscription ledger before any
  notice is ever sent to LPs
- LP-distribution recording off an upstream investment-actor exit-
  distribution fact -- since that fact carries only a fund-wide total
  (no per-LP claim to verify), this vehicle independently COMPUTES the
  authoritative per-LP split itself and HARD-holds if no subscriptions
  exist or the same distribution would be recorded twice
- NAV/valuation disclosure off an upstream investment-actor NAV/capital-
  account report -- since NAV and ownership shares depend on portfolio
  data this vehicle does not have, it can only independently verify each
  LP's called-amount against its own ledger and that every named LP has
  a subscription on file
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per fund/trust vehicle
- support: monthly retainer with SLA
- migration: import from an incumbent fund-administration system
- NAV-calculation fee

## Trust Controls

- no capital-call notice is issued to LPs, no distribution is recorded,
  and no NAV is disclosed, without human (trustee/fund officer)
  sign-off
- an unaccredited subscriber, a capital-call notice referencing an LP
  with no subscription on file, a notice whose upstream allocation does
  not match this vehicle's own independent recomputation, a distribution
  recorded against zero subscriptions, a double-recording of the same
  upstream distribution, a NAV disclosure naming an LP with no
  subscription on file, or a NAV disclosure whose called-amount does not
  match this vehicle's own ledger -- each forces a hold, not an override
- every subscription/notice/distribution/disclosure path is auditable
- emergency manual override paths remain outside LLM control
