/**
 * The starter message proposed in the chat composer right after onboarding.
 *
 * <p><b>Why it exists.</b> A new account lands on an empty composer under a
 * generic title, so the one thing the product most needs to show (that the
 * chat builds the thing for you) is left for the user to guess. Onboarding
 * already asked what they want to automate first, so the first screen after it
 * can propose the sentence instead of an empty box.
 *
 * <p><b>Proposed, never sent.</b> The prompt is written into the composer and
 * the user presses send. Auto-sending would spend credits on a message nobody
 * read, and on the Free plan a chat turn draws the pay-as-you-go bucket that a
 * new account has at zero, so an auto-send would open the account on a refusal.
 *
 * <p><b>It is proposed even to an account that cannot pay for it</b>, which is
 * deliberate and worth stating because it is the same boundary that got the
 * credit-gift modal deleted. `useMonthlyCreditsCannotPay` could suppress the
 * proposal on a Free account with no top-up, and it is not consulted: showing
 * the sentence is showing what the product does, while an empty composer plus
 * an upgrade badge shows nothing at all. The difference from the gift modal is
 * that this makes no promise. The gift said "explore all features right away"
 * about credits that could not fund a chat turn; this puts a request in a box
 * the user chooses to send, next to the model picker's own upgrade badge.
 *
 * <p><b>Enough information, or nothing.</b> The goal is the whole signal: a
 * user who picked "Something else" (or skipped onboarding) has told us nothing
 * specific, and a generic "build me a workflow" would be worse than the empty
 * composer, which at least carries the rotating title and the suggestion chips.
 * So an unmapped goal returns null and the composer is left alone. The tools
 * are a bonus that sharpens a prompt we would already propose, never a reason
 * to propose one.
 *
 * <p><b>Why not the home suggestion chips.</b> `HomeSuggestionChips` already
 * holds starter prompts and writes them into this same composer, so the overlap
 * is deliberate rather than overlooked. They answer different questions: a chip
 * is a fully specified automation the user picks off a shelf ("summarize my
 * inbox each morning" with a channel and a time already chosen), while this is
 * the one thing THIS account said it came to do, phrased as a request the agent
 * still has to work out with them. Folding them together would mean either
 * losing the personalisation or making the chips depend on onboarding answers
 * that most sessions no longer have. The chips stay on screen under the
 * composer, so a user who prefers one of them simply clicks it.
 */

import { DRAFT_MAX_AGE_MS } from '@/lib/chat/draftStorage';

/** sessionStorage key carrying the proposal from onboarding to the chat. */
export const FIRST_BUILD_PROMPT_KEY = 'lc_first_build_prompt';

/**
 * How long a proposal stays restorable.
 *
 * <p>sessionStorage lives as long as the tab, which is much longer than the
 * moment this belongs to: land on the chat, go off to Workflows, come back two
 * hours later, and without an age guard the composer fills with a sentence from
 * a forgotten onboarding. That is the same problem, in the same storage, for
 * the same composer as the draft TTL in `lib/chat/draftStorage`, which exists
 * because a stale restore was re-sent in production and created a duplicate
 * conversation. So the window is IMPORTED rather than copied: the proposal and
 * the draft it defers to must expire together, or the guard in
 * `useFirstBuildPromptProposal` starts comparing two different notions of
 * "still fresh".
 */
export const FIRST_BUILD_PROMPT_MAX_AGE_MS = DRAFT_MAX_AGE_MS;

/**
 * primaryGoal value to its i18n key under `onboarding.firstBuild.goalPrompts`.
 *
 * <p>Covers both vocabularies: the cloud PRIMARY_GOALS and the CE use-cases the
 * self-hosted onboarding asks instead. `other` is deliberately in NEITHER: it
 * is the answer that means "I did not tell you", and mapping it to some default
 * prompt would put words in the user's mouth on their very first screen.
 *
 * <p>Kept in sync with the onboarding option lists by
 * `__tests__/firstBuildPromptCatalogParity.test.ts`, which fails when an option
 * is added there without a prompt here. Without it the drift is silent: an
 * unmapped goal simply proposes nothing.
 *
 * <p><b>Why a map at all, when every entry is just kebab-to-camel.</b> Deriving
 * the key would remove this table and half that test, and it would also remove
 * the place where `other` is excluded. That exclusion is the whole editorial
 * decision here (see above), and a derivation would turn it into a special case
 * hidden in a helper, or worse, silently produce `firstBuild.goalPrompts.other`
 * and require a message for it. An explicit list of what we have written a
 * sentence for is also what makes the parity test able to say "you added an
 * option and nobody wrote its prompt" rather than "a message is missing".
 */
export const GOAL_PROMPT_KEYS: Readonly<Record<string, string>> = {
  // Cloud
  'email-follow-ups': 'emailFollowUps',
  'content-publishing': 'contentPublishing',
  'lead-generation': 'leadGeneration',
  'customer-support': 'customerSupport',
  reporting: 'reporting',
  'data-sync': 'dataSync',
  'monitoring-alerts': 'monitoringAlerts',
  'ai-assistant': 'aiAssistant',
  // CE (self-hosted) use-case vocabulary
  'internal-automation': 'internalAutomation',
  'private-assistants': 'privateAssistants',
  'data-pipelines': 'dataPipelines',
  'tool-orchestration': 'toolOrchestration',
  'team-workspaces': 'teamWorkspaces',
  'marketplace-publishing': 'marketplacePublishing',
  'evaluation-sandbox': 'evaluationSandbox',
};

/**
 * Tool value to its label key under `onboarding.tools`. `other` is absent for
 * the same reason as above: "Other" names no tool the agent could wire up.
 */
export const TOOL_LABEL_KEYS: Readonly<Record<string, string>> = {
  gmail: 'gmail',
  outlook: 'outlook',
  'google-sheets': 'googleSheets',
  slack: 'slack',
  notion: 'notion',
  hubspot: 'hubspot',
  salesforce: 'salesforce',
  shopify: 'shopify',
  stripe: 'stripe',
  github: 'github',
  discord: 'discord',
  telegram: 'telegram',
  linkedin: 'linkedin',
  airtable: 'airtable',
};

/**
 * At most three tools are named. The clause exists to make the prompt concrete,
 * and a user who ticked eight integrations would get a sentence that reads as a
 * list rather than as a request.
 */
const MAX_TOOLS_NAMED = 3;

export interface FirstBuildPromptInput {
  /** The single onboarding "what do you want to automate first" answer. */
  primaryGoal: string | null | undefined;
  /** The multi-select "tools you already work with" answer. */
  toolsUsed: string[] | null | undefined;
  /** App locale, used to join the tool names the way that language does. */
  locale: string;
  /** next-intl translator bound to the `onboarding` namespace. */
  t: (key: string, values?: Record<string, string>) => string;
}

type Translator = FirstBuildPromptInput['t'];

/**
 * Resolve a message, or null when it did not resolve to real text.
 *
 * <p>A missing message does NOT come back blank: next-intl's default fallback
 * returns the full key path, so `firstBuild.goalPrompts.reporting` resolves to
 * the string "onboarding.firstBuild.goalPrompts.reporting". That is non-blank,
 * so a blank check alone would put a raw key path into the first message a new
 * user ever sees in the composer. Anything that still ends with the key we
 * asked for is therefore treated as absent.
 *
 * <p>next-intl also exposes `t.has(key)`, which answers this exactly. It is not
 * used because it would widen this module's translator parameter from a plain
 * function to next-intl's own type, and the callers (and every test) would have
 * to supply it. The trade is deliberate: the only case this heuristic gets
 * wrong is a translation whose real text ends with its own key path, which
 * would read as broken anyway.
 */
function message(t: Translator, key: string, values?: Record<string, string>): string | null {
  let resolved: string;
  try {
    resolved = t(key, values);
  } catch {
    // A translator that throws on a missing message is as good as no message.
    return null;
  }
  const trimmed = (resolved ?? '').trim();
  if (!trimmed || trimmed.endsWith(key)) return null;
  return trimmed;
}

/** Join tool names with the locale's own conjunction ("A, B and C" / "A, B et C"). */
function formatToolList(labels: string[], locale: string): string {
  try {
    // Not available in every runtime (older Safari, some test environments);
    // the comma join below is a correct, if less idiomatic, fallback.
    const ListFormat = (Intl as unknown as { ListFormat?: typeof Intl.ListFormat }).ListFormat;
    if (ListFormat) {
      return new ListFormat(locale, { style: 'long', type: 'conjunction' }).format(labels);
    }
  } catch {
    /* fall through */
  }
  return labels.join(', ');
}

/**
 * Build the proposal, or null when onboarding did not say enough to write one.
 */
export function buildFirstBuildPrompt({
  primaryGoal,
  toolsUsed,
  locale,
  t,
}: FirstBuildPromptInput): string | null {
  const goalKey = primaryGoal ? GOAL_PROMPT_KEYS[primaryGoal] : undefined;
  if (!goalKey) return null;

  const base = message(t, `firstBuild.goalPrompts.${goalKey}`);
  if (!base) return null;

  // Resolve BEFORE slicing: cutting to three first means one unresolvable label
  // among them silently yields two names while a fourth, perfectly good tool
  // was sitting right there.
  const labels = (toolsUsed ?? [])
    .map(value => TOOL_LABEL_KEYS[value])
    .filter((key): key is string => Boolean(key))
    .map(key => message(t, `tools.${key}`))
    .filter((label): label is string => Boolean(label))
    .slice(0, MAX_TOOLS_NAMED);

  if (labels.length === 0) return base;

  const clause = message(t, 'firstBuild.toolsClause', { tools: formatToolList(labels, locale) });
  // The goal sentence alone is a perfectly good prompt, so a clause that did not
  // resolve is dropped rather than allowed to corrupt the one we have.
  if (!clause) return base;

  return `${base}${sentenceSeparator(t)}${clause}`;
}

/**
 * What goes between the two sentences. A space in the Latin locales, nothing in
 * Chinese, where a space after the full stop is wrong.
 *
 * <p>Deliberately NOT routed through {@link message}: the whole point of this
 * one is that an empty string is its correct value in some locales. Only
 * whitespace (including none) is accepted, so a missing or mistranslated
 * message falls back to a plain space rather than splicing a key name into the
 * middle of the sentence.
 */
function sentenceSeparator(t: Translator): string {
  let separator: string;
  try {
    separator = t('firstBuild.promptSeparator');
  } catch {
    return ' ';
  }
  // Anything that is not a string is an unusable message, exactly like a throw
  // or a key echo, so it takes the SAME fallback: a plain space. Returning ''
  // here instead would glue the two sentences together ("...urgence.Je
  // travaille..."), which is the corruption this function exists to prevent
  // rather than a second kind of success.
  if (typeof separator !== 'string') return ' ';
  // Normalise FIRST, then decide. Testing `(separator ?? '')` while returning
  // the raw `separator` splices the literal text "undefined" between the two
  // sentences: the predicate has to be applied to the value that is returned.
  return separator.trim().length === 0 ? separator : ' ';
}

/**
 * What the sessionStorage slot holds: the text plus when it was written.
 *
 * <p>Deliberately shaped like `draftStorage`'s own record, and deliberately NOT
 * shared with it. Sharing would mean exporting a generic TTL'd-slot helper from
 * a module whose read path was hardened around a production incident, to save
 * about fifteen lines, and the two are not actually the same contract: this one
 * purges on EVERY read (the proposal is one-shot) while a draft is purged only
 * when it fails to restore, and it rejects whitespace-only text where a draft
 * only rejects empty. The TTL is the one thing that must agree, so that is the
 * one thing imported.
 */
interface StoredProposal {
  /** Proposal text. */
  v: string;
  /** Epoch ms of the write (drives the freshness TTL). */
  t: number;
}

/**
 * sessionStorage, or null where there is none. Mirrors `draftStorage.getStore`:
 * this module is imported by a page that renders on the server, where touching
 * `sessionStorage` is a ReferenceError rather than a storage failure.
 */
function store(): Storage | null {
  try {
    if (typeof window === 'undefined') return null;
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * Hand the proposal to the chat, or clear the slot when there is none.
 *
 * <p>It SETS the slot rather than only writing to it, because "no proposal" has
 * to be able to overwrite a previous one. Reaching onboarding twice in a tab is
 * possible (a completion whose status has not settled bounces back), so a
 * second pass answering "Something else" must not leave the first pass's
 * sentence waiting in the composer. Returning early on null would do exactly
 * that, and the symptom is a stale proposal, not a missing one.
 */
export function storeFirstBuildPrompt(prompt: string | null, now: number = Date.now()): void {
  if (!prompt || !prompt.trim()) {
    clearFirstBuildPrompt();
    return;
  }
  const slot = store();
  if (!slot) return;
  try {
    const stored: StoredProposal = { v: prompt, t: now };
    slot.setItem(FIRST_BUILD_PROMPT_KEY, JSON.stringify(stored));
  } catch {
    /* a proposal is a nicety - never let storage being unavailable break onboarding */
  }
}

/**
 * Drop any parked proposal.
 *
 * <p>For the skip path: skipping says the user told us nothing, so a proposal
 * left over from an earlier completion in the same tab must not be the sentence
 * that greets them. Reaching onboarding twice is possible (a completion whose
 * status has not settled bounces back), and without this the stale proposal
 * would still be waiting in the composer.
 */
export function clearFirstBuildPrompt(): void {
  const slot = store();
  if (!slot) return;
  try {
    slot.removeItem(FIRST_BUILD_PROMPT_KEY);
  } catch {
    /* nothing to clear if storage is unavailable */
  }
}

/**
 * Read the proposal once. The key is removed on read, so a second chat surface
 * mounting later (or a back-navigation to the home) does not re-fill a composer
 * the user has already cleared. A proposal older than
 * {@link FIRST_BUILD_PROMPT_MAX_AGE_MS} is purged rather than returned.
 */
export function consumeFirstBuildPrompt(now: number = Date.now()): string | null {
  const slot = store();
  if (!slot) return null;

  let raw: string | null;
  try {
    raw = slot.getItem(FIRST_BUILD_PROMPT_KEY);
    if (raw !== null) slot.removeItem(FIRST_BUILD_PROMPT_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as Partial<StoredProposal> | null;
    if (!parsed || typeof parsed.v !== 'string' || typeof parsed.t !== 'number') return null;
    if (!parsed.v.trim()) return null;
    // `< MAX` is fresh, so the boundary itself is stale. Written this way round
    // to match `draftStorage.readDraft` exactly: sharing the constant while
    // flipping the comparison would leave the proposal alive for the one
    // millisecond in which the draft it defers to is already gone.
    if (!(now - parsed.t < FIRST_BUILD_PROMPT_MAX_AGE_MS)) return null;
    return parsed.v;
  } catch {
    // Not our shape (hand-edited, or written by an older build): discard it
    // rather than guess. It was already removed above.
    return null;
  }
}
