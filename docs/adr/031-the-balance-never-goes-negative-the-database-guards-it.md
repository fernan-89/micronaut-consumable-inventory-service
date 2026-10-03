# ADR-031: The Balance Never Goes Negative, and the Database Is the Guard

## Status
Accepted

## Context
Two people issuing the last two units at the same moment must not both succeed, and two people registering the same SKU at the same
moment must not both succeed. A use case that reads the item, checks, then writes is not atomic, so the check alone cannot be trusted.

## Decision
- **Issuing more than is on hand is refused (`409 ERR-STK-00409`)**; the stock never goes negative. If the physical count is higher
  than the system's, the answer is an `adjust` (with its mandatory reason), not a negative balance that hides a counting error.
- The use cases **load the item first** and let the aggregate validate the move (ACTIVE, positive quantity, enough on hand), so a
  refused move never writes and the error names the real cause. The **write is conditional**, carrying its guard in the filter:
  - an issue (`applyDelta` with a negative delta) only matches while the item is still `ACTIVE` **and** `onHand >= quantity`, then
    `$inc`s;
  - a receipt only matches while the item is still `ACTIVE`;
  - an adjustment only matches while the item is still `ACTIVE` **and** `onHand` is still the value the caller saw (compare-and-set).
  When nothing matches, the repository answers `false` and the use case raises `StockChangedException` (`409`, safe to retry): a
  concurrent writer got in between and **nothing was written**. A lost update and a negative balance are both impossible by
  construction, not by convention.
- **SKUs are unique per organisation** by a unique index on `(organisationId, sku)`. The use case does not pre-check: the index is
  the arbiter and the repository turns its duplicate-key error into `409`, so concurrent registrations leave exactly one. The index
  is created at startup, fail-open like the kit's initializer (`thinklab.mongo.create-indexes=false` for unit-test contexts).
- A stock item is only read or changed on behalf of its own tenant; another tenant's id answers `404`, like a missing one.
- `low-stock/retrieve` compares two fields of the same document (`onHand <= reorderLevel`) with a Mongo expression filter, scoped to
  the tenant and to ACTIVE items.

## Consequences
- Positive: correct under concurrency without locks or a coordinator; refusals are specific and safe to retry; proven against a real
  MongoDB by the integration test (twelve concurrent issues of one unit against five units leave exactly five issued).
- Negative: under heavy contention on one item some callers get `StockChangedException` and must retry; the expression filter for
  low stock is not index-assisted (fine for the item counts of an IT estate, revisit if a tenant holds millions of SKUs).
