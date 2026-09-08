/**
 * Single source of truth for pricing constants and calculation logic.
 * Used by: pricing page, insufficient credits modal, billing tests.
 *
 * Mirrors CreditTierConstants.java; keep both in sync.
 *
 * Pricing curve revised 2026-05-27:
 * - Pro/Starter credit packs are degressive down to $0.70 / 1k credits.
 * - Team has explicit premium pack costs down to $0.80 / 1k credits.
 * - PAYG stays separate at $1.25 / 1k credits.
 * - Yearly discount applies to the base plan only, not to credits.
 */

/**
 * Public GitHub repository for the self-hosted Community Edition.
 * Single source of truth for the "Self-hosted" deployment CTA across every pricing
 * surface (landing, settings, insufficient-credits modal). The CE tree is published
 * by `export-ce.sh --push <giturl>`; update this constant to the final public repo URL.
 */
export const SELF_HOSTED_GITHUB_URL = 'https://github.com/livecontext-ai/livecontext-ce';

/**
 * Canonical credits→USD "list" scale: 1 credit = $0.001 USD. Defined backend-side
 * (ModelPricingService / migration V80) - calculateCost divides provider USD/1M-token
 * rates by 1000, so a stored credit amount IS the dollar cost × 1000. CE shows spend in
 * dollars (see lib/format-cost.ts), so credit-denominated ledger amounts - the local CE
 * ledger AND the cloud-linked relay mirror - are multiplied by this to render real dollars.
 *
 * NOTE: this is the list scale, NOT the per-pack purchase price (those vary $0.0008-$0.00125
 * per credit, see CREDIT_COSTS/CREDIT_TIERS), and the cloud LLM billing multiplier (×1.11) is
 * already baked into the stored credit amount - so the displayed $ is the billed value,
 * margin included (the deliberate, balance-reconciling choice over stripping the multiplier).
 */
export const CREDIT_LIST_USD = 0.001;

/**
 * PAYG one-time top-up purchase price: $1.25 per 1,000 credits (see the header note).
 * Used to render the referral reward's dollar value from its configured credit amount.
 */
export const PAYG_USD_PER_1K = 1.25;

/**
 * What the entry credit pack (`CREDIT_TIERS[0]`) actually buys, in the three
 * shapes of work a reader recognises. Rendered inside the FAQ answer that asks
 * the question (see CREDIT_EXAMPLES_FAQ_KEY) and quoted by the plan comparison,
 * so the figures live here rather than inside translation strings: six locales
 * quoting six independently-edited numbers is exactly how a public price claim
 * goes stale.
 *
 * WHY THREE FIGURES AND NOT ONE. The cost of a "conversation" is not one
 * number: a plain question and answer sends the prompt once, while an agent
 * building a workflow re-sends the whole transcript on every tool round-trip,
 * and it makes dozens of them to search the tool catalogue, create and wire the
 * nodes and test the run. Measured, the two differ by ~3.5x, and a
 * classification step by another two orders of magnitude. A single average
 * would be true of almost no one, and would read as either alarming or
 * dishonest depending on which reader saw it.
 *
 * Derivation (2026-09-03, recompute when the LLM billing multiplier moves):
 * every real chat conversation on the platform (turns summed per conversation)
 * and every classify execution, re-priced on Claude Sonnet 5 rates ($2 / $10 per
 * 1M tokens) with the platform's cache weighting and the current multiplier.
 * MEDIANS: conversation with no tool call 80 credits (n=19), agent conversation
 * that calls tools 285 credits (n=310, 4.8 turns and 44 tool calls on average),
 * classify step 2.9 credits (n=2,649). Each `creditsEach` below is that median
 * rounded to a legible number, and each `perEntryPack` is 5,000 / creditsEach
 * rounded DOWN to a legible one (62.5 -> 60, 16.7 -> 15, 1,666 -> 1,500), never
 * merely floored: a page that promised "16 workflow-building conversations"
 * would read as a measurement rather than the estimate it is. Down, always, so
 * the page cannot over-promise; `credit-conversation-copy.test.ts` pins both
 * ends of that (a multiple of 5, and never less than 60% of the pack). Both
 * tails are wide
 * (agent p25 101, p75 759), which is why the copy says a short exchange goes
 * much further and a long one costs more.
 *
 * WHAT THE COPY MAY CLAIM FOR `agentChat`. The measured unit is ONE agent
 * CONVERSATION of that median shape, not one finished piece of work. The page
 * names what such a conversation is typically for (building a workflow: the
 * agent searches the catalogue, wires nodes and tests the run) because that is
 * what makes it the expensive end and a reader recognises it. It must NOT
 * promise a completed build for that price: the copy states the measured shape
 * (around 5 turns and 45 tool calls, the measured 4.8 and 44 rounded to legible
 * numbers and never DOWN, so the sentence cannot understate the very driver it
 * invokes) and says a longer build costs more. Widen
 * that claim only after re-measuring build sessions end to end.
 */
export interface CreditExample {
  /** Labelled by `pricing.faq.<CREDIT_EXAMPLES_FAQ_KEY>.examples.<id>`. */
  id: string;
  /** Typical cost of one, in credits. */
  creditsEach: number;
  /** How many of them the entry pack covers, rounded down. */
  perEntryPack: number;
}

export const CREDIT_EXAMPLES: readonly CreditExample[] = [
  { id: 'simpleChat', creditsEach: 80, perEntryPack: 60 },
  { id: 'agentChat', creditsEach: 300, perEntryPack: 15 },
  { id: 'classifyStep', creditsEach: 3, perEntryPack: 1500 },
];

/**
 * The range, low end first: the agent conversation is the floor, the plain one
 * the ceiling.
 *
 * <p>NO English message quotes these today. The credits tooltip used to divide
 * the entry pack by them and now states per-conversation prices instead, which
 * is the only phrasing true of a plan with no pack and no slider. They are
 * still supplied to every message that takes the credit facts, so a locale may
 * phrase an answer with the counts, and the FAQ figures are still derived from
 * them (see the invariants in credit-conversation-copy.test.ts).
 */
export const AGENT_CONVERSATIONS_PER_PACK = 15;
export const SIMPLE_CONVERSATIONS_PER_PACK = 60;

/**
 * The entry pack expressed the way a reader thinks about it, ready to
 * interpolate into any message that quotes it.
 *
 * <p>Lives here, next to the figures, because FOUR surfaces quote them and they
 * must never disagree: the pricing page's FAQ, the plan cards on that page, the
 * same cards on the public landing, and the plan-comparison table.
 *
 * <p>Pass the WHOLE object, never a hand-picked subset. Two surfaces used to
 * build their own, and the comparison table listed three values by name; when
 * the credits tooltip was rewritten to quote per-conversation prices, the table
 * started rendering its raw message path and throwing an IntlError on every
 * render, in all six locales, while the cards were fine. A message may use any
 * fact; a caller that offers only some decides for it.
 *
 * <p>Formatted against the APP locale, never the browser's, so a /fr visitor
 * reads "5 000" on the server-rendered landing and after hydration alike.
 */
export function creditFactsFor(locale: string): Record<string, string | number> {
  const creditsOf = (id: string) =>
    (CREDIT_EXAMPLES.find((example) => example.id === id)?.creditsEach ?? 0).toLocaleString(locale);
  return {
    credits: CREDIT_TIERS[0].toLocaleString(locale),
    agentConversations: AGENT_CONVERSATIONS_PER_PACK,
    simpleConversations: SIMPLE_CONVERSATIONS_PER_PACK,
    agentCredits: creditsOf('agentChat'),
    simpleCredits: creditsOf('simpleChat'),
    classifyCredits: creditsOf('classifyStep'),
  };
}

/**
 * The FAQ entry the worked examples are rendered under.
 *
 * They used to be a band of their own above the FAQ, which asked the reader the
 * same question twice and answered it in two places with two sets of words. The
 * figures now sit inside the answer, so the page states the estimate exactly
 * once. Its messages live at `pricing.faq.<key>.examplesCaption` and
 * `pricing.faq.<key>.examples.<exampleId>.{count,detail}`; moving the block to
 * another question means moving those keys in every locale file.
 */
export const CREDIT_EXAMPLES_FAQ_KEY = 'conversationCost';

/**
 * The pricing page's FAQ, in reading order. This list is the ONLY place an entry
 * is added, removed or reordered; each key needs `pricing.faq.<key>.question`
 * and `.answer` in EVERY locale file. Answers are rendered with the page's
 * credit facts, so any of them may interpolate {credits}, {agentConversations},
 * {simpleConversations}, {agentCredits}, {simpleCredits} or {classifyCredits}
 * without touching the component.
 */
export const FAQ_KEYS = [
  'conversationCost',
  'exceedCredits',
  'rollover',
  'changePlans',
  'payAsYouGo',
  'sharedStorage',
] as const;

export const CREDIT_TIERS = [5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000];
export const CREDIT_COSTS = [0, 10, 22, 42, 80, 185, 365, 720, 3_500, 7_000];
export const TEAM_CREDIT_COSTS = [0, 15, 30, 55, 100, 230, 430, 825, 4_000, 8_000];

export const BASE_PRICES: Record<string, number> = {
  starter: 10,
  pro: 24,
  team: 49,
};

export const STARTER_MAX_CREDITS = 100_000;

/**
 * Highest tier index shown on the slider by default (index 7 = 1,000,000 credits).
 * The two tiers above (5M, 10M) carry intimidating prices for a casual visitor, so
 * they are hidden behind the `?tiers=full` unlock (see resolveMaxTierIndex). The
 * underlying CREDIT_TIERS array stays 10-long - the cap is display-only and the
 * backend (CreditTierConstants.java) keeps accepting indices 0-9.
 */
export const DEFAULT_MAX_TIER_INDEX = 7;

/**
 * Resolve the slider's max selectable index.
 * Full range (last index, 10M) when the hidden tiers are explicitly unlocked OR when
 * the user's current subscription already sits on a hidden tier - otherwise an existing
 * 5M/10M customer would be silently clamped down to 1M and could downgrade by accident.
 */
export function resolveMaxTierIndex(fullTiersUnlocked: boolean, subscriptionTierIndex = 0): number {
  if (fullTiersUnlocked || subscriptionTierIndex > DEFAULT_MAX_TIER_INDEX) {
    return CREDIT_TIERS.length - 1;
  }
  return DEFAULT_MAX_TIER_INDEX;
}

/**
 * Clamp a selected tier index into [0, maxTierIndex]. Used to keep the selected index in
 * sync with the slider cap: when the cap shrinks (e.g. the hidden tiers get re-hidden while
 * the user is parked on 5M/10M), the index must follow so the price/checkout never reflect a
 * tier the slider no longer shows.
 */
export function clampTierIndex(tierIndex: number, maxTierIndex: number): number {
  return Math.min(Math.max(tierIndex, 0), maxTierIndex);
}

/**
 * Ordered feature-label keys per plan card. Each key maps to a single i18n string at
 * `pricing.planCards.features.<key>` (shared keys are translated once). The sentinel
 * 'creditsDynamic' is rendered in the component with the live slider amount.
 *
 * Lists are authored as explicit supersets: every tier visibly includes everything the
 * tier below it offers, so Enterprise never appears to have fewer features than Team.
 * The coherence is enforced by a unit test via CAPABILITY_KEYS.
 *
 * That applies to the SCALED dimensions too, and Enterprise carried no support key at
 * all until 2026-08-31: on a card the omission merely showed one bullet fewer, but read
 * across plans (see plan-comparison.ts) it stated that Enterprise includes no support
 * while Team includes support with an SLA. 'supportSla' is therefore its floor, on top
 * of which 'sla999' and 'accountManager' are what Enterprise adds. `plan-comparison`
 * pins the invariant: no dimension may go blank on a plan above one that has a value.
 *
 * The `nodes*` keys are a REPLACED dimension, like support and analytics: each tier
 * states its own node coverage rather than adding to the one below, so they are
 * deliberately absent from CAPABILITY_KEYS.
 */
export const PLAN_FEATURE_KEYS: Record<string, string[]> = {
  free: ['creditsFree', 'nodesCore', 'users1', 'workspaces1', 'variables3', 'concurrent1', 'storage100mb', 'logs7', 'supportCommunity'],
  starter: ['creditsDynamic', 'nodesPublishing', 'users1', 'workspaces1', 'variables25', 'concurrent5', 'storage1gb', 'logs30', 'versioning', 'apiAccess', 'cePlatformCreds', 'analyticsBasic', 'supportEmail'],
  pro: ['creditsDynamic', 'nodesAll', 'users1', 'workspaces3', 'variables100', 'concurrent20', 'storage10gb', 'logs30', 'versioning', 'apiAccess', 'cePlatformCreds', 'vectorSearch', 'browserAgent', 'priorityExecution', 'executionSearch', 'analyticsDetailed', 'supportPriority'],
  team: ['creditsDynamic', 'nodesAll', 'users25', 'workspaces10', 'variables500', 'concurrent50', 'storage100gb', 'logs90', 'versioning', 'apiAccess', 'cePlatformCreds', 'vectorSearch', 'browserAgent', 'priorityExecution', 'executionSearch', 'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling', 'analyticsTeam', 'supportSla'],
  enterprise: ['creditsCustom', 'nodesAll', 'usersUnlimited', 'workspacesUnlimited', 'variablesUnlimited', 'concurrentUnlimited', 'storage1tb', 'logsCustom', 'versioning', 'apiAccess', 'cePlatformCreds', 'vectorSearch', 'browserAgent', 'priorityExecution', 'executionSearch', 'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling', 'dedicatedInstance', 'compliance', 'overageProtection', 'analyticsAdvanced', 'supportSla', 'sla999', 'accountManager', 'onboarding'],
};

/**
 * Purely-additive capability keys (excludes value-scaled dimensions such as
 * credits/users/concurrent/storage/logs and the replaced support/analytics dims).
 * Used by the coherence test: each tier must include every capability of the tier below.
 */
export const CAPABILITY_KEYS = [
  'versioning', 'apiAccess', 'cePlatformCreds', 'vectorSearch', 'browserAgent',
  'priorityExecution', 'executionSearch',
  'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling',
  'dedicatedInstance', 'compliance', 'overageProtection', 'accountManager', 'onboarding',
];

export function getCreditCost(planId: string, creditTierIndex: number): number {
  return planId === 'team'
    ? TEAM_CREDIT_COSTS[creditTierIndex]
    : CREDIT_COSTS[creditTierIndex];
}

/**
 * Yearly-cycle multiplier, applied to the BASE plan price only and never to credits.
 * Exported so a pricing event composes its announced future price through exactly the
 * same formula as the current billed price (see priceFromBase).
 */
export const YEARLY_BASE_MULTIPLIER = 0.8;

/**
 * Compose a displayed monthly price from an arbitrary base price.
 *
 * Shared by calcPrice (the price actually billed today) and by the pricing-event
 * announcement (the higher price a plan moves to once the event window closes), so the
 * two can never drift apart on the yearly discount or on how credits are added.
 */
export function priceFromBase(
  base: number,
  planId: string,
  cycle: 'monthly' | 'yearly',
  creditTierIndex: number
): number {
  const basePrice = cycle === 'yearly' ? Math.round(base * YEARLY_BASE_MULTIPLIER) : base;
  return basePrice + getCreditCost(planId, creditTierIndex);
}

export function calcPrice(planId: string, cycle: 'monthly' | 'yearly', creditTierIndex: number): number {
  const base = BASE_PRICES[planId];
  if (base === undefined) return 0;
  return priceFromBase(base, planId, cycle, creditTierIndex);
}

export function formatTierLabel(tier: number): string {
  if (tier >= 1_000_000) return `${tier / 1_000_000}M`;
  if (tier >= 1_000) return `${tier / 1_000}K`;
  return String(tier);
}
