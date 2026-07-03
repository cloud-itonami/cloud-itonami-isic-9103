# cloud-itonami-9103

Open Business Blueprint for **ISIC Rev.5 9103**: Botanical and zoological gardens and nature reserves activi....

This repository designs a forkable OSS business for botanical and zoological gardens and nature reserve activities -- living-collection conservation, animal/plant welfare and public access -- run by a qualified, licensed operator so a community or
independent operator never surrenders patron/collection data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a habitat-monitoring robot supports physical animal/plant welfare checks,
under an actor that proposes actions and an independent **Conservation Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + operational records
        |
        v
ConservationOps-LLM -> Conservation Governor -> hold, proceed, or human approval
        |
        v
operational ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: transferring or releasing a living specimen.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`9103`). This vertical's operational records are practice-specific
rather than a shared cross-operator data contract, so it runs on the generic
identity/forms/dmn/bpmn/audit-ledger stack -- no bespoke domain capability lib.

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`ConservationOps-LLM` + `Conservation Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
