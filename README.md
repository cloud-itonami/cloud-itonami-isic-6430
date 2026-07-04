# cloud-itonami-isic-6430

Open Business Blueprint for **ISIC Rev.5 6430**: Trusts, funds and similar financial entities.

This repository designs a forkable OSS business for administration of trusts, funds and similar financial entities that pool investor capital on behalf of shareholders/beneficiaries -- run by a qualified, licensed operator so a community or
independent professional never surrenders customer data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a secure document-custody robot manages physical trust-deed/fund-prospectus custody,
under an actor that proposes actions and an independent **Trust/Fund Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + case/account records
        |
        v
TrustAdmin-LLM -> Trust/Fund Governor -> hold, proceed, or human approval
        |
        v
case/account ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: disbursing a distribution or publishing a NAV used for real transactions.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6430`). Required capabilities are implemented by:

- [`kotoba-lang/securities`](https://github.com/kotoba-lang/securities)
  -- position, trade, fund-NAV and mandate contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`TrustAdmin-LLM` + `Trust/Fund Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
