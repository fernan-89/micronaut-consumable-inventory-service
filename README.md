# micronaut-consumable-inventory-service

BIAN-aligned Service Domain **consumable-inventory** (Control Record: `StockItem`), port `8096`.

How many of each consumable or spare part (toner, cables, spare SSDs) an organisation has, what moved, and what is running low. A stock
item has a SKU (unique per organisation), a unit, a quantity on hand, a reorder level and a status; stock moves by `receive`, `issue`
and `adjust` (ADR-030).

## What it guarantees, and what it does not

- **The balance never goes negative.** An issue larger than what is on hand is refused (409). The guard is in the database write
  itself, so two people issuing the last units at the same moment cannot both succeed (ADR-031).
- **An adjustment always explains itself.** `movement/adjust` sets the quantity to a counted value and requires a reason; it only
  applies to the quantity the caller saw, so a stale count changes nothing.
- **One SKU per organisation**, held by a unique index (ADR-031). Another tenant's item answers 404.
- **The history is recent activity, not a permanent ledger.** Each item keeps its newest 1000 entries; the compliance audit ledger
  (fed by the gateway) is the permanent record (ADR-030).
- **No money, no events, no link to repairs yet.** No prices or purchasing; low stock is a query, not a notification; a repair
  work order does not draw from stock automatically (ADR-032).

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call and scopes it; `X-Executor` is mandatory on every change.

### StockItem - `/consumable-inventory/v1`

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /consumable-inventory/v1/initiate` `{"sku":"toner-85a","name":"HP 85A toner","unit":"unit","reorderLevel":3,"initialQuantity":10}` |
| retrieve | `GET /consumable-inventory/v1/{id}/retrieve` |
| retrieve (collection) | `GET /consumable-inventory/v1/retrieve?status=&sku=` (by SKU) |
| low-stock/retrieve | `GET /consumable-inventory/v1/low-stock/retrieve` (ACTIVE items at or under their reorder level) |
| update | `PUT /consumable-inventory/v1/{id}/update` `{"name":"...","unit":"...","reorderLevel":5}` |
| movement/receive | `PUT /consumable-inventory/v1/{id}/movement/receive` `{"quantity":5,"reason":"delivery"}` |
| movement/issue | `PUT /consumable-inventory/v1/{id}/movement/issue` `{"quantity":2,"reason":"printer 3F"}` |
| movement/adjust | `PUT /consumable-inventory/v1/{id}/movement/adjust` `{"newQuantity":7,"reason":"recount"}` |
| control/discontinue | `PUT /consumable-inventory/v1/{id}/control/discontinue` (ACTIVE -> DISCONTINUED, terminal) |
| history/retrieve | `GET /consumable-inventory/v1/{id}/history/retrieve` (newest first) |

```bash
curl http://localhost:8096/consumable-inventory/v1/low-stock/retrieve -H "X-Tenant-Id: <organisationId>"
# [{"sku":"TONER-85A","name":"HP 85A toner","unit":"unit","onHand":2,"reorderLevel":3,"belowReorderLevel":true,"status":"ACTIVE",...}]
```

Do not put personal data in a movement's `reason`: it is stored in the item's history.

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-STK-00404` | 404 | Stock item not found (another tenant's item answers the same) |
| `ERR-STK-00409` | 409 | Duplicate SKU, an issue larger than the balance, a change on a discontinued item, or the item changed while the movement was applied (retry) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure (bad SKU, non-positive quantity, missing adjustment reason...) |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
