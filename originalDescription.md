# The Agile Monkeys - Backend

Build ReleasePilot, an internal platform that moves application versions through deployment environments (dev → staging → production). The core is the Promotion: a state machine with strict business rules. Model it with DDD, CQRS and events, in any backend stack you like.

The domain is the whole point. Keep the API thin, model the Promotion as an aggregate that guards its own rules, and ship a docker-compose.yml that starts the database and message queue it needs.

## What you'll build

### A. Domain model & invariant

Environments form an ordered pipeline (dev → staging → production). A Promotion advances one application version exactly one step, and a version must complete each environment before the next, no skipping. Only one promotion may be in progress per application + target environment; only an approver may approve; once completed or cancelled a promotion is immutable. The aggregate guards these rules itself, violating one is a domain error, never a 500.

```
dev ✓ → staging …not yet → production
RequestPromotion v1.4 · staging → production
↳ v1.4 has not completed staging
✗ EnvironmentSkipped — domain error, not a 500
```

### B. Commands

One dedicated handler per command; each transition emits a domain event.

```
RequestPromotion  → PromotionRequested
ApprovePromotion  → PromotionApproved
StartDeployment   → DeploymentStarted
CompletePromotion → PromotionCompleted
RollbackPromotion → PromotionRolledBack
CancelPromotion   → PromotionCancelled
```

### C. Queries

Read models shaped for the consumer, not the aggregate.

```
GET /promotions/:id                      → detail + state history
GET /applications/:id/status           → state per environment
GET /applications/:id/promotions  → paged history
```

### D. Ports

The platform talks to external systems. Define them as ports and stub them in-memory, no real HTTP. Where you place these interfaces matters, and you'll be asked why.

```
DeploymentPort     triggers a deployment
IssueTrackerPort    fetches linked work items
NotificationPort     alerts on terminal states
```

### E. Events & Async Processing

Publish every domain event to a queue and consume it in a decoupled handler that could be its own process. Build an Audit Log consumer that persists each event: type, promotion id, timestamp, acting user. The API responds before the consumer finishes.

```
PromotionApproved → queue → AuditLogConsumer
API responds first · consumer runs after · fire and forget
```

### F. [Optional] AI release-notes agent

When a promotion reaches Approved, trigger an agent that drafts release notes: a real tool-calling loop (not a single prompt), a mocked LLM backend is fine. Won't make up for a weak core, but attempting it well is a strong signal.

```
GetWorkItems(promotionId) → linked issue stubs
AskClarification(workItemId, question)
FlagBreakingChange(workItemId, reason)
SubmitReleaseNotes(draft) → persists the draft
```