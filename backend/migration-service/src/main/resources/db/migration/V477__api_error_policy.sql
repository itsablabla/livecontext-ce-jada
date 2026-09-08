-- errorPolicy: per-API rules that turn a provider's refusal into an action.
--
-- Two things were impossible before this column. A provider that answers 429 (or 503 with a
-- Retry-After) was never retried, so a throttled key failed the step instead of waiting the
-- handful of seconds the provider itself asked for. And a refusal that only a human can act on
-- ("your app is not audited, only SELF_ONLY is allowed", "daily post limit reached") reached the
-- reader as the raw provider body, which reads like a platform bug.
--
-- The built-in 429/503 retry needs no data here and applies to the whole catalogue. This column
-- holds only what a seed author declares on top: an ordered array of
--   { "match": { "status" | "statusIn" | "bodyContains" }, "action": "retry"|"user_error",
--     "message": "...", "waitMs": 5000 }
-- evaluated first, first match wins, so an author can also override the built-in rule.
--
-- Nullable, and NULL is the norm: the overwhelming majority of APIs declare nothing.
ALTER TABLE catalog.apis
    ADD COLUMN IF NOT EXISTS error_policy JSONB;

COMMENT ON COLUMN catalog.apis.error_policy IS
    'Ordered array of error-classification rules from the seed file (errorPolicy). '
    'Each rule: {match:{status|statusIn|bodyContains}, action:retry|user_error, message?, waitMs?}. '
    'Evaluated before the built-in 429/503 Retry-After rule; first match wins.';
