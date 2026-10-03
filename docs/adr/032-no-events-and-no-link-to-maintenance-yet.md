# ADR-032: No Events and No Link to Hardware Maintenance Yet

## Status
Accepted

## Context
Two natural extensions were considered for the first version: telling someone when stock runs low, and having a repair work order
draw its parts from the stock automatically.

## Decision
- **Low stock is a query, not a notification.** `low-stock/retrieve` and the `belowReorderLevel` flag are enough for the web app and
  for a person; there is no outbox, no NATS publication and nothing that consumes one today. Adding events later is additive.
- **Hardware maintenance is not linked.** `it-hardware-maintenance` already records the parts of a work order; having it draw them
  from this stock would touch a public, fully covered service and create a runtime dependency between the two. Until a real need
  appears, an operator issues the parts here by hand (the `reason` can name the work order, not a person).
- The service calls no other service except the Hash Token Registry for sovereign ids.

## Consequences
- Positive: small surface, nothing existing changes, no new failure modes for the services around it.
- Negative: parts used in repairs are issued from stock by hand; nobody is alerted automatically that a reorder level was crossed.
  Both are deliberate follow-ups, to be built when someone asks for them.
