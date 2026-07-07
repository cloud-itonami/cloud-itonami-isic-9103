# Business Model: Botanical and zoological gardens and nature reserves activi...

## Classification

- Repository: `cloud-itonami-isic-9103`
- ISIC Rev.5: `9103`
- Activity: botanical and zoological gardens and nature reserve activities -- living-collection conservation, animal/plant welfare and public access
- Social impact: cultural/recreational access, data sovereignty, transparent audit

## Customer

- independent botanical gardens/zoos
- cooperative conservation trusts
- community nature-reserve operators

## Offer

- living-specimen intake
- care/exhibit-plan proposal
- transfer/release proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per institution
- support: monthly retainer with SLA
- migration: import from an incumbent collection-management system
- per-transfer fee

## Trust Controls

- no living specimen is transferred or released without human sign-off
  (a licensed conservationist/veterinarian)
- a fabricated jurisdiction citation, incomplete specimen evidence, a
  body-condition score outside its own species-specific healthy range,
  or an unresolved welfare (health/behavior) flag -- each forces a
  hold, not an override
- a specimen's transfer/release cannot each be finalized twice: a
  double-transfer or double-release attempt is held off this actor's
  own specimen facts alone, with no upstream comparison needed
- every intake, assessment, screening and transfer/release path is
  auditable
- emergency manual override paths remain outside LLM control
