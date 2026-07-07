# cloud-itonami-isic-9103

Open Business Blueprint for **ISIC Rev.5 9103**: Botanical and
zoological gardens and nature reserves activities. This repository
publishes a zoo/botanical-garden/conservation actor -- living-specimen
intake, jurisdiction assessment, welfare screening, specimen-transfer
finalization and specimen-release finalization -- as an OSS business
that any qualified, licensed conservation operator can fork, deploy,
run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521),
[`9321`](https://github.com/cloud-itonami/cloud-itonami-isic-9321),
[`8730`](https://github.com/cloud-itonami/cloud-itonami-isic-8730),
[`9102`](https://github.com/cloud-itonami/cloud-itonami-isic-9102)) --
a second cultural/recreational vertical (ISIC division 91) in this
fleet, alongside `9102`'s museum, but for LIVING collections rather
than static objects. Here it is **ConservationOps-LLM ⊣ Conservation
Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> specimen-intake summary, normalizing records, and checking whether a
> specimen's own body-condition score falls outside its species-
> specific healthy range -- but it has **no notion of which
> jurisdiction's wildlife/plant-conservation requirements are
> official, no license to transfer or release a real living specimen,
> and no way to know on its own whether a specimen's own welfare
> (health/behavior) flag is still unresolved**. Letting it transfer or
> release a specimen directly invites fabricated jurisdiction
> citations, a transfer or release of a specimen in a compromised
> health state, and an unresolved welfare concern being quietly signed
> off -- and liability, and animal/plant welfare risk, for whoever runs
> it. This project seals the ConservationOps-LLM into a single node
> and wraps it with an independent **Conservation Governor**, a human
> **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers living-specimen intake through jurisdiction
assessment, welfare screening, specimen-transfer finalization and
specimen-release finalization. It does **not**, by itself, hold any
license required to operate a zoo/botanical garden/nature reserve in a
given jurisdiction, and it does not claim to. It also does **not**
model a full veterinary/husbandry program -- no clinical diagnostics,
no breeding-program/studbook management, no habitat-design engineering
(see `conservation.registry/body-condition-out-of-range?`'s own
docstring for the honest simplification this makes: a single
representative body-condition-scoring range, not a full veterinary
assessment). Whoever deploys and operates a live instance (a licensed
conservation operator) supplies any jurisdiction-specific license, the
real veterinary/conservation expertise and the real collection-
management-system integrations, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited
execution scaffold so that operator does not have to build the
compliance layer from scratch for every new market.

### Actuation

**Transferring or releasing a real living specimen is never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`conservation.governor`'s `:actuation/transfer-
specimen`/`:actuation/release-specimen` high-stakes gate and
`conservation.phase`'s phase table, which never puts `:specimen/
transfer`/`:specimen/release` in any phase's `:auto` set) -- see
`conservation.phase`'s docstring and `test/conservation/phase_test.
clj`'s `specimen-transfer-never-auto-at-any-phase`/`specimen-release-
never-auto-at-any-phase`. The actor may draft, check and recommend; a
human conservationist/veterinarian is always the one who actually
transfers or releases a specimen. Like `6512`/`6622`/`6520`/`6530`/
`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/`9102`, this actor has
TWO actuation events.

## The core contract

```
specimen intake + jurisdiction facts (conservation.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Conservation-│ ─────────────▶ │ Conservation                │  (independent system)
   │ Ops-LLM      │  + citations    │ Governor: spec-basis ·      │
   │ (sealed)     │                 │ evidence-incomplete ·        │
   └──────────────┘         commit ◀────┼──────────▶ hold │ body-condition-
                                 │             │           │ out-of-range (per-
                           record + ledger  escalate ─▶ human   entity two-sided
                                             (ALWAYS for         range) ·
                                              :specimen/            welfare-flag-unresolved
                                              transfer /              (unconditional) ·
                                              :specimen/release)        already-finalized
```

**The ConservationOps-LLM never transfers or releases a specimen the
Conservation Governor would reject, and never does so without a human
sign-off.** Hard violations (fabricated jurisdiction requirements;
unsupported specimen evidence; a body-condition score outside its own
species-specific healthy range; an unresolved welfare flag; a double
transfer or release) force **hold** and *cannot* be approved past; a
clean transfer/release proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean lifecycles (specimen transfer, specimen release) + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a habitat-monitoring robot
supports physical animal/plant welfare checks, under the actor, gated
by the independent **Conservation Governor**. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions require
human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Conservation Governor, specimen-transfer + specimen-release draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`9103`). Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/
`9321`/`8730`/`9102`, this vertical's specimen records are practice-
specific rather than a shared cross-operator data contract, so
`conservation.*` runs on the generic identity/forms/dmn/bpmn/audit-
ledger stack only -- no bespoke domain capability lib to reference at
all.

## Layout

| File | Role |
|---|---|
| `src/conservation/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate specimen-transfer/specimen-release history. No dynamically-filed sub-record -- both actuation ops act directly on a pre-seeded specimen, and the double-transfer/double-release guards check dedicated `:transfer-finalized?`/`:released?` booleans rather than a `:status` value |
| `src/conservation/registry.cljc` | Specimen-transfer + specimen-release draft records, plus `body-condition-out-of-range?`/`bcs-min-healthy`/`bcs-max-healthy` -- the SECOND check in this fleet to combine BOTH directions in ONE check (established by `testlab.registry/within-tolerance?`), and the first to apply per-ENTITY species-specific acceptance bounds rather than per-test-protocol bounds |
| `src/conservation/facts.cljc` | Per-jurisdiction wildlife/plant-conservation catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/conservation/conservationopsllm.cljc` | **ConservationOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/welfare-screening/specimen-transfer/specimen-release proposals |
| `src/conservation/governor.cljc` | **Conservation Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · body-condition-out-of-range, pure ground-truth two-sided range recompute · welfare-flag-unresolved, unconditional evaluation, the TWELFTH grounding of this discipline) + already-transferred/already-released guards + 1 soft (confidence/actuation gate) |
| `src/conservation/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (both transfer and release always human; specimen intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/conservation/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/conservation/sim.cljc` | demo driver |
| `test/conservation/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers living-specimen intake through jurisdiction
assessment, welfare screening, specimen-transfer finalization and
specimen-release finalization -- the core governed lifecycle this
blueprint's own `docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Specimen intake + per-jurisdiction wildlife/plant-conservation checklisting, HARD-gated on an official spec-basis citation (`:specimen/intake`/`:jurisdiction/assess`) | A full veterinary/husbandry program (clinical diagnostics, breeding-program/studbook management, habitat-design engineering -- see `body-condition-out-of-range?`'s docstring) |
| Welfare screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:welfare/screen`) | Real collection-management-system integration, public-education/visitor-experience workflows |
| Specimen-transfer finalization, HARD-gated on the specimen's own body-condition score falling within its species-specific healthy range and a double-transfer guard (`:specimen/transfer`) | Ongoing veterinary-care workflows themselves |
| Specimen-release finalization, HARD-gated on the specimen's welfare flag being resolved and a double-release guard (`:specimen/release`) | |
| Immutable audit ledger for every intake/assessment/screening/transfer/release decision | |

Extending coverage is additive: add the next gate (e.g. a CITES-permit-
validity check) as its own governed op with its own HARD checks and
tests, following the SAME "an independent governor re-verifies against
the actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`conservation.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `conservation.facts/catalog`
-- currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `conservation.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `ConservationOps-LLM` + `Conservation Governor` run
as real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the twenty-two
prior actors' architecture. See `docs/adr/0001-architecture.md` for
the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
