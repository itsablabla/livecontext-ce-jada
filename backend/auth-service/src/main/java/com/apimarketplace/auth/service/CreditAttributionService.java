package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.PendingCreditUpgrade;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Centralized service for credit attribution (grants, resets, pack upgrades).
 * Single responsibility: handles ONLY credit attribution logic.
 * CreditService remains the owner of consumption (deductions).
 *
 * Credit model:
 * - Plans unlock features only, they do NOT grant credits.
 *   Exception: FREE plan has includedLlmTokens (1000 credits). Admin-granted comp
 *   Starter/Pro/Team subscriptions ({@code provider="internal"}, non-FREE plan) grant the
 *   tier-0 base pack (5000 credits) - the same base a paying customer with no pack receives.
 * - Credits come from credit packs (tiers) via Stripe slider.
 * - Credit tier upgrades use billing_cycle_anchor:NOW (new cycle, full grant).
 * - Credit tier downgrades are scheduled (end of period).
 * - Plan changes (upgrade/downgrade) have NO credit logic.
 *
 * Idempotence strategy (subscription-based sourceId):
 * 1. BillingEvent.existsByEventId() - rejects duplicate webhooks (caller responsibility)
 * 2. CreditLedgerRepository.existsBySourceId() - exact match prevents double-grant
 *    (backed by UNIQUE partial index on source_id, V6 migration)
 * 3. SourceId derived from subscription state (subscriptionId + currentPeriodStart),
 *    making idempotency structural - same subscription state always = same sourceId.
 *
 * SourceId formats:
 * - Initial plan credits:  plan_sub_{subId}_init
 * - Initial pack credits:  pack_sub_{subId}_init
 * - Renewal reset:         reset_sub_{subId}_{epochSec}
 * - Renewal plan/pack:     plan_sub_{subId}_{epochSec} / pack_sub_{subId}_{epochSec}
 * - Pack upgrade:          pack_sub_{subId}_upgrade_{epochSec}
 */
@Service
public class CreditAttributionService {

    /**
     * Product-analytics emitter (PostHog). Optional so hand-built test instances and
     * analytics-less deployments are untouched; a null field emits nothing.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics;

    private static final Logger log = LoggerFactory.getLogger(CreditAttributionService.class);

    private final CreditService creditService;
    private final CreditLedgerRepository ledgerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PendingCreditUpgradeRepository pendingCreditUpgradeRepository;

    /**
     * Needed for {@code refresh}: a {@code SELECT ... FOR UPDATE} through Spring Data returns the
     * first-level-cached instance when the entity is already managed here, so the lock is taken
     * but the values are whatever the caller loaded. See {@link #resolveManagedForUpdate}.
     * Optional so the existing 4-arg constructor keeps working in unit tests.
     */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public CreditAttributionService(CreditService creditService,
                                     CreditLedgerRepository ledgerRepository,
                                     SubscriptionRepository subscriptionRepository,
                                     PendingCreditUpgradeRepository pendingCreditUpgradeRepository) {
        this.creditService = creditService;
        this.ledgerRepository = ledgerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.pendingCreditUpgradeRepository = pendingCreditUpgradeRepository;
    }

    /**
     * Attribute credits on a new subscription creation.
     * Grants full credit pack credits or plan-included credits.
     *
     * <p>No {@link #resolveManagedForUpdate} here, unlike the renewal and pack-change paths:
     * every caller passes a subscription that is MANAGED in its own transaction (freshly
     * persisted, or loaded inside it), and the sourceId is keyed on {@code _init} rather than
     * on a mutable period - there is no stale value to read.
     *
     * @param userId          the user ID
     * @param subscription    the local subscription entity (used for sourceId derivation)
     * @param creditQuantity  Stripe quantity for credit pack (0 = no pack)
     */
    @Transactional
    public void attributeOnSubscription(Long userId, Subscription subscription, int creditQuantity) {
        try {
            Plan plan = subscription.getPlan();
            String subKey = "sub_" + subscription.getId();
            log.info("Attributing credits for new subscription: userId={}, plan={}, creditQty={}, subId={}",
                    userId, plan.getCode(), creditQuantity, subscription.getId());

            if (grantsBasePack(subscription, creditQuantity)) {
                // Paid plans AND admin-granted comp plans (internal, non-FREE): grant the
                // tier-0 base pack (5K at $0 when creditQuantity=0). A comp Starter/Pro/Team
                // therefore gets the same 5K base as a paying customer with no pack - never
                // the plan's larger allowance (admin-credits "5k/month max" rule).
                grantPackCredits(userId, "pack_" + subKey + "_init", creditQuantity, plan.getCode());
            } else if (plan.getIncludedLlmTokens() != null && plan.getIncludedLlmTokens() > 0) {
                // Internal FREE plan: grant plan-included credits (1K)
                grantPlanCredits(userId, "plan_" + subKey + "_init", plan);
            }
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate credit attribution detected for subId={}, treating as idempotent skip",
                    subscription.getId());
        }
    }

    /**
     * Attribute credits on subscription renewal (invoice.paid with billing_reason=subscription_cycle).
     * Resets balance to zero, then re-grants full pack credits.
     *
     * @param userId       the user ID
     * @param subscription the local subscription entity
     */
    @Transactional
    public RenewalOutcome attributeOnRenewal(Long userId, Subscription subscription) {
        return attributeOnRenewal(userId, subscription, null);
    }

    /**
     * What {@link #attributeOnRenewal(Long, Subscription, LocalDateTime)} actually did, so the
     * caller can log the truth instead of assuming success. The internal scheduler used to
     * announce "renewed" unconditionally, which is precisely how a renewal that granted nothing
     * stayed invisible in production for weeks.
     */
    public enum RenewalOutcome {
        /** Period advanced (when asked) and the cycle's credits were attributed. */
        RENEWED,
        /** Another actor had already renewed this cycle; deliberately left untouched. */
        ALREADY_RENEWED,
        /** The row could not be renewed (not resolvable, no longer eligible, no plan). */
        SKIPPED
    }

    /**
     * Renewal attribution that can also ADVANCE the billing period, atomically with
     * the reset + re-grant.
     *
     * <p>{@code newPeriodStart} is the contract difference between the two renewal
     * callers:
     * <ul>
     *   <li><b>{@code null}</b> - the caller already owns the period (Stripe
     *       {@code invoice.paid}, where Stripe is the source of truth for the cycle,
     *       and {@code AdminPlanService.assignPlan}, which anchors the cycle itself
     *       before calling). The period is left alone. One thing DID change for these
     *       callers: the row is now re-read under lock, so the plan, the credit quantity,
     *       the period and the balance are the live values rather than whatever the caller
     *       loaded. For the Stripe webhook that window is small (it loads the row a few lines
     *       before calling), so this is defence rather than a bug fix there - but the reload
     *       is what makes {@code resetBalance}'s write safe for ANY caller, which is the
     *       point, and it can now return {@link RenewalOutcome#SKIPPED} where the row has
     *       vanished instead of writing through a detached copy.</li>
     *   <li><b>non-null</b> - the internal monthly scheduler, which has no external
     *       source of truth for the cycle. The period is advanced HERE, inside this
     *       transaction and BEFORE the sourceId is derived from it.</li>
     * </ul>
     *
     * <p>Doing the advance here (instead of in the scheduler, after this call) fixes
     * two production defects that between them made every internal renewal wrong:
     * <ol>
     *   <li><b>Lost update.</b> The scheduler used to write the subscription row again
     *       AFTER this method committed. Its {@link Subscription} is detached (a
     *       {@code @Scheduled} thread has no persistence context), and the grant lands
     *       on a re-resolved managed instance, so that trailing {@code save} merged a
     *       pre-grant copy back over the fresh balance. Silent: the ledger showed the
     *       grant, the wallet showed zero.</li>
     *   <li><b>Period-key reuse.</b> The sourceId embeds {@code currentPeriodStart}.
     *       Attributing BEFORE advancing it re-mints the previous cycle's key, which
     *       the {@code existsBySourceId} guards then skip - the renewal silently
     *       granted nothing at all.</li>
     * </ol>
     * Advancing first also makes the pair crash-safe: period and credits commit
     * together, so a failure leaves the subscription expired and the next hourly pass
     * retries it cleanly.
     *
     * @return what actually happened, so a caller can log the truth rather than assume success
     */
    @Transactional
    public RenewalOutcome attributeOnRenewal(Long userId, Subscription subscription, LocalDateTime newPeriodStart) {
        try {
            // Work on a MANAGED instance holding the row's live values; the caller's object
            // may be detached and stale (see resolveManagedForUpdate).
            // No fall-back to the caller's instance, for EITHER overload. This method always
            // writes through the entity it works on - resetBalance zeroes the balance and saves
            // it - so continuing on a detached copy would merge every stale column back over the
            // row (defect 3), and if the row is genuinely gone the merge would re-insert it as a
            // brand-new subscription. There is nothing safe to do without the live row.
            Subscription sub = resolveManagedForUpdate(subscription).orElse(null);
            if (sub == null) {
                log.error("Cannot attribute renewal credits: subscription row {} is not resolvable for update. userId={}",
                        subscription == null ? null : subscription.getId(), userId);
                return RenewalOutcome.SKIPPED;
            }

            if (newPeriodStart != null) {
                // Re-validate UNDER the lock everything the unlocked selection query matched on.
                // Between that READ COMMITTED select and acquiring this lock, another actor may
                // have renewed the row (concurrent admin comp grant, or an overlapping pass once
                // one exceeds lockAtMostFor) or made it ineligible (a Stripe upgrade cancels the
                // internal sibling). Acting on a stale match grants the same cycle twice.
                // NOTE what this does NOT protect: grantCredits resolves the wallet by user
                // (most-recent active row), so on an account that holds both an active internal
                // row and a newer active Stripe row, the reset and the grant still land on
                // different rows. Sibling-cancellation in SubscriptionService is what keeps that
                // shape from existing; this guard only stops us acting on a row that already
                // moved on.
                if (sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isAfter(newPeriodStart)) {
                    log.info("Subscription {} was already renewed by a concurrent actor (period ends {}), skipping",
                            sub.getId(), sub.getCurrentPeriodEnd());
                    return RenewalOutcome.ALREADY_RENEWED;
                }
                if (!isInternalRenewalEligible(sub)) {
                    log.info("Subscription {} is no longer eligible for internal renewal (provider={}, status={}), skipping",
                            sub.getId(), sub.getProvider(), sub.getStatus());
                    return RenewalOutcome.SKIPPED;
                }
                // Advance BEFORE the plan check: a row we refuse to attribute must still
                // leave the expired window, otherwise every hourly pass re-picks it forever.
                sub.setCurrentPeriodStart(newPeriodStart);
                sub.setCurrentPeriodEnd(newPeriodStart.plusMonths(1));
                // updatedAt is deliberately NOT set here - @PreUpdate stamps it at flush.
            }

            Plan plan = sub.getPlan();
            if (plan == null) {
                log.error("Cannot attribute renewal credits: subscription {} has no plan. userId={}",
                        sub.getId(), userId);
                return RenewalOutcome.SKIPPED;
            }

            int creditQuantity = sub.getCreditQuantity() != null ? sub.getCreditQuantity() : 0;
            String subKey = "sub_" + sub.getId();
            String periodSuffix = periodKey(sub.getCurrentPeriodStart());

            log.info("Attributing credits for renewal: userId={}, plan={}, creditQty={}, subId={}, period={}",
                    userId, plan.getCode(), creditQuantity, sub.getId(), periodSuffix);

            // Reset balance to zero
            resetBalance(userId, "reset_" + subKey + "_" + periodSuffix, sub);

            // Re-grant credits
            if (grantsBasePack(sub, creditQuantity)) {
                // Paid plans AND admin-granted comp plans (internal, non-FREE): grant the
                // tier-0 base pack (5K at $0 when creditQuantity=0). Keeps a comp Starter/Pro/Team
                // renewing at the 5K base every cycle - never the plan's larger allowance.
                grantPackCredits(userId, "pack_" + subKey + "_" + periodSuffix, creditQuantity, plan.getCode());
            } else if (plan.getIncludedLlmTokens() != null && plan.getIncludedLlmTokens() > 0) {
                // Internal FREE plan: grant plan-included credits (1K)
                grantPlanCredits(userId, "plan_" + subKey + "_" + periodSuffix, plan);
            }
            return RenewalOutcome.RENEWED;
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate renewal credit attribution detected for subId={}, treating as idempotent skip",
                    subscription == null ? null : subscription.getId());
            return RenewalOutcome.SKIPPED;
        }
    }

    /**
     * Statuses {@code findExpiredInternalSubscriptions} matches on. Re-checked under the lock
     * because that selection query is unlocked: a Stripe upgrade cancels the internal sibling
     * row, and renewing a canceled comp row would grant its 5K onto the wallet the user has
     * meanwhile started paying for.
     */
    private static final java.util.Set<String> INTERNAL_RENEWABLE_STATUSES =
            java.util.Set.of("active", "trialing");

    private static boolean isInternalRenewalEligible(Subscription sub) {
        return "internal".equalsIgnoreCase(sub.getProvider())
                && sub.getStatus() != null
                && INTERNAL_RENEWABLE_STATUSES.contains(sub.getStatus().toLowerCase());
    }

    /**
     * Re-read {@code subscription} by primary key under {@code PESSIMISTIC_WRITE} so the
     * rest of the attribution works on live column values inside this transaction.
     *
     * <p>Callers hand us a {@link Subscription} loaded elsewhere. When that elsewhere is
     * another transaction (the internal renewal scheduler, the Stripe webhook handler)
     * the object is DETACHED, and two things go wrong if we trust it: the balance we read
     * for the {@code PLAN_RESET} audit amount may not be the row's current balance, and
     * any {@code save} through it merges every stale column back over the row. A caller
     * that is already inside a transaction (admin comp grant) gets its own managed
     * instance straight back from the persistence context, so this is a no-op there.
     *
     * <p>Returns EMPTY when there is no row to address (null argument, unsaved entity) or the
     * row has vanished. Whether empty is fatal is the CALLER's decision, and the rule is simply
     * whether that caller WRITES through the entity:
     * <ul>
     *   <li>{@link #attributeOnRenewal} always writes (at minimum {@code resetBalance} zeroes
     *       the balance and saves), so it MUST abort on empty - regardless of whether it was
     *       also asked to advance the period.</li>
     *   <li>{@link #handleCreditPackChange} only READS the live plan and period to key an
     *       idempotent grant, so it degrades to the caller's instance: nothing is written
     *       through it, and aborting would drop a grant Stripe has already charged for.</li>
     * </ul>
     */
    private Optional<Subscription> resolveManagedForUpdate(Subscription subscription) {
        if (subscription == null || subscription.getId() == null) {
            return Optional.empty();
        }
        Optional<Subscription> managed = subscriptionRepository.findByIdForUpdate(subscription.getId());
        // The lock alone does NOT guarantee fresh values. When the caller's entity is already in
        // this persistence context - which it is for every caller that shares a transaction or
        // an open-in-view EntityManager with us, i.e. the Stripe webhook and the admin grant -
        // the query returns that same first-level-cached instance, unrefreshed, however stale it
        // is. Verified empirically: a value committed by another connection after the caller's
        // load was still invisible after the FOR UPDATE. refresh() is what actually re-reads the
        // row, and it is safe on the row we now hold the lock on.
        if (entityManager != null) {
            managed.ifPresent(entityManager::refresh);
        }
        return managed;
    }

    /**
     * Handle credit pack tier upgrade.
     * With billing_cycle_anchor:NOW, Stripe starts a new cycle and charges full price.
     * We grant the full new pack credits. User keeps their remaining balance.
     *
     * @param userId              the user ID
     * @param callerSubscription  the local subscription entity, possibly detached
     * @param oldCreditQuantity   previous Stripe quantity (kept for logging)
     * @param newCreditQuantity   new Stripe quantity (tier cost)
     */
    @Transactional
    public void handleCreditPackChange(Long userId, Subscription callerSubscription,
                                        int oldCreditQuantity, int newCreditQuantity) {
        try {
            // periodSuffix keys this grant's idempotency, so read it off the live row. Today's
            // only production caller (SubscriptionService, @Transactional at class level) hands
            // us a MANAGED entity, so this resolves to the same instance and buys nothing but
            // the lock; it is here so a future caller reaching this method with a detached row
            // does not silently key the grant on a stale period. Unlike the renewal path this
            // one never WRITES through the entity, so an unresolvable row degrades safely to
            // the caller's copy rather than aborting a grant Stripe has already charged for.
            Subscription subscription = resolveManagedForUpdate(callerSubscription).orElse(callerSubscription);
            Plan plan = subscription.getPlan();
            if (plan == null) {
                log.error("Cannot grant pack-change credits: subscription {} has no plan. userId={}",
                        subscription.getId(), userId);
                return;
            }
            String planCode = plan.getCode();
            String subKey = "sub_" + subscription.getId();
            String periodSuffix = periodKey(subscription.getCurrentPeriodStart());

            log.info("Credit pack upgrade for userId={}: qty {} -> {}, plan={}, subId={}",
                    userId, oldCreditQuantity, newCreditQuantity, planCode, subscription.getId());

            if (newCreditQuantity <= 0) {
                log.info("Pack removed for userId={}, no credits to grant", userId);
                return;
            }

            // Grant full new pack credits (no reset - user keeps remaining balance)
            grantPackCredits(userId, "pack_" + subKey + "_upgrade_" + periodSuffix, newCreditQuantity, planCode);
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate pack change credit attribution detected for subId={}, treating as idempotent skip",
                    callerSubscription.getId());
        }
    }

    /**
     * Grant credits for a credit-tier upgrade that has been paid via the Option A
     * one-shot invoice flow. Called from the {@code invoice.paid} webhook handler
     * after looking up a {@link PendingCreditUpgrade} row by Stripe invoice id.
     *
     * <p>Source-id format ({@code stripe_invoice:<id>}) is UNIQUE across the ledger
     * (V6 partial index), so duplicate webhook deliveries - or a race with a future
     * synchronous grant path - collapse to a single row via the
     * {@code DataIntegrityViolationException} swallow below.
     *
     * <p>This path is the sole place that grants pack credits for an Option A
     * upgrade. The legacy {@code handleCreditPackChange} (triggered by
     * {@code customer.subscription.updated}) is skipped by
     * {@code SubscriptionService.onSubscriptionUpsert} when a matching pending row
     * exists, preventing double-grants.
     *
     * @param pending the pending-upgrade row resolved from the webhook's invoice id
     */
    @Transactional
    public void handleCreditUpgradeInvoicePaid(PendingCreditUpgrade pending) {
        if (pending == null) {
            log.warn("handleCreditUpgradeInvoicePaid called with null pending - skipping");
            return;
        }
        if (PendingCreditUpgrade.STATUS_FAILED.equals(pending.getStatus())) {
            log.error("invoice.paid received for a FAILED pending upgrade - refusing to grant. " +
                    "invoice={}, user={}, sub={}",
                    pending.getStripeInvoiceId(), pending.getUserId(), pending.getProviderSubscriptionId());
            return;
        }
        try {
            Subscription subscription = subscriptionRepository.findById(pending.getSubscriptionId())
                    .orElse(null);
            if (subscription == null) {
                log.error("handleCreditUpgradeInvoicePaid: subscription {} not found for pending invoice {}",
                        pending.getSubscriptionId(), pending.getStripeInvoiceId());
                return;
            }
            Plan plan = subscription.getPlan();
            String planCode = plan != null ? plan.getCode() : "UNKNOWN";

            String sourceId = "stripe_invoice:" + pending.getStripeInvoiceId();
            log.info("Granting credit-upgrade pack credits: user={}, sub={}, tier={}, qty={}, invoice={}",
                    pending.getUserId(), subscription.getId(), pending.getTargetTierIndex(),
                    pending.getTargetCreditQuantity(), pending.getStripeInvoiceId());

            grantPackCredits(pending.getUserId(), sourceId, pending.getTargetCreditQuantity(), planCode);
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate credit-upgrade grant for invoice={} - V6 UNIQUE absorbs, treating as idempotent skip",
                    pending.getStripeInvoiceId());
        }
    }

    // ========== Private helpers ==========

    /**
     * Plan codes that an admin can grant as a complimentary subscription. When such a plan
     * is held on an {@code internal} (non-Stripe) subscription it grants the tier-0 5K base -
     * the same as a paying customer with no pack. Mirrors
     * {@code AdminPlanService.ALLOWED_PLAN_CODES} minus FREE (FREE keeps its 1K plan grant).
     */
    private static final java.util.Set<String> COMP_BASE_PACK_PLANS = java.util.Set.of("STARTER", "PRO", "TEAM");

    /**
     * Decide whether a subscription grants the tier-0 <b>base pack</b> (5,000 credits
     * at $0 when {@code creditQuantity == 0}) versus the smaller plan-included grant.
     *
     * <p>True for:
     * <ul>
     *   <li>Any paid subscription ({@code provider != "internal"}) - unchanged behaviour
     *       (paid Starter/Pro/Team/Enterprise all flow here, exactly as before).</li>
     *   <li>Any subscription that carries an explicit credit pack ({@code creditQuantity > 0}).</li>
     *   <li>An admin-granted comp Starter/Pro/Team row that is still {@code provider == "internal"}.
     *       This is the ONLY new case - it makes a comp plan receive the same 5K base as a
     *       paying customer with no pack (the admin-credits "5k/month max" rule).</li>
     * </ul>
     *
     * <p>Deliberately narrow on the third branch: internal FREE keeps its 1K plan-included
     * grant, and internal CREDIT_PACK/PAYG subs keep granting nothing at qty 0. Only the
     * previously-impossible "internal + Starter/Pro/Team" case changes behaviour, so the
     * mapping is provably a no-op for every pre-existing subscription shape.
     */
    private static boolean grantsBasePack(Subscription subscription, int creditQuantity) {
        boolean isPaidSubscription = !"internal".equalsIgnoreCase(subscription.getProvider());
        if (isPaidSubscription || creditQuantity > 0) {
            return true;
        }
        String code = subscription.getPlan() != null ? subscription.getPlan().getCode() : null;
        return code != null && COMP_BASE_PACK_PLANS.contains(code.toUpperCase());
    }

    /**
     * Convert a period start timestamp to epoch seconds string for sourceId construction.
     */
    private static String periodKey(LocalDateTime periodStart) {
        if (periodStart == null) {
            return "0";
        }
        return String.valueOf(periodStart.toEpochSecond(ZoneOffset.UTC));
    }

    /**
     * V250/PR3 - Grant credits from a Stripe PAYG one-time top-up checkout.
     *
     * <p>Called from {@code WebhookController.handleCheckoutCompleted} when
     * the metadata.kind == "payg_topup" branch matches. Routes the grant
     * via {@code CreditService.grantCredits(sourceType="PAYG_TOPUP")} which
     * lands the amount on {@code subscription.payg_remaining_credits}
     * (V250 bucket - persists across sub-renewal cycles).
     *
     * <p>Idempotent: the underlying {@code grantCredits} catches
     * {@code DataIntegrityViolationException} via the unique constraint on
     * {@code credit_ledger.source_id}. Caller passes the Stripe session id
     * as sourceId - Stripe replays the same session id, the second grant
     * is a no-op skip.
     *
     * @param userId    recipient user
     * @param amount    credit amount (NOT cents - already in credit units)
     * @param sessionId Stripe checkout session id (used as ledger sourceId
     *                  for idempotence)
     * @param tier      "small" / "medium" / "large" - used only for audit
     *                  description and Prometheus labels (optional)
     */
    public void grantPaygTopup(Long userId, BigDecimal amount, String sessionId, String tier) {
        if (ledgerRepository.existsBySourceId(sessionId)) {
            log.info("PAYG top-up already granted for sessionId={}, idempotent skip", sessionId);
            return;
        }

        String description = "PAYG top-up tier=" + (tier == null ? "?" : tier);
        log.info("Granting {} PAYG credits (tier={}) to userId={} sessionId={}",
                amount, tier, userId, sessionId);

        CreditConsumeResult result = creditService.grantCredits(
                userId, amount, "PAYG_TOPUP", sessionId, description);
        if (!result.success()) {
            log.error("Failed to grant PAYG top-up for userId={}, tier={}, sessionId={}: {}",
                    userId, tier, sessionId, result.error());
            throw new IllegalStateException("PAYG top-up grant failed: " + result.error());
        }
        if (analytics != null) analytics.creditsPurchased(userId, amount, tier);
    }

    /**
     * Grant plan-included credits for plans that have includedLlmTokens (e.g. FREE plan).
     * Used when no credit pack is attached (creditQuantity = 0).
     */
    private void grantPlanCredits(Long userId, String sourceId, Plan plan) {
        if (ledgerRepository.existsBySourceId(sourceId)) {
            log.info("Plan credits already granted for sourceId={}, skipping", sourceId);
            return;
        }

        BigDecimal amount = BigDecimal.valueOf(plan.getIncludedLlmTokens());
        log.info("Granting {} plan-included credits (plan={}) to userId={}",
                plan.getIncludedLlmTokens(), plan.getCode(), userId);

        CreditConsumeResult result = creditService.grantCredits(userId, amount, "PURCHASE", sourceId,
                "Plan-included credits: " + plan.getCode() + " (" + plan.getIncludedLlmTokens() + " credits)");
        if (!result.success()) {
            log.error("Failed to grant plan credits for userId={}, plan={}: {}",
                    userId, plan.getCode(), result.error());
            throw new IllegalStateException("Plan credit grant failed: " + result.error());
        }
    }

    /**
     * Grant credit pack credits (PURCHASE) based on the Stripe quantity (tier cost).
     * Uses CreditTierConstants to resolve tier index -> credit amount.
     */
    private void grantPackCredits(Long userId, String sourceId, int creditQuantity, String planCode) {
        if (creditQuantity < 0) {
            return;
        }

        if (ledgerRepository.existsBySourceId(sourceId)) {
            log.info("Pack credits already granted for sourceId={}, skipping", sourceId);
            return;
        }

        int tierIndex = CreditTierConstants.resolveTierIndex(creditQuantity, planCode);
        int creditAmount = CreditTierConstants.getCreditAmount(tierIndex);

        // Validate tier for plan (e.g. Starter max tier)
        try {
            CreditTierConstants.validateTierForPlan(tierIndex, planCode);
        } catch (IllegalArgumentException e) {
            log.warn("Credit pack tier {} not valid for plan {}: {}. Granting anyway (Stripe already charged).",
                    tierIndex, planCode, e.getMessage());
        }

        BigDecimal amount = BigDecimal.valueOf(creditAmount);
        log.info("Granting {} pack credits (tier={}, cost={}) to userId={}",
                creditAmount, tierIndex, creditQuantity, userId);

        CreditConsumeResult result = creditService.grantCredits(userId, amount, "PURCHASE", sourceId,
                "Credit pack: tier " + tierIndex + " (" + creditAmount + " credits)");
        if (!result.success()) {
            log.error("Failed to grant pack credits for userId={}, tier={}: {}",
                    userId, tierIndex, result.error());
            throw new IllegalStateException("Credit pack grant failed: " + result.error());
        }
    }

    /**
     * Reset balance to zero before renewal re-grant.
     * Creates a PLAN_RESET ledger entry for audit trail.
     *
     * <p><b>Contract: {@code subscription} MUST be a managed instance carrying the row's
     * live values</b> - callers get one from {@link #resolveManagedForUpdate}. This method
     * both reads the balance (to record how much the reset absorbed) and zeroes it, so a
     * detached or stale instance produces a {@code PLAN_RESET} row whose amount does not
     * match what was actually absorbed, and permanently mis-states the ledger. That is not
     * hypothetical: it happened in production on an account whose wallet had moved between
     * load and reset.
     */
    private void resetBalance(Long userId, String sourceId, Subscription subscription) {
        if (ledgerRepository.existsBySourceId(sourceId)) {
            log.info("Balance already reset for sourceId={}, skipping", sourceId);
            return;
        }

        BigDecimal currentBalance = subscription.getRemainingCredits() != null ? subscription.getRemainingCredits() : BigDecimal.ZERO;
        if (currentBalance.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Balance already zero for userId={}, no reset needed", userId);
            return;
        }

        // Set balance to zero
        subscription.setRemainingCredits(BigDecimal.ZERO);
        subscriptionRepository.save(subscription);

        // Create ledger entry for audit trail
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setUserId(userId);
        entry.setAmount(currentBalance.negate()); // negative of current balance to bring to zero
        entry.setBalanceAfter(BigDecimal.ZERO);
        entry.setSourceType("PLAN_RESET");
        entry.setSourceId(sourceId);
        entry.setDescription("Balance reset on renewal (previous balance: " + currentBalance + ")");
        ledgerRepository.save(entry);

        log.info("Balance reset for userId={}: {} -> 0 (sourceId={})", userId, currentBalance, sourceId);
    }

}
