package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.service.http.ErrorPolicyEngine.Action;
import com.apimarketplace.catalog.service.http.ErrorPolicyEngine.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine decides two things a provider refusal used to decide for us: whether the call is
 * worth re-sending, and what the reader is told. Both have a failure mode that is silent, so each
 * is pinned here rather than left to the call site.
 */
class ErrorPolicyEngineTest {

    private static final int MAX_RETRIES = 2;
    private static final long MAX_WAIT_MS = 10_000L;

    private final ErrorPolicyEngine engine = new ErrorPolicyEngine(MAX_RETRIES, MAX_WAIT_MS);

    /**
     * Most cases are method-agnostic, so they run as POST: the strictest case, and the one a
     * publishing step actually uses. The 503 rule is the exception and states its method.
     */
    private ErrorPolicyEngine.Verdict classify(int status, String body, HttpHeaders headers,
                                               String policy, int attempt) {
        return engine.classify(status, body, headers, policy, attempt, "POST");
    }

    private static HttpHeaders headersWith(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return headers;
    }

    @Nested
    @DisplayName("Built-in rule (no seed declaration)")
    class BuiltIn {

        @Test
        @DisplayName("429 retries after the delay the provider asked for")
        void retriesA429AfterRetryAfter() {
            Verdict verdict = classify(429, "{\"error\":\"too many requests\"}",
                    headersWith("3"), null, 0);

            assertThat(verdict.action()).isEqualTo(Action.RETRY);
            assertThat(verdict.waitMs()).isEqualTo(3_000L);
        }

        @Test
        @DisplayName("Retry-After: 0 still pauses, instead of hammering the endpoint that refused")
        void zeroRetryAfterStillPauses() {
            // "Come back now" from a provider that is throttling would otherwise re-send with no
            // pause at all, twice, which is the hardest possible hammering of that endpoint.
            assertThat(classify(429, "{}", headersWith("0"), null, 0).waitMs())
                    .isGreaterThanOrEqualTo(250L);

            // Same for a Retry-After date that has already passed.
            String tenSecondsAgo = ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(10)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);
            assertThat(classify(429, "{}", headersWith(tenSecondsAgo), null, 0).waitMs())
                    .isGreaterThanOrEqualTo(250L);
        }

        @Test
        @DisplayName("429 without a Retry-After still retries, on the default backoff")
        void retriesA429WithoutHeader() {
            assertThat(classify(429, "{}", headersWith(null), null, 0).waitMs())
                    .isEqualTo(1_000L);
            assertThat(classify(429, "{}", headersWith(null), null, 1).waitMs())
                    .isEqualTo(3_000L);
        }

        @Test
        @DisplayName("503 retries a GET when the provider said when to come back")
        void retries503OnIdempotentMethods() {
            assertThat(engine.classify(503, "{}", headersWith("2"), null, 0, "GET").action())
                    .isEqualTo(Action.RETRY);
            // A bare 503 can be a crash loop; re-sending into one is not obviously safe, so it
            // stays the caller's decision.
            assertThat(engine.classify(503, "{}", headersWith(null), null, 0, "GET").action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("503 does NOT retry a write, because it can arrive after the write landed")
        void doesNotRetry503OnWrites() {
            // Unlike a 429, a 503 does not prove the request was rejected: an intermediary can
            // answer 503 after the origin processed it. Re-sending a publish on that basis posts
            // the video twice, which is the one failure this feature must never cause.
            for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
                assertThat(engine.classify(503, "{}", headersWith("2"), null, 0, method).action())
                        .as("%s changes state, so it must not be re-sent on a 503", method)
                        .isEqualTo(Action.NONE);
            }
            assertThat(engine.classify(503, "{}", headersWith("2"), null, 0, null).action())
                    .as("an unknown method is treated as unsafe, not as idempotent")
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("429 retries every method, because it proves nothing was applied")
        void retries429OnWrites() {
            for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE", "GET"}) {
                assertThat(engine.classify(429, "{}", headersWith("0"), null, 0, method).action())
                        .as("%s answered 429 was refused, so re-sending is safe", method)
                        .isEqualTo(Action.RETRY);
            }
        }

        @Test
        @DisplayName("a plain 4xx is not retried")
        void doesNotRetryOrdinaryFailures() {
            assertThat(classify(400, "bad request", headersWith(null), null, 0).action())
                    .isEqualTo(Action.NONE);
            assertThat(classify(404, "not found", headersWith(null), null, 0).action())
                    .isEqualTo(Action.NONE);
            assertThat(classify(500, "boom", headersWith(null), null, 0).action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("stops retrying once the attempts are spent")
        void stopsAfterMaxRetries() {
            assertThat(classify(429, "{}", headersWith("1"), null, MAX_RETRIES - 1).action())
                    .isEqualTo(Action.RETRY);
            assertThat(classify(429, "{}", headersWith("1"), null, MAX_RETRIES).action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("a wait longer than the cap is reported, not slept through")
        void refusesToHoldTheThreadTooLong() {
            Verdict verdict = classify(429, "{}", headersWith("900"), null, 0);

            assertThat(verdict.action()).isEqualTo(Action.NONE);
            // The provider's failure is then returned as it stands. Nothing surfaces the delay to
            // the caller today, which is why the budget is a real refusal and not a deferral.
            assertThat(verdict.waitMs()).isZero();
        }
    }

    @Nested
    @DisplayName("Retry-After parsing")
    class RetryAfterParsing {

        @Test
        @DisplayName("reads the HTTP-date form as well as delta-seconds")
        void readsHttpDate() {
            String inFiveSeconds = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(5)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);

            Long seconds = engine.parseRetryAfterSeconds(headersWith(inFiveSeconds));

            assertThat(seconds).isBetween(3L, 6L);
        }

        @Test
        @DisplayName("a date already past yields zero, never a negative wait")
        void clampsPastDates() {
            String tenSecondsAgo = ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(10)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);

            assertThat(engine.parseRetryAfterSeconds(headersWith(tenSecondsAgo))).isZero();
        }

        @Test
        @DisplayName("an unparseable or absent header is simply not there")
        void ignoresGarbage() {
            assertThat(engine.parseRetryAfterSeconds(headersWith("soon"))).isNull();
            assertThat(engine.parseRetryAfterSeconds(headersWith(null))).isNull();
            assertThat(engine.parseRetryAfterSeconds(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Declared rules")
    class DeclaredRules {

        @Test
        @DisplayName("turns a provider code into a message written for the reader")
        void mapsBodyToUserMessage() {
            String policy = """
                [{"match":{"bodyContains":"unaudited_client_can_only_post_to_private_accounts"},
                  "action":"user_error",
                  "message":"Your TikTok app is not audited yet: only SELF_ONLY posts are allowed."}]
                """;

            Verdict verdict = classify(400,
                    "{\"error\":{\"code\":\"unaudited_client_can_only_post_to_private_accounts\"}}",
                    headersWith(null), policy, 0);

            assertThat(verdict.action()).isEqualTo(Action.USER_ERROR);
            assertThat(verdict.message()).contains("not audited");
        }

        @Test
        @DisplayName("matches the body case-insensitively")
        void matchesCaseInsensitively() {
            String policy = """
                [{"match":{"bodyContains":"spam_risk"},"action":"user_error","message":"Slow down."}]
                """;

            assertThat(classify(400, "{\"code\":\"SPAM_RISK_TOO_MANY_POSTS\"}",
                    headersWith(null), policy, 0).action()).isEqualTo(Action.USER_ERROR);
        }

        @Test
        @DisplayName("a body-only retry rule does NOT re-send a write that failed with a 5xx")
        void bodyOnlyRetryRuleIsStillGatedOnFiveHundreds() {
            // This is the shape tiktok.json ships, and TikTok's publish endpoints are POSTs.
            // A gateway answering 502 with a body that happens to echo the provider's rate-limit
            // code would otherwise re-send the publish twice. The rule carries no status, so the
            // seed validator cannot see the risk: only the runtime knows what actually came back.
            String policy = """
                [{"match":{"bodyContains":"rate_limit_exceeded"},"action":"retry","waitMs":5000}]
                """;

            for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
                assertThat(engine.classify(502, "rate_limit_exceeded", headersWith(null), policy, 0, method)
                        .action())
                        .as("%s must not be re-sent on a 5xx, whatever the seed declares", method)
                        .isEqualTo(Action.NONE);
            }
            assertThat(engine.classify(503, "rate_limit_exceeded", headersWith("1"), policy, 0, "POST")
                    .action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("408 and 425 are 4xx but equally ambiguous, so a write is not re-sent")
        void ambiguousFourHundredsAreGatedToo() {
            // The server may have read the request before answering either of these, so "4xx
            // means refused" does not hold for them.
            String policy = """
                [{"match":{"bodyContains":"rate_limit_exceeded"},"action":"retry"}]
                """;

            assertThat(engine.classify(408, "rate_limit_exceeded", headersWith(null), policy, 0, "POST")
                    .action())
                    .isEqualTo(Action.NONE);
            assertThat(engine.classify(425, "rate_limit_exceeded", headersWith(null), policy, 0, "POST")
                    .action())
                    .isEqualTo(Action.NONE);
            assertThat(engine.classify(408, "rate_limit_exceeded", headersWith(null), policy, 0, "GET")
                    .action())
                    .isEqualTo(Action.RETRY);
        }

        @Test
        @DisplayName("an unusable rule disables itself, not the rules after it")
        void oneBadRuleDoesNotDiscardTheRest() {
            // A bundle from a newer cloud can carry an action this build has no code for. Skipping
            // the whole list would also drop rules this build CAN execute, which is a silent
            // downgrade nobody would connect to the upgrade that caused it.
            String policy = """
                [{"match":{"status":400},"action":"disconnect_credential"},
                 {"match":{"status":400},"action":"user_error","message":"Readable."}]
                """;

            Verdict verdict = classify(400, "{}", headersWith(null), policy, 0);

            assertThat(verdict.action()).isEqualTo(Action.USER_ERROR);
            assertThat(verdict.message()).isEqualTo("Readable.");
        }

        @Test
        @DisplayName("a malformed statusIn narrows the rule to nothing, it does not widen it")
        void malformedStatusInFailsClosed() {
            // Skipping the criterion would leave a body-only rule that fires on statuses the
            // author never listed - a malformed declaration must never match MORE than written.
            String policy = """
                [{"match":{"statusIn":"429","bodyContains":"limit"},"action":"user_error",
                  "message":"m"}]
                """;

            assertThat(classify(429, "limit reached", headersWith(null), policy, 0).action())
                    .isEqualTo(Action.RETRY);
        }

        @Test
        @DisplayName("the same rule still retries a 4xx write, and a 5xx read")
        void bodyOnlyRetryRuleStillWorksWhereItIsSafe() {
            String policy = """
                [{"match":{"bodyContains":"rate_limit_exceeded"},"action":"retry","waitMs":5000}]
                """;

            // 4xx: the provider refused, nothing was applied.
            assertThat(engine.classify(400, "rate_limit_exceeded", headersWith(null), policy, 0, "POST")
                    .action())
                    .isEqualTo(Action.RETRY);
            // 5xx on a read: re-sending cannot duplicate anything.
            assertThat(engine.classify(502, "rate_limit_exceeded", headersWith(null), policy, 0, "GET")
                    .action())
                    .isEqualTo(Action.RETRY);
        }

        @Test
        @DisplayName("a gated 5xx still delivers the rule's message rather than the raw body")
        void gatedRetryKeepsItsMessage() {
            String policy = """
                [{"match":{"bodyContains":"rate_limit_exceeded"},"action":"retry",
                  "message":"The provider is throttling this account."}]
                """;

            Verdict verdict = engine.classify(502, "rate_limit_exceeded", headersWith(null), policy, 0, "POST");

            assertThat(verdict.action()).isEqualTo(Action.USER_ERROR);
            assertThat(verdict.message()).contains("throttling");
        }

        @Test
        @DisplayName("a declared rule wins over the built-in 429 retry")
        void declaredRuleOverridesBuiltIn() {
            // Some providers answer 429 for "this account is blocked", where re-sending only digs
            // the hole deeper. The author must be able to say so.
            String policy = """
                [{"match":{"status":429,"bodyContains":"banned"},
                  "action":"user_error","message":"This account is blocked from posting."}]
                """;

            Verdict verdict = classify(429, "{\"reason\":\"banned\"}", headersWith("1"), policy, 0);

            assertThat(verdict.action()).isEqualTo(Action.USER_ERROR);
            assertThat(verdict.message()).isEqualTo("This account is blocked from posting.");
        }

        @Test
        @DisplayName("every criterion in a match must hold")
        void criteriaAreAnded() {
            String policy = """
                [{"match":{"status":400,"bodyContains":"quota"},"action":"user_error","message":"Quota."}]
                """;

            assertThat(classify(400, "no match here", headersWith(null), policy, 0).action())
                    .isEqualTo(Action.NONE);
            assertThat(classify(403, "quota exceeded", headersWith(null), policy, 0).action())
                    .isEqualTo(Action.NONE);
            assertThat(classify(400, "quota exceeded", headersWith(null), policy, 0).action())
                    .isEqualTo(Action.USER_ERROR);
        }

        @Test
        @DisplayName("statusIn matches any listed status")
        void statusInMatchesAny() {
            String policy = """
                [{"match":{"statusIn":[400,403],"bodyContains":"scope"},
                  "action":"user_error","message":"Missing permission."}]
                """;

            assertThat(classify(403, "missing scope", headersWith(null), policy, 0).action())
                    .isEqualTo(Action.USER_ERROR);
            assertThat(classify(400, "missing scope", headersWith(null), policy, 0).action())
                    .isEqualTo(Action.USER_ERROR);
            assertThat(classify(429, "missing scope", headersWith(null), policy, 0).action())
                    .isEqualTo(Action.RETRY);
        }

        @Test
        @DisplayName("a declared retry uses its own wait, unless the provider sent one")
        void declaredWaitIsUsedOnlyWithoutRetryAfter() {
            String policy = """
                [{"match":{"bodyContains":"rate_limit_exceeded"},"action":"retry","waitMs":5000}]
                """;

            // 4xx, not 200: both call sites classify from inside a catch, so a provider that
            // reports its error code in a 200 body is never seen by this engine today.
            assertThat(classify(400, "rate_limit_exceeded", headersWith(null), policy, 0).waitMs())
                    .isEqualTo(5_000L);
            // The provider knows when THIS key stops being throttled; the seed only guessed.
            assertThat(classify(400, "rate_limit_exceeded", headersWith("2"), policy, 0).waitMs())
                    .isEqualTo(2_000L);
        }

        @Test
        @DisplayName("an exhausted retry rule still delivers its message")
        void exhaustedRetryKeepsItsMessage() {
            String policy = """
                [{"match":{"status":429},"action":"retry",
                  "message":"The provider is throttling this account, try again shortly."}]
                """;

            Verdict verdict = classify(429, "{}", headersWith("1"), policy, MAX_RETRIES);

            assertThat(verdict.action()).isEqualTo(Action.USER_ERROR);
            assertThat(verdict.message()).contains("throttling");
        }

        @Test
        @DisplayName("the first matching rule wins")
        void firstMatchWins() {
            String policy = """
                [{"match":{"bodyContains":"limit"},"action":"user_error","message":"first"},
                 {"match":{"bodyContains":"limit"},"action":"user_error","message":"second"}]
                """;

            assertThat(classify(400, "limit reached", headersWith(null), policy, 0).message())
                    .isEqualTo("first");
        }

        @Test
        @DisplayName("an empty match matches nothing")
        void emptyMatchNeverFires() {
            // A rule with no criteria would fire on every failure of the API, which is a trap
            // rather than a shortcut: it would rewrite unrelated errors with one wording.
            String policy = """
                [{"match":{},"action":"user_error","message":"everything is broken"}]
                """;

            assertThat(classify(404, "not found", headersWith(null), policy, 0).action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("an explicit null criterion narrows the rule to nothing, it does not widen it")
        void nullCriterionFailsClosed() {
            // A bundle can carry {"status": null}. Dropping the criterion would leave a rule that
            // fires on every status the provider returns, which is the opposite of what the author
            // wrote and the same widening the statusIn branch already refuses.
            String nullStatus = """
                [{"match":{"status":null,"bodyContains":"quota"},"action":"user_error","message":"m"}]
                """;
            assertThat(classify(500, "quota exceeded", headersWith(null), nullStatus, 0).action())
                    .isEqualTo(Action.NONE);

            String nullBody = """
                [{"match":{"status":400,"bodyContains":null},"action":"user_error","message":"m"}]
                """;
            assertThat(classify(400, "anything", headersWith(null), nullBody, 0).action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("a blank message is no message, so the reader never gets an empty error")
        void blankMessageIsTreatedAsAbsent() {
            // Replacing the provider's body with "" is worse than the body it replaced.
            String blank = """
                [{"match":{"status":429},"action":"user_error","message":"   "}]
                """;

            // Falls through to the built-in rule rather than reporting nothing.
            assertThat(classify(429, "{}", headersWith("1"), blank, 0).action())
                    .isEqualTo(Action.RETRY);

            // Same on the gated-5xx path, which has its own message return.
            String blankRetry = """
                [{"match":{"bodyContains":"rate_limit"},"action":"retry","message":""}]
                """;
            Verdict gated = engine.classify(502, "rate_limit", headersWith(null), blankRetry, 0, "POST");
            assertThat(gated.action()).isEqualTo(Action.NONE);
            assertThat(gated.message()).isNull();
        }

        @Test
        @DisplayName("a needle too short to identify an error code matches nothing")
        void shortNeedleFailsClosed() {
            // The validator refuses this on a seed, so it arrives only from a bundle or a
            // hand-edited row. A one or two character needle is in nearly every error body, so
            // honouring it would rewrite EVERY failed call of the API with a single wording.
            String policy = """
                [{"match":{"bodyContains":"ab"},"action":"user_error","message":"everything"}]
                """;

            assertThat(classify(404, "not found: table ab missing", headersWith(null), policy, 0)
                    .action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("a match that is missing or not an object matches nothing")
        void missingOrNonObjectMatch() {
            assertThat(classify(400, "x", headersWith(null),
                    "[{\"action\":\"user_error\",\"message\":\"m\"}]", 0).action())
                    .isEqualTo(Action.NONE);
            assertThat(classify(400, "x", headersWith(null),
                    "[{\"match\":\"400\",\"action\":\"user_error\",\"message\":\"m\"}]", 0).action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("classifyForMessage reports a rule without ever asking for a retry")
        void classifyForMessageNeverRetries() {
            // Used by the error-building path, where the attempts are already spent: it must be
            // able to deliver a message and must never come back asking for another call.
            String policy = """
                [{"match":{"status":429},"action":"retry","message":"Throttled, try later."}]
                """;

            Verdict verdict = engine.classifyForMessage(429, "{}", headersWith("1"), policy);

            assertThat(verdict.action()).isEqualTo(Action.USER_ERROR);
            assertThat(verdict.message()).isEqualTo("Throttled, try later.");
        }

        @Test
        @DisplayName("a malformed policy is ignored, the built-in rule still applies")
        void malformedPolicyDoesNotBreakTheCall() {
            assertThat(classify(429, "{}", headersWith("1"), "{ not json", 0).action())
                    .isEqualTo(Action.RETRY);
            assertThat(classify(429, "{}", headersWith("1"), "{\"rules\":[]}", 0).action())
                    .isEqualTo(Action.RETRY);
        }

        @Test
        @DisplayName("a user_error with no message degrades to the built-in behaviour")
        void messagelessUserErrorFallsBackToBuiltIn() {
            // The validator refuses this shape, so it only arrives from a bundle or a hand-edited
            // row. Honouring it would stop the 429 retry AND tell the reader nothing new.
            String policy = "[{\"match\":{\"status\":429},\"action\":\"user_error\"}]";

            assertThat(classify(429, "{}", headersWith("1"), policy, 0).action())
                    .isEqualTo(Action.RETRY);
        }

        @Test
        @DisplayName("an absurd Retry-After declines the retry instead of overflowing to zero")
        void hugeRetryAfterDeclines() {
            // retryAfterSeconds * 1000 near Long.MAX overflows negative, and a max(0, ...) would
            // then turn "wait forever" into "retry immediately".
            assertThat(classify(429, "{}", headersWith(String.valueOf(Long.MAX_VALUE / 2)), null, 0)
                    .action())
                    .isEqualTo(Action.NONE);
        }

        @Test
        @DisplayName("an action this build does not know degrades to the built-in behaviour")
        void unknownActionFallsBackToBuiltIn() {
            // The validator rejects an unknown action at seed time, so this only happens to a
            // self-hosted install applying a bundle from a newer cloud that declares an action it
            // has no code for. Falling back to the built-in rule keeps that install behaving like
            // it did before the bundle; treating the rule as "handled" would silently switch off
            // the 429 retry on an older build.
            String policy = "[{\"match\":{\"status\":429},\"action\":\"disconnect_credential\"}]";

            assertThat(classify(429, "{}", headersWith("1"), policy, 0).action())
                    .isEqualTo(Action.RETRY);
        }

        @Test
        @DisplayName("a null body never throws")
        void handlesNullBody() {
            String policy = """
                [{"match":{"bodyContains":"anything"},"action":"user_error","message":"m"}]
                """;

            assertThat(classify(500, null, headersWith(null), policy, 0).action())
                    .isEqualTo(Action.NONE);
        }
    }
}
