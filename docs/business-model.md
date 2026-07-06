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
- NAV/valuation disclosure proposal (blueprint-stage; not yet a governed op)
- distribution proposal (blueprint-stage; not yet a governed op)
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per fund/trust vehicle
- support: monthly retainer with SLA
- migration: import from an incumbent fund-administration system
- NAV-calculation fee

## Trust Controls

- no capital-call notice is issued to LPs without human (trustee/fund
  officer) sign-off; no distribution is disbursed and no NAV is published
  without human sign-off either, once those ops are implemented
- an unaccredited subscriber, a capital-call notice referencing an LP
  with no subscription on file, or a notice whose upstream allocation
  does not match this vehicle's own independent recomputation forces a
  hold, not an override
- every subscription/notice path is auditable
- emergency manual override paths remain outside LLM control
