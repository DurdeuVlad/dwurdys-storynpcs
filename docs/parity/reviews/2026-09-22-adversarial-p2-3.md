# Independent review — P2-3 adversarial transaction audit — 2026-09-22

Status: `FAIL` — read-only review; no source or documentation edits were made by the reviewer.

## Handoff

- **Lens:** Attempt duplicate, retry, crash, and identity-confusion failures across trade, bank, quest, and durable operation boundaries.
- **Evidence examined:** `StoryNpcsNetwork`, `StoryNpcsApplicationService`, `DurableOperationJournal`, `TradeStateRepository`, `TradeListing`, bank replay code, and P2-3 tests/closeout.
- **Finding:** Duplicate trade/bank packets could be dropped before canonical replay; trade screens generated fresh request IDs; listing usage was index-keyed; trade final journal commit failure can occur after external effects; quest rewards lack a durable outbox/ledger; corrupt bank/progression state has fail-open paths; paid unlock and trade payment/output still have crash windows.
- **Primary risks:** A retry can duplicate or lose value, and index changes can apply a prior use count to a different offer.
- **Fix applied:** Stable listing IDs and contract-bound journal subjects remove index identity ambiguity; request admission distinguishes new/duplicate/invalid; trade and held-item deposit retries preserve request IDs and reach canonical replay where durable handling exists.
- **Verification:** Focused `RuntimeSessionRegistryTest`, `NetworkPayloadsTest`, `TraderRoleTest`, `TradeStateRepositoryTest`, and `RoleSerdeTest` passed with `BUILD SUCCESSFUL`.
- **Unresolved:** Durable side-effect markers for trade payment/output, quest rewards, other bank actions, and fail-closed validation for every state store remain open; P2-3 stays `IN-REVIEW`.
