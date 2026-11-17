# ADR 033: Discontinuing is a guarded write

## Status
Accepted (2026-11-17). Found by the platform race audit (ACTION-PLAN 0.5, `thinklab-platform/docs/race-audit.md`).

## Context
Stock movements were already guarded (an issue never goes below zero, and an adjustment only applies if the balance is what the caller saw). Discontinuing
an item was not: it validated `ACTIVE -> DISCONTINUED` in the domain and wrote the status by `_id` only. Measured by `TransitionRaceIT` on the old
code: ten discontinuations of one item at once all answered OK and left ten entries in its history.

## Decision
`updateStatus` is a **guarded write**: the only transition is `ACTIVE -> DISCONTINUED`, so it applies only to an item that is still `ACTIVE`; a miss is
`409 ERR-STK-00409` when the item exists and `404` when it does not.

## Consequences
- **Positive:** one winner per race and one history entry.
- **Not changed:** `updateDetails` (name, unit, reorder level) is a whole-field replace by `_id` and stays last-write-wins: the fields are independent of the balance and of the lifecycle.
