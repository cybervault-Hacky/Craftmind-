# Marketplace trust, disputes, and creator protection (Phase 30)

Phase 30 adds a **non-monetary** trust layer on top of the existing marketplace: buyer/creator **reports**,
**order disputes**, and a self-service **avoid/block list**. It extends the Phase 23–27 marketplace services and the
Phase 19/20 audit, rate-limit, and authorization machinery; it introduces no second stack and no new dependency. It is
explicitly **not** payments. The deferred monetization phases stay deferred — there is no refund, payout, commission,
escrow, settlement, invoice, or tax anywhere, and no plan, credit, or status is purchasable.

This module closes exactly the gap Phase 27 left open on purpose. `order-lifecycle.js` and the website both state that
closing an order that already has approved milestones "belongs to a future dispute phase." Phase 30 is that phase,
implemented without moving money: a dispute can freeze closure and, **only on two-sided agreement**, close an order as
`CANCELLED`.

## Scope

| Surface | Endpoints | Effect |
| --- | --- | --- |
| Reports | `POST /marketplace/reports`, `GET /marketplace/reports`, `POST /marketplace/reports/:id/withdraw`, `GET /marketplace/reports/about-me` | Flag a `LISTING`, a `JOB`, or the counterparty of your own `ORDER`. Never changes money; the subject sees a stripped view. |
| Trust posture | `GET /marketplace/trust` | The caller's own counts: open reports received/filed, blocks placed/against. Self-visible only; **not** a public score. |
| Blocks (creator protection) | `POST /marketplace/blocks`, `POST /marketplace/blocks/remove`, `GET /marketplace/blocks` | A directed block between two accounts derived from a one-to-one relationship the caller holds (an order, or a proposer on one of their jobs). Its only effect: a blocked pair can no longer form new proposals. |
| Disputes | `POST /orders/:id/disputes`, `GET /orders/:id/disputes`, `GET /orders/:id/disputes/:did`, `POST /orders/:id/disputes/:did/statements`, `POST /orders/:id/disputes/:did/position`, `POST /orders/:id/disputes/:did/withdraw`, `GET /marketplace/disputes` | An order-scoped, participants-only workflow: append-only immutable statements, two-of-two resolution (`CONTINUE` or `CLOSE`), opener-only withdrawal. |

Every endpoint resolves the acting account from the **session token**, never the body. The strict body reader refuses
unknown keys, so a client cannot supply a `reporterUserId`, `targetUserId`, `blockedUserId` (on create), a dispute
`status`/`outcome`/`role`, or any amount — the server derives all of it.

## Invariants (server-authoritative, honest by construction)

- **Server decides identity.** A report's target is resolved from the subject row at write time and stored as
  `target_user_id`: a listing's creator, a job's buyer, or the *other participant* of an order (only that order's
  participants may report it). A `CHECK (target_user_id <> reporter_user_id)` makes self-reports unrepresentable, and
  "about me" is a direct indexed read on `target_user_id` — so a filer never sees their own accusation back in their
  inbox. A block's counterparty is resolved from an order or a proposal the caller genuinely holds; an arbitrary
  account is unreachable by design.
- **Reports protect the reporter.** The party a report is about sees only `category`, `subjectType`, `subjectId`, and
  `status` — **never** who filed it and **never** the free-text note. Attribution of accusations would turn a safety
  tool into a harassment tool. The reporter always sees their own full submission. The audit record carries ids and a
  category only; the note is never stored in the log.
- **A dispute needs two, not one.** Either participant may record `CONTINUE` or `CLOSE`. The dispute resolves when
  both have recorded a position **and they match**: two `CONTINUE` close the dispute and leave the order `ACTIVE`;
  two `CLOSE` close the dispute and the order (`CANCELLED`, `cancelled_at` set, all history preserved). A mixed pair
  stays `OPEN`. The opener may `withdraw` at any time, which unblocks the order without closing it. A non-participant
  receives the uniform not-found whether or not a dispute exists.
- **An open dispute freezes closure, not work.** `completeOrder` and `cancelOrder` refuse with `ORDER_DISPUTED` (409)
  while a dispute is open; milestones and deliveries keep flowing so the parties can converge. The guard is an inert
  read (`hasOpenDisputeForOrder`) — an undisputed order completes and cancels exactly as in Phase 27.
- **No money, ever.** The one effect a dispute has on an order is a status transition to `CANCELLED` on mutual
  agreement. There is no refund, payout, commission, escrow, or settlement surface, and none is implied by any
  response. Order amounts stay the Phase 27 snapshot and are never edited.
- **Append-only evidence.** `order_dispute_statements` are immutable versions (the Phase 27 delivery discipline);
  database `BEFORE UPDATE`/`BEFORE DELETE` triggers enforce immutability as well as the service.
- **One audit log, one rate limiter, one control plane.** Phase 30 adds twelve audit action types through the same
  shadow-table rebuild that widened the log for Phases 19–27, and two dedicated rate budgets (`trustWrite`,
  `disputeWrite`) so a burst in one trust category cannot spend another's. No new developer tool is added: platform
  moderation continues to use the existing Phase 19/23 tools (`suspend_creator`, `archive_server`, listing archive);
  the report/dispute tables expose nothing secret to review them.

## Schema (SQLite v11 — additive and data-preserving)

Five new tables, none touching an existing row:

- `marketplace_reports(report_id, reporter_user_id, target_user_id, subject_type, subject_id, category, note, status,
  created_at, updated_at, withdrawn_at)` — `target_user_id` is the resolved counterparty written by the service, with
  a partial unique index enforcing **one OPEN report per reporter+subject**, a `CHECK (target_user_id <>
  reporter_user_id)` forbidding self-reports, indexes on target and reporter, and a CHECK that keeps `withdrawn_at`
  consistent with `status`.
- `marketplace_blocks(blocker_user_id, blocked_user_id, reason, created_at)` — composite PK, `CHECK` no self-block.
- `order_disputes(dispute_id, order_id, opened_by, other_participant, reason_category, reason, status, outcome,
  statement_count, created_at, updated_at, resolved_at)` — partial unique index enforcing **one OPEN dispute per
  order**, CHECKs binding `status`/`outcome`/`resolved_at` and capping `statement_count` at 12.
- `order_dispute_statements(statement_id, dispute_id, order_id, author_user_id, version, body, created_at)` —
  `UNIQUE(dispute_id, version)` plus append-only triggers.
- `order_dispute_positions(dispute_id, participant_user_id, position, created_at, updated_at)` — one row per party,
  upserted; resolution is recomputed from these rows, never from a request.

`admin_audit_log` is rebuilt once more (the v6–v10 pattern) so its `action_type` CHECK accepts the twelve new actions:
`REPORT_FILED`, `REPORT_WITHDRAWN`, `REPORT_ACCESS_DENIED`, `BLOCK_ADDED`, `BLOCK_REMOVED`, `BLOCK_ENFORCED`,
`DISPUTE_OPENED`, `DISPUTE_STATEMENT_ADDED`, `DISPUTE_POSITION_SET`, `DISPUTE_WITHDRAWN`, `DISPUTE_RESOLVED`,
`DISPUTE_ACCESS_DENIED`. `REGISTERED_AUDIT_ACTION_TYPES` grows from 90 to 102; `SCHEMA_VERSION` from 10 to 11.

## Deliberately out of scope

- Payments, commissions, refunds, and payouts (deferred Phase 28) — untouched; a dispute can close an order but never
  settles a debt.
- Purchasable memberships, credit purchases, and the **Trusted Seller** marker (deferred Phase 29) — untouched. This
  phase mints no badge and grants no plan. (The non-purchasable Phase 22 membership/credit *ledger* already exists and
  is unaffected.)
- Public trust scores, aggregate "verified seller" reputation, reviews with stars, and follower graphs — not
  implemented and not fabricated. The trust summary is a private, self-visible count, nothing more.
- A dedicated developer dispute/report review UI — not added, to keep the phase focused; the existing audit log and
  moderation tools remain the platform surface.

## Verification

`cd backend && npm test` → **306/306 passed across 53 suites** (Node v22.22.3), including the new
`marketplace-trust.test.js` (12) and `order-disputes.test.js` (16). The full existing suite is unchanged and green.
`python3 scripts/check_website.py` → PASS (41 pages — no website change was required), `python3
scripts/check_release_config.py` → PASS, `node --check` on every module → PASS, `git diff --check` clean. No live
deployment, external security review, browser/device test, or payment-provider call is claimed; there is no browser
E2E or Android build in this environment.
