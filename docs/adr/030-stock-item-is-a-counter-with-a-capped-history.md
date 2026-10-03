# ADR-030: A Stock Item Is a Counter With a Capped History

## Status
Accepted

## Context
Organisations keep consumables and spare parts (toner, cables, spare SSDs) and need to know how many they have, what moved, and
what is running low. The question is how to represent the quantity.

## Decision
- The Control Record is the **`StockItem`**: a per-tenant `sku` (normalised to upper case, unique per organisation), a name, a
  unit (`unit`, `box`, `metre`...), a **quantity on hand**, a **reorder level** and a status. The quantity is a **counter stored on
  the item**, not a sum of movements recomputed on every read.
- Stock moves through three named Behavior Qualifiers, not a generic "change quantity" endpoint, because they are different
  business acts with different rules:
  - `movement/receive`: adds a positive quantity;
  - `movement/issue`: takes a positive quantity, never below zero;
  - `movement/adjust`: sets the quantity to a **counted value** and **requires a reason** (an inventory correction is the one
    act that must always explain itself).
- Every change appends a `StockEntry` to the item's **history** (what, who, signed change, balance afterwards, reason). The history
  is embedded in the item's document so the balance and its history are written by **one atomic update** and can never diverge.
  An embedded list would grow without bound, so it is **capped at the newest 1000 entries** (`$push` with `$slice`). It is a
  recent-activity view, not a permanent ledger: anyone who needs a permanent, tamper-evident record already has the compliance
  audit ledger, which the gateway feeds with every mutating call.
- `reorderLevel` drives `belowReorderLevel` (quantity **at or under** the level) and the `low-stock/retrieve` query. A discontinued
  item is not listed there.
- Lifecycle: `ACTIVE -> DISCONTINUED` (terminal). A discontinued item keeps its balance and history and accepts no change.
- No money: no unit prices, valuation or purchasing. No DELETE: nothing is physically removed.
- The reason on a movement is free text that lands in the history: it must not carry personal data (the same standing rule as the
  rest of the platform's records).

## Consequences
- Positive: reads are one document; the balance is always consistent with the history that explains it; the cap bounds document size.
- Negative: history older than 1000 entries per item is dropped from this view (it remains on the compliance ledger); a quantity
  cannot be reconstructed from movements alone beyond that window, which is why the counter, not the history, is the source of truth.
