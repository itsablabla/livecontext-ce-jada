package com.apimarketplace.common.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two questions that look like one: "can this cron fire?" and "should we accept it?".
 *
 * <p>They were answered by a single method, and that is a data-loss bug waiting to happen,
 * because the two predicates are consumed by opposite kinds of caller:
 *
 * <ul>
 *   <li>{@link CronOccurrences#isAcceptableInput(String)} guards the doors a user submits
 *       through, where refusing costs a 400 and a message.</li>
 *   <li>{@link CronOccurrences#isValid(String)} guards the occurrence walk AND the schedule
 *       reapers, where refusing costs the ROW: the reaper archives it with
 *       {@code INVALID_CRON_LEGACY}, which is permanent.</li>
 * </ul>
 *
 * <p>So tightening the input rule tightened the destructive one with it. {@code 1-5/10} is
 * the shape that exposed it: it parses, and it fires (on minute 1), but the new rule called
 * it invalid - which emptied the occurrence walk for every stored row carrying it, stopping
 * those schedules dead, and then archived them beyond recovery on their next tick. A
 * validator getting stricter must never be able to do that.
 */
@DisplayName("CronOccurrences - firing validity vs input acceptability")
class CronInputVsFiringValidityTest {

    /** Parses, fires on minute 1 only, and is nobody's intention. */
    private static final String DEGENERATE = "1-5/10 * * * *";

    @Nested
    @DisplayName("isValid - the permissive, destructive-path predicate")
    class FiringValidity {

        @Test
        @DisplayName("accepts a degenerate-but-firing expression")
        void degenerateStillCounts() {
            // The whole point. This is what stops the reaper destroying a working row over
            // a rule introduced after that row was written.
            assertThat(CronOccurrences.isValid(DEGENERATE)).isTrue();
        }

        @Test
        @DisplayName("and the occurrence walk actually produces its fires")
        void theWalkAgreesWithTheVerdict() {
            // Saying "valid" while `next` returns nothing would be the same outage with a
            // friendlier log line: the daemon arms nothing and the schedule goes inert.
            List<Instant> next = CronOccurrences.next(DEGENERATE, "UTC", 3);

            assertThat(next).hasSize(3);
            assertThat(next).allSatisfy(instant ->
                    assertThat(instant.atZone(java.time.ZoneOffset.UTC).getMinute()).isEqualTo(1));
        }

        @Test
        @DisplayName("still refuses an expression Spring cannot parse")
        void garbageIsStillGarbage() {
            assertThat(CronOccurrences.isValid("not a cron")).isFalse();
            assertThat(CronOccurrences.isValid("0 9 * *")).isFalse();
            assertThat(CronOccurrences.isValid("")).isFalse();
            assertThat(CronOccurrences.isValid(null)).isFalse();
        }

        @Test
        @DisplayName("a step wider than the field still FIRES, so the reaper must not kill it")
        void oversizedStepFiresHourlyRatherThanRunningAway() {
            // `*/120` on minutes is the expression the auto-archive path names in its own
            // comment, on the reasoning that it "would get getNextExecution == null, fall
            // back to now + 60s, and fire every minute forever". Spring parses it perfectly
            // well: that null came from the STRICT validator gating the occurrence walk, not
            // from the expression. Splitting the predicates removes the runaway at its
            // source - the walk now answers minute 0 of each hour, which is what the
            // expression means - so there is nothing left to protect the platform from by
            // destroying the row.
            assertThat(CronOccurrences.isValid("*/120 * * * *")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("*/120 * * * *")).isFalse();

            List<Instant> next = CronOccurrences.next("*/120 * * * *", "UTC", 2);
            assertThat(next).hasSize(2);
            assertThat(next).allSatisfy(instant ->
                    assertThat(instant.atZone(java.time.ZoneOffset.UTC).getMinute()).isZero());
            assertThat(java.time.Duration.between(next.get(0), next.get(1)))
                    .as("fires on the hour, not every minute")
                    .isEqualTo(java.time.Duration.ofHours(1));
        }
    }

    @Nested
    @DisplayName("isAcceptableInput - the strict, door-guarding predicate")
    class InputAcceptability {

        @Test
        @DisplayName("refuses a degenerate expression a caller is submitting")
        void degenerateIsRefusedAtTheDoor() {
            // Preserved from the original fix: nobody typing `1-5/10` meant "minute 1".
            assertThat(CronOccurrences.isAcceptableInput(DEGENERATE)).isFalse();
        }

        @Test
        @DisplayName("refuses a degenerate element buried in a comma list")
        void embeddedDegenerateIsCaught() {
            assertThat(CronOccurrences.isAcceptableInput("0,20-24/30 * * * *")).isFalse();
        }

        @Test
        @DisplayName("refuses a step that cannot reach a second value in a range the author WROTE")
        void stepEqualToAnExplicitRangeCollapses() {
            // step > span let these through. Each keeps only the first value of a range the
            // author bounded themselves - the same disagreement between intent and
            // behaviour as 1-5/10, since nobody writes 1-5 to mean "minute 1".
            assertThat(CronOccurrences.isAcceptableInput("1-5/5 * * * *")).isFalse();
            assertThat(CronOccurrences.isAcceptableInput("0-30/31 * * * *")).isFalse();
        }

        @Test
        @DisplayName("but a full-field step stays acceptable - it is how an hourly fire is written")
        void fullFieldStepIsAnIdiomNotAMistake() {
            // The case that stops the rule above from being step >= span everywhere, and the
            // reason this boundary needs two tests rather than one. */60 keeps only minute
            // 0, and "every 60 minutes" is precisely what the author meant: intent and
            // behaviour AGREE. An earlier round pinned this deliberately; refusing it to
            // satisfy a symmetry would break real schedules.
            assertThat(CronOccurrences.isAcceptableInput("*/60 * * * *")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("* */24 * * *")).isTrue();
            // A step the field cannot traverse even once is still refused.
            assertThat(CronOccurrences.isAcceptableInput("*/120 * * * *")).isFalse();
        }

        @Test
        @DisplayName("still accepts a step that keeps two values, however wide")
        void twoValuesIsEnough() {
            // The other side of the boundary: tightening must not start refusing
            // expressions that genuinely alternate.
            assertThat(CronOccurrences.isAcceptableInput("*/30 * * * *")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("*/59 * * * *")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("0-30/30 * * * *")).isTrue();
        }

        @Test
        @DisplayName("accepts ordinary expressions, including honest steps")
        void normalExpressionsPass() {
            assertThat(CronOccurrences.isAcceptableInput("0 9 * * *")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("*/15 * * * *")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("0 9-17/2 * * 1-5")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("0 0 9 * * *")).isTrue();
        }

        @Test
        @DisplayName("is never more permissive than isValid")
        void strictImpliesValid() {
            // The relationship the two must keep: acceptance is a SUBSET of firing
            // validity. If this ever inverts, a door accepts a cron the daemon cannot
            // honour and the row is archived the moment it comes due.
            for (String cron : List.of(DEGENERATE, "0 9 * * *", "*/15 * * * *", "not a cron",
                    "*/120 * * * *", "0 0 31 2 *", "0 9-17/2 * * 1-5")) {
                if (CronOccurrences.isAcceptableInput(cron)) {
                    assertThat(CronOccurrences.isValid(cron))
                            .as("accepted as input but not firing-valid: %s", cron)
                            .isTrue();
                }
            }
        }
    }
}
