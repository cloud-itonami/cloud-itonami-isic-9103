# ADR-0001: cloud-itonami-isic-9103 -- ConservationOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102` ADR-0001s (the pattern this
  ADR ports); ADR-2607071250/ADR-2607071320/ADR-2607071351/
  ADR-2607071618/ADR-2607071640/ADR-2607071654/ADR-2607071717/
  ADR-2607071732/ADR-2607071752/ADR-2607071819/ADR-2607071849/
  ADR-2607071922/ADR-2607072715/ADR-2607072730 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`, the fourteen verticals built outside ADR-2607032000's
  original insurance/real-estate batch -- this is the fifteenth)
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9102`, this ADR deepens `cloud-itonami-
  isic-9103` (botanical and zoological gardens and nature reserves
  activities) from `:blueprint` to `:implemented`, the twenty-third
  actor in this fleet -- a SECOND cultural/recreational vertical (ISIC
  division 91) alongside `9102`'s museum, but for LIVING collections
  rather than static objects.

## Problem

A zoo/botanical garden's specimen-transfer/specimen-release workflow
bundles several distinct concerns under one governed workflow:

1. **Jurisdiction wildlife/plant-conservation correctness** -- an
   official spec-basis citation from a real regulator (環境省/U.S. Fish
   and Wildlife Service's Division of Management Authority/the Animal
   and Plant Health Agency/the Bundesamt für Naturschutz), never
   fabricated.
2. **Body-condition sufficiency** -- does a specimen's own body-
   condition score fall within its own species-specific healthy range?
   The SECOND check in this fleet to combine BOTH directions in ONE
   check (`testlab.registry/within-tolerance?` established the first),
   and the FIRST to apply per-ENTITY species-specific acceptance
   bounds rather than per-test-protocol bounds.
3. **Welfare-flag resolution verification** -- has a specimen's own
   welfare (health/behavior) flag actually been resolved before either
   a transfer or a release is finalized? The conservation-specific
   reuse of the unconditional-evaluation screening discipline this
   fleet's `casualty.governor/sanctions-violations` originally
   established -- a TWELFTH distinct grounding.
4. **Real, high-stakes actuation, twice** -- transferring a real
   living specimen and releasing a real living specimen are two
   independently-gated real-world acts on the SAME entity.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a zoo/botanical garden with an LLM" but
"seal the LLM inside a trust boundary and layer evidence-sufficiency,
body-condition verification, welfare-flag-resolution verification,
audit and human-approval on top of it, while structurally fixing both
real actuation events as human-only."

## Decision

### 1. ConservationOps-LLM is sealed into the bottom node; it never transfers or releases directly

`conservation.conservationopsllm` returns exactly five kinds of
proposal: intake normalization, jurisdiction wildlife/plant-
conservation checklist, welfare screening, specimen-transfer draft,
and specimen-release draft. No proposal writes the SSoT or commits a
real transfer/release directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 conservation operation

`conservation.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. `body-condition-out-of-range?` extends the two-sided range-check family to per-entity species-specific bounds

`testlab.registry/within-tolerance?` established the FIRST check in
this fleet to combine BOTH directions (minimum and maximum) in one
check, comparing a measured value against a per-test-protocol
acceptance range. `body-condition-out-of-range?` is the SECOND
instance of that shape, but generalizes it: the acceptance bounds
(`:bcs-min-healthy`/`:bcs-max-healthy`) live on the specimen ITSELF
rather than on a shared protocol record, since different species
genuinely have different healthy body-condition ranges -- an honest,
domain-authentic generalization (the same kind of extension
`parksafety.registry/operators-sufficient?` made to the MINIMUM-
threshold family, generalizing it from field-vs-constant to field-vs-
field on the same entity).

### 4. Welfare-flag-unresolved screening reuses the unconditional-evaluation discipline for a twelfth distinct grounding

`welfare-flag-unresolved-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for `:welfare/screen`, `:specimen/transfer` AND `:specimen/
release` -- the TWELFTH distinct application of this exact discipline
in this fleet, and the third (after `eldercare` and `museum`) to gate
BOTH actuation ops of a dual-actuation actor off the same unresolved-
flag concept.

### 5. The unconditional-evaluation check is tested via the SCREENING op directly, per the lesson already recorded by `parksafety`/`eldercare`/`museum`

`welfare-flag-unresolved-is-held-and-unoverridable` calls `:welfare/
screen` directly against `specimen-4` (an unresolved welfare flag),
NOT `:specimen/transfer`/`:specimen/release` against an un-screened
specimen -- because a failing screen is itself a HARD hold whose
payload never persists to the store, so the actuation ops alone could
never discover the bad ground-truth flag through this check family
without the screening op having actually been run first. This build
applied that lesson PROACTIVELY for a third consecutive vertical
(after `eldercare` and `museum`), further reinforcing that lessons
recorded in this fleet's ADRs transfer forward reliably.

### 6. Dual actuation, matching `6512`/`6622`/`6520`/`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/`9102`'s shape

`conservation.governor`'s `high-stakes` set has exactly two members
(`:actuation/transfer-specimen`, `:actuation/release-specimen`), each
acting on the SAME specimen entity, each with its OWN history
collection (`transfer-history`/`release-history`), sequence counter
and dedicated double-actuation-guard boolean.

### 7. Double-transfer/double-release guards check dedicated booleans, not `:status`

`already-transferred-violations`/`already-released-violations` check
`:transfer-finalized?`/`:released?`, dedicated booleans set once and
never cleared, rather than a `:status` value that could legitimately
advance past a checked state (the exact trap `cloud-itonami-isic-
6492`'s ADR-0001 documents in detail, explicitly avoided BY DESIGN in
every sibling actor's equivalent guard since). This actor's `:status`
never needs to encode "has this actuation already happened" at all --
a deliberate architectural choice applied here for a thirteenth
consecutive time.

### 8. No bespoke capability lib

Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
`8730`/`9102`, and unlike most other actors in this fleet, this
vertical's specimen records are practice-specific rather than a shared
cross-operator data contract -- `conservation.*` runs on the generic
identity/forms/dmn/bpmn/audit-ledger stack only, per the blueprint's
own explicit statement.

## Consequences

- (+) Zoo/botanical-garden conservation gets the same governed,
  auditable-actor treatment as the twenty-two prior actors, extending
  the pattern to a second cultural/recreational vertical (ISIC
  division 91) alongside `9102`'s museum, but for LIVING collections
  rather than static objects.
- (+) `body-condition-out-of-range?` is a genuine structural
  contribution: it generalizes the two-sided range-check family from
  per-protocol acceptance bounds to per-entity species-specific
  acceptance bounds.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/conservation/phase_test.clj`'s `specimen-
  transfer-never-auto-at-any-phase`/`specimen-release-never-auto-at-
  any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  conservation/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern every sibling actor uses.
- (+) The welfare-flag-unresolved test/demo again correctly applied
  the established SCREENING-op-directly pattern for a third
  consecutive vertical after `eldercare` and `museum` -- further
  evidence that lessons recorded in this fleet's ADRs continue to
  transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `conservation.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `body-condition-out-of-range?` models only a single
  representative body-condition-scoring range per specimen, not a full
  veterinary/husbandry program (clinical diagnostics, breeding-
  program/studbook management, habitat-design engineering are out of
  scope -- see that fn's own docstring); real collection-management-
  system integration and ongoing veterinary-care workflows are all out
  of scope for this OSS actor -- each operator's responsibility (see
  README's coverage table).
- 38 tests / 176 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All fourteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`; mixing a different sub-domain into any would blur scope boundaries even where the ISIC division (91) overlaps with `9102` |
| Keep `cloud-itonami-isic-9103` at `:blueprint` only | ❌ | The standing direction continues past `9102`; living-collection conservation is a natural, well-precedented next domain, deepening this fleet's cultural/recreational coverage with a genuinely different entity type (living specimens vs. static artifacts) |
| Model `body-condition-out-of-range?` as a repeat of `operators-sufficient?`'s field-vs-field shape rather than an extension of `within-tolerance?`'s two-sided range shape | ❌ | The actual comparison is a range check (score must fall between two bounds), not a single greater-than-or-equal comparison between two fields -- honestly framing this as an extension of the range-check family, not the threshold family, keeps the fleet's check-family taxonomy accurate |
| Test `welfare-flag-unresolved-violations` via an actuation op against an un-screened specimen (the shape `parksafety`'s ORIGINAL, buggy test used) | ❌ | Already proven wrong by `parksafety`'s ADR-2607071922 Decision 5 and reconfirmed by `eldercare`'s and `museum`'s ADR-0001s -- a failing screen never persists its payload to the store, so the actuation op alone cannot discover the bad ground-truth flag through this check family; this build tested the SCREENING op directly from the start |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/conservation`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning established by every "no bespoke capability lib" sibling's ADR |
