import type { GenerationLimit, GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * What one generation model accepts, turned into the fields a surface has to draw.
 *
 * <p><b>Why this is a module and not a render loop.</b> The rule "which controls does this model
 * get" was written inside the generation dialog, where it decides the shape of a form, the number
 * of file pickers, which fields block the submit and which values are a closed choice. The studio
 * composer has to answer the same question, and answering it a second time is how the two drift:
 * one surface gains a model's third image slot and the other keeps offering one, or one enforces a
 * required parameter the other lets through to be refused by the provider after the call is paid
 * for.
 *
 * <p>So the model's own declaration is read ONCE, here, and both surfaces render what comes back.
 * Nothing in this file knows what a control looks like.
 */

/*
 * There is deliberately no TEXT_PARAMS list.
 *
 * The dialog this replaced kept one, and it was a liability: `text` is what a parameter is when it
 * is neither a file, nor a declared choice, nor a number, so a list of text parameters is a copy of
 * a fact derived elsewhere. It went stale the moment the catalogue shipped a parameter nobody added
 * to it - and the symptom was not an error, it was a field the reader could not fill in.
 */

/** Unified parameters entered as a number. */
export const NUMBER_PARAMS = ['duration_seconds', 'n', 'seed'] as const;

/**
 * Parameters that carry a FILE rather than a value.
 *
 * <p>They cannot share the text field the others use: the platform needs the bytes, so what travels
 * is the whole file handle an upload returns. A path or a URL typed into a text box reaches the
 * backend and is refused there, which is a worse place to learn it.
 */
export const ASSET_PARAMS = ['input_image', 'input_audio', 'input_video'] as const;

/** What the picker offers per slot, so the reader is not shown every file they own. */
export const ASSET_ACCEPT: Record<string, string> = {
  input_image: 'image/*',
  input_audio: 'audio/*',
  input_video: 'video/*',
};

/**
 * The most file slots one parameter will ever draw.
 *
 * <p>A ceiling, not a rule: it exists because `maxItems` comes from a provider descriptor, and a
 * surface that trusts it without bound draws whatever number lands there. Eight is past every
 * model the catalogue ships and still fits on a screen.
 */
export const MAX_ASSET_SLOTS = 8;

export type StudioFieldKind = 'asset' | 'choice' | 'number' | 'text';

export interface StudioField {
  /** The unified parameter name, which is also the key it is sent under. */
  name: string;
  kind: StudioFieldKind;
  /** True when the provider refuses the call without it. */
  required: boolean;
  /** The model's declared restriction, when it has one. */
  limit?: GenerationLimit;
  /**
   * For an asset field: what the file IS to this model ('first_frame', 'source_image', ...), as
   * declared by the provider's descriptor. Undefined when the model does not say.
   */
  role?: string;
  /** For an asset field: how many files this parameter takes. At least 1, capped at MAX_ASSET_SLOTS. */
  slots: number;
  /** What the picker accepts for an asset field. */
  accept?: string;
  /**
   * Discrete values the model declares. Empty when the parameter is free-form OR when the values
   * exist but only the provider can name them - `optionsMustBeFetched` tells those two apart.
   */
  choices: string[];
  /**
   * True when the values belong to the caller's own account and have to be asked for with their
   * key (an ElevenLabs voice, say). The surface fetches them when the field is opened; until then
   * the field stays free-form, because a closed choice over nothing is a dead end.
   */
  optionsMustBeFetched: boolean;
  /**
   * True when the declared values are a SUGGESTION and a value outside them is accepted anyway.
   * A surface that turns such a list into a closed choice forbids values the platform allows.
   */
  choicesAreSuggestions: boolean;
}

/**
 * Order the fields the way the decision is actually made: the files first (they are the subject),
 * then the closed choices, then numbers, then free text.
 *
 * <p>Within a group the model's own order is kept. Sorting alphabetically instead would put
 * `aspect_ratio` above `input_image` on a model whose whole point is the image.
 */
const KIND_ORDER: Record<StudioFieldKind, number> = { asset: 0, choice: 1, number: 2, text: 3 };

function fieldKind(name: string, limit: GenerationLimit | undefined): StudioFieldKind {
  if ((ASSET_PARAMS as readonly string[]).includes(name)) return 'asset';
  // A closed choice needs values to choose FROM. `optionsAvailable` says the values exist but must
  // be fetched, which is still a choice field - the surface fills it when the field is opened.
  if (limit?.optionsAvailable) return 'choice';
  if (limit?.allowed && limit.allowed.length > 0) return 'choice';
  if ((NUMBER_PARAMS as readonly string[]).includes(name)) return 'number';
  return 'text';
}

/**
 * The fields to draw for one model.
 *
 * <p>Driven by `accepts`, which is the model's own list: anything outside it is REFUSED by the
 * platform, so drawing a control for it would offer the reader a way to fail. The prompt is
 * excluded because every surface gives it its own place (a composer's textarea, a form's first
 * field) rather than one row among the parameters.
 */
export function buildParamSpec(model: GenerationModel | null | undefined): StudioField[] {
  if (!model) return [];
  const required = new Set(model.required ?? []);
  const fields = (model.accepts ?? [])
    .filter((name) => name !== 'prompt')
    .map<StudioField>((name) => {
      const limit = model.limits?.[name];
      const kind = fieldKind(name, limit);
      const shape = model.inputs?.[name];
      return {
        name,
        kind,
        required: required.has(name),
        limit,
        role: shape?.role,
        // As many pickers as the provider takes files. One flat picker on a model that composes
        // three images hides two thirds of what it can do; more pickers than it accepts invites a
        // refusal.
        slots: kind === 'asset'
          ? Math.max(1, Math.min(shape?.maxItems ?? 1, MAX_ASSET_SLOTS))
          : 1,
        accept: kind === 'asset' ? ASSET_ACCEPT[name] : undefined,
        choices: (limit?.allowed ?? []).map((value) => String(value)),
        optionsMustBeFetched: !!limit?.optionsAvailable,
        // Absent means enforced, which is the ordinary case.
        choicesAreSuggestions: limit?.allowedEnforced === false,
      };
    });
  return fields.sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind]);
}

/**
 * The file-carrying fields, which is what a composer's attachment control has to become.
 *
 * <p>This is the whole substance of "the + adapts to the model": an attachment button on a model
 * that takes no file is a control that can only produce a refusal, and one flat "attach" on a model
 * that takes a first frame AND a reference image cannot say which is which.
 */
export function assetFields(spec: StudioField[]): StudioField[] {
  return spec.filter((field) => field.kind === 'asset');
}

/** Total files this model will accept across every slot. Zero means: offer no attachment control. */
export function totalAssetSlots(spec: StudioField[]): number {
  return assetFields(spec).reduce((sum, field) => sum + field.slots, 0);
}

/**
 * Required fields that are still empty, by name.
 *
 * <p>Only the FIRST slot of a multi-file parameter counts, exactly as the provider treats it: a
 * parameter taking up to four images requires one, not four.
 */
export function missingRequired(
  spec: StudioField[],
  values: Record<string, unknown>,
  assets: Record<string, (unknown | undefined)[]>,
): string[] {
  return spec
    .filter((field) => field.required)
    .filter((field) => {
      if (field.kind === 'asset') return !assets[field.name]?.[0];
      const value = values[field.name];
      return value === undefined || value === null || String(value).trim() === '';
    })
    .map((field) => field.name);
}

/**
 * A file handle carried by a parameter. Structural on purpose: this module must not depend on the
 * file service, and the only thing it does with a handle is pass it through.
 */
export interface AssetHandle {
  id?: string;
  path?: string;
  name?: string;
}

/**
 * What a turn will actually SEND, from what the reader typed and attached.
 *
 * <p><b>Why this is a function and not a loop inside the composer.</b> It is the money-critical
 * decision on this path - a parameter the model does not declare is REFUSED by the platform, and the
 * refusal names nothing the reader can act on, so the turn fails with its cause nowhere on screen.
 * Written inline it was unreachable by a test: the composer filters at three points (on reuse, on
 * model change, on submit) and any two of them MASK the third, so deleting any one left the suite
 * green. A pure function can be handed the exact state each layer is meant to catch.
 *
 * <p>Values are dropped, never coerced into something the model might accept: guessing is how a
 * reader ends up paying for a generation they did not describe.
 */
export function buildSubmissionParams(
  spec: StudioField[],
  values: Record<string, unknown>,
  assets: Record<string, (AssetHandle | undefined)[]>,
): Record<string, unknown> {
  const accepted = new Map(spec.map((field) => [field.name, field]));
  const params: Record<string, unknown> = {};

  for (const [name, value] of Object.entries(values)) {
    if (value === undefined || value === null || String(value).trim() === '') continue;
    const field = accepted.get(name);
    if (!field) continue;
    if (field.kind === 'number') {
      const numeric = Number(value);
      // Unparseable text becomes NaN, which JSON.stringify writes as null: the provider then
      // refuses a value the reader never typed.
      if (!Number.isFinite(numeric)) continue;
      params[name] = numeric;
      continue;
    }
    params[name] = value;
  }

  for (const [name, list] of Object.entries(assets)) {
    const field = accepted.get(name);
    if (!field || field.kind !== 'asset') continue;
    // Sent packed and capped: the platform reads a list of handles, a hole in it is not a file, and
    // more handles than the model takes is a refusal.
    const files = (list ?? []).filter(Boolean).slice(0, field.slots) as AssetHandle[];
    if (files.length > 0) params[name] = files;
  }

  return params;
}

/**
 * True when this parameter's value would survive into a submission on this model.
 *
 * <p>Used to decide whether there is anything to send at all. Counting raw state instead lets a file
 * attached on a PREVIOUS model unblock the send button on a model that does not take one.
 */
export function hasSubmittableInput(
  spec: StudioField[],
  values: Record<string, unknown>,
  assets: Record<string, (AssetHandle | undefined)[]>,
): boolean {
  return Object.keys(buildSubmissionParams(spec, values, assets)).length > 0;
}
