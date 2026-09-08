'use client';

import * as React from 'react';
import { ArrowUp, Loader2, Paperclip, SlidersHorizontal, X } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
// See components/ui/menu.ts: the bare PopoverContent has no background in this theme.
import { menuItemClass, menuSurfaceClass } from '@/components/ui/menu';
import { fileRefToUrl, fileService, isFileRef, isImageFile, normalizeFileRef, type FileRef } from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';
import type { StudioRequestEnvelope } from '@/lib/generation/studioMessage';
import {
  assetFields,
  buildParamSpec,
  buildSubmissionParams,
  hasSubmittableInput,
  missingRequired,
  totalAssetSlots,
  type StudioField,
} from '@/lib/generation/paramSpec';
import { StudioModelPicker } from '@/components/studio/StudioModelPicker';
import { StudioParamControl } from '@/components/studio/StudioParamControl';
import { assetRoleLabel, type LabelTranslator } from '@/lib/generation/labels';
import { useGenerationOptions } from '@/hooks/useGenerationOptions';
import { useGenerationQuote } from '@/hooks/useGenerationQuote';
import { platformSellsThis } from '@/lib/generation/platformSells';
import { describeQuotedPrice } from '@/lib/generation/price';

/**
 * The composer of the studio: one place to say what to make, on which model, from which files.
 *
 * <p><b>Why the controls are on the composer rather than behind a dialog.</b> Every choice here
 * changes what the next generation costs and what it can even accept, and a dialog puts them behind
 * a round trip: the reader opens it, fills a form, and finds out only at the end that this model
 * takes no reference image, or requires a voice. On the composer the model's own declaration
 * reshapes the row as soon as it is picked, so what is on screen is always exactly what this model
 * accepts.
 *
 * <p><b>The attachment control is not a constant.</b> It appears only when the selected model takes
 * a file, and it offers one named slot per file the model accepts - "First frame" on a model that
 * animates from a still, "Reference image" on one that only borrows a look. A single flat "attach"
 * button would mis-describe both, and on a model that takes no file at all it would be a control
 * whose only possible outcome is a refusal, discovered after the reader has chosen a file.
 *
 * <p>Nothing about chat lives here: no tools, no skills, no per-conversation configuration. A
 * studio turn is submitted on its own, so there is no context to configure.
 */

export interface StudioComposerProps {
  models: GenerationModel[];
  selectedModel: GenerationModel | null;
  onSelectModel: (model: GenerationModel) => void;
  /**
   * Submit a turn. Resolve TRUE only when it was actually recorded.
   *
   * <p>The composer clears the prompt and the attachments on true and on nothing else. Resolving
   * true unconditionally is the bug this signature exists to prevent: a turn refused before it left
   * (no conversation could be created, so nothing was sent and nothing was charged) would erase the
   * words and the uploaded files, and the files have to be picked and uploaded again.
   */
  onSubmit: (input: { prompt: string; params: Record<string, unknown> }) => Promise<boolean> | boolean;
  /** True while a turn is in flight: the composer is inert and the button says so. */
  isRunning?: boolean;
  /** Placed above the button row, for a refusal or a lost connection. */
  notice?: React.ReactNode;
  /**
   * The chat/studio switch, rendered INSIDE the composer's control row.
   *
   * <p>Passed in rather than imported: the switch navigates, and a composer that knows how to
   * leave the surface it belongs to is a composer that cannot be rendered anywhere else. The
   * caller owns the routing; this owns the place it sits.
   *
   * <p>It sits in the composer because the composer is what it changes. Above the box it read as
   * page furniture, and it moved when you used it - the chat home put it over the title, the
   * studio over its own. Inside, it stays put and the box around it changes instead.
   */
  modeSwitch?: React.ReactNode;
  /**
   * Nothing can be sent from here at all - the conversation this composer writes into cannot be
   * read, so its kind is unknown. Distinct from `isRunning`, which is temporary.
   */
  disabled?: boolean;
  autoFocus?: boolean;
  /**
   * Which pool pays, and which key. Only 'user' reads a specific key; on the platform key the id is
   * not sent. It is part of the QUESTION for a fetch-only parameter, not a detail: a voice list read
   * on the platform's key is not the reader's own, so the values change when the payer changes.
   */
  credentialSource?: 'platform' | 'user';
  onCredentialSourceChange?: (source: 'platform' | 'user') => void;
  credentialId?: number | null;
  onCredentialIdChange?: (id: number | null) => void;
  /**
   * A previous turn to load back into the composer, so the reader changes only what they came to
   * change.
   *
   * <p>File parameters ARE restored, handle and all, so a replay runs on the very file the first
   * run used. Dropping them looked safer (a handle can point at a file that has since been deleted)
   * and was worse: on the models a replay is most used for - image to video, upscale, style
   * transfer - the file IS the subject, so a replay without it is a different generation. A stale
   * handle fails at the provider with a message; a missing one fails silently as "a prompt with no
   * source".
   */
  reuse?: StudioRequestEnvelope | null;
  /** Called once a `reuse` has been loaded, so the caller can drop it. */
  onReuseConsumed?: () => void;
}

/** One uploaded file per (parameter, slot). Sparse: slot 2 can hold a file while slot 1 is empty. */
/**
 * How much room must come BACK before the parameter toggles get their words again.
 *
 * <p>Folding is driven by real overflow, not by a guessed width, because the row's natural width is
 * the model's: three parameters fit where six do not, and any fixed breakpoint is wrong for one of
 * them. But "fold when it overflows" alone oscillates - folding frees width, which un-overflows,
 * which unfolds, which overflows. So unfolding needs strictly more room than folding did, and this
 * is that margin.
 */
const UNFOLD_SLACK_PX = 90;

/**
 * Below this composer width the parameter controls leave the row for a single menu.
 *
 * <p>The SAME number, measured the same way, as the chat composer's
 * `MERGE_ACTIONS_BELOW_WIDTH_PX`. The two composers sit on one page behind one switch, so a row
 * that survives a phone in one of them and collapses in the other reads as a bug in whichever you
 * opened second - which is exactly how this was reported.
 *
 * <p>It is the COMPOSER's width, not the viewport's: the composer also lives in the side panel,
 * which is narrow on a wide screen.
 */
const MERGE_PARAMS_BELOW_WIDTH_PX = 430;

type AssetMap = Record<string, (FileRef | undefined)[]>;

export function StudioComposer({
  models,
  selectedModel,
  onSelectModel,
  onSubmit,
  isRunning = false,
  notice,
  modeSwitch,
  disabled = false,
  autoFocus = false,
  credentialSource = 'platform',
  onCredentialSourceChange,
  credentialId = null,
  onCredentialIdChange,
  reuse = null,
  onReuseConsumed,
}: StudioComposerProps) {
  const t = useTranslations('studio');
  // The parameter and file-slot dictionary is shared with every other generation surface: one
  // copy, so a new provider role is named the same way everywhere.
  const tGeneration = useTranslations('generation');
  // Bound to `credentials`, which is where the price-unit dictionary lives, and NOT one level
  // deeper: priceUnitLabel prepends `source.` itself, so a translator bound deeper double-prefixes
  // the lookup and next-intl silently returns the key path, on screen, in every locale.
  const tUnits = useTranslations('credentials');
  // The same generation dictionary, narrowed for the label helpers that need `has`.
  const tLabels = tGeneration as unknown as LabelTranslator;
  const [prompt, setPrompt] = React.useState('');
  const [values, setValues] = React.useState<Record<string, unknown>>({});
  const [assets, setAssets] = React.useState<AssetMap>({});
  // A SET, not one key.
  //
  // Two uploads cannot currently overlap - the add control is disabled while any is running, and the
  // per-chip retry only appears on a slot that FAILED, which the add control already treats as
  // free. So this is not fixing a reachable bug today. It is here because the single-key form
  // encoded that impossibility as an assumption, and the assumption is one control-enablement
  // change away from being wrong: with a single value, the first upload to finish clears the
  // other's marker, that chip vanishes (no file, no marker, no error) and the send button unblocks
  // with a file still in the air. A Set costs nothing and cannot be wrong that way.
  const [uploadingKeys, setUploadingKeys] = React.useState<ReadonlySet<string>>(new Set());
  const [uploadErrors, setUploadErrors] = React.useState<Record<string, string>>({});
  const textareaRef = React.useRef<HTMLTextAreaElement>(null);

  const fileInputRef = React.useRef<HTMLInputElement>(null);
  // Which (parameter, slot) the hidden file input is currently picking for. One input is reused for
  // every slot: a separate input per slot would mean up to eight of them in the DOM per parameter.
  const pendingSlotRef = React.useRef<{ name: string; slot: number } | null>(null);

  const modelId = selectedModel?.model ?? null;
  const spec = React.useMemo(() => buildParamSpec(selectedModel), [selectedModel]);
  const fileFields = React.useMemo(() => assetFields(spec), [spec]);
  const slotCount = React.useMemo(() => totalAssetSlots(spec), [spec]);
  const valueFields = React.useMemo(() => spec.filter((f) => f.kind !== 'asset'), [spec]);

  const composerRef = React.useRef<HTMLDivElement | null>(null);
  const controlsRef = React.useRef<HTMLDivElement | null>(null);
  // The width at which the words were last dropped, so they only come back with room to spare.
  const foldedAtRef = React.useRef<number | null>(null);
  // Starts false, so a renderer without ResizeObserver - jsdom, and any server render - draws the
  // full labels it drew before rather than a permanently dense row.
  const [denseParams, setDenseParams] = React.useState(false);
  React.useEffect(() => {
    const el = controlsRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const measure = () => {
      setDenseParams((wasDense) => {
        if (!wasDense) {
          // +1 absorbs sub-pixel rounding, which otherwise reports a permanent 0.5px overflow.
          if (el.scrollWidth > el.clientWidth + 1) {
            foldedAtRef.current = el.clientWidth;
            return true;
          }
          return false;
        }
        const roomToUnfold = foldedAtRef.current != null
          && el.clientWidth > foldedAtRef.current + UNFOLD_SLACK_PX;
        if (roomToUnfold) foldedAtRef.current = null;
        return !roomToUnfold;
      });
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
    // Re-measured when the model changes: a different model brings a different set of toggles, and
    // the row that fitted three of them need not fit six.
  }, [valueFields.length, slotCount]);

  /**
   * Narrow row: the parameters move into ONE menu instead of being squeezed.
   *
   * <p>What went wrong before is worth stating, because the row still looks reasonable in a
   * desktop browser. The right-hand group (price, model picker, send) was `flex-shrink-0` and the
   * left one `flex-1 min-w-0 overflow-hidden`, so ALL the deficit landed on the left: on a phone
   * the price capped at 144px and the picker at 190px already exceed the row, the left group was
   * squeezed to nothing, and `overflow-hidden` then cut its contents - the mode switch included.
   * A control that is clipped is not merely small, it is unreachable, and nothing said so.
   *
   * <p>The chat composer never had this because it does not squeeze: below the same width it
   * MERGES its actions into one menu and every surviving control keeps its size. This does the
   * same, and the picker gives up its label rather than the row giving up a control.
   *
   * <p>Starts false, so a renderer with no ResizeObserver - jsdom, and any server render - draws
   * the full row it drew before.
   */
  const [mergeParams, setMergeParams] = React.useState(false);
  const [paramsMenuOpen, setParamsMenuOpen] = React.useState(false);
  React.useEffect(() => {
    const el = composerRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const measure = () => {
      setMergeParams(el.getBoundingClientRect().width < MERGE_PARAMS_BELOW_WIDTH_PX);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // The menu exists only while the row is merged: widening with it open would leave a popover
  // anchored to a button that is no longer rendered.
  React.useEffect(() => {
    if (!mergeParams) setParamsMenuOpen(false);
  }, [mergeParams]);

  // Sticky once any parameter has been opened. The underlying hook asks the provider for every
  // dynamic parameter of the model at once, so this is one batch, not one request per pill - and it
  // is not sent at all for a reader who never opens one.
  const [optionsWanted, setOptionsWanted] = React.useState(false);
  React.useEffect(() => { setOptionsWanted(false); }, [modelId]);
  // What is currently typed, so the price sizes THIS call. Shared with the payer control below,
  // which asks the SAME question: one request between them.
  const quantitySource = React.useMemo(
    () => ({ prompt, ...values }),
    [prompt, values],
  );
  // The quantity comes back with the quote so the payer control below quotes the SAME call. Both
  // computing it from their own inputs is how the pill and the popover end up naming two prices for
  // one generation.
  const { quote, quantity, settled: priceSettled, stale: priceStale } = useGenerationQuote(selectedModel, quantitySource);
  /**
   * Falls back to the reader's OWN key on a model the platform does not sell.
   *
   * <p>The studio opens on `'platform'` because that is the common case and the one that needs no
   * setup. But the platform sells only what an admin has published a rate for: on HeyGen it sells
   * nothing, and the surface still sat on `'platform'`, so the reader was defaulted onto a payer
   * that cannot run the call - and, because standing there was read as proof the option existed,
   * was even offered it in the picker. The first sign of trouble was the refusal, after choosing a
   * model and writing a prompt.
   *
   * <p>The answer comes from the quote already in hand - the same endpoint, the same cache entry
   * the payer control reads - so this costs no extra request. It waits for `priceSettled`: acting
   * on "no price yet" would flip every model to the reader's key for the moment before its rate
   * arrives.
   *
   * <p>One direction only. It never moves anyone ONTO the platform: that would overrule a reader
   * who deliberately chose their own key, and quietly change who pays.
   */
  React.useEffect(() => {
    if (!onCredentialSourceChange) return;
    if (credentialSource !== 'platform') return;
    if (!priceSettled) return;
    if (platformSellsThis(quote)) return;
    onCredentialSourceChange('user');
  }, [credentialSource, onCredentialSourceChange, priceSettled, quote]);

  const priceLabel = React.useMemo(
    () => describeQuotedPrice(quote, tGeneration as never, tUnits as never),
    [quote, tGeneration, tUnits],
  );

  const optionsByParam = useGenerationOptions(
    selectedModel,
    credentialSource,
    credentialId,
    optionsWanted,
  );

  // Changing model changes which parameters exist. Values for parameters the new model does not
  // accept are dropped rather than carried: the platform REFUSES an unaccepted parameter, so
  // keeping them would turn a model switch into a failing turn whose cause is invisible.
  const previousModelRef = React.useRef(modelId);
  React.useEffect(() => {
    if (previousModelRef.current === modelId) return;
    previousModelRef.current = modelId;
    const accepted = new Set(spec.map((f) => f.name));
    setValues((current) => Object.fromEntries(
      Object.entries(current).filter(([name]) => accepted.has(name)),
    ));
    setAssets((current) => Object.fromEntries(
      Object.entries(current).filter(([name]) => accepted.has(name)),
    ));
    setUploadErrors({});
  }, [modelId, spec]);

  // Loading a reused turn. Runs on the `reuse` object arriving, not on every render: the caller
  // drops it immediately afterwards, so this fires once per press of "reuse".
  const onReuseConsumedRef = React.useRef(onReuseConsumed);
  onReuseConsumedRef.current = onReuseConsumed;
  React.useEffect(() => {
    if (!reuse) return;
    setPrompt(reuse.prompt ?? '');
    const accepted = new Map(spec.map((f) => [f.name, f]));
    const restored: Record<string, unknown> = {};
    const restoredAssets: AssetMap = {};
    for (const [name, value] of Object.entries(reuse.params ?? {})) {
      // Only what THIS model accepts. A recipe carries the parameters of the model it was made on,
      // and restoring one the current model does not declare would put a value on screen that the
      // platform refuses.
      const field = accepted.get(name);
      if (!field) continue;
      if (field.kind === 'asset') {
        const files = (Array.isArray(value) ? value : [value]).filter(isFileRef).map(normalizeFileRef);
        if (files.length > 0) restoredAssets[name] = files.slice(0, field.slots);
        continue;
      }
      // Anything structured that is not a file is not a value this composer can edit.
      if (value === null || typeof value === 'object') continue;
      restored[name] = value;
    }
    setValues(restored);
    setAssets(restoredAssets);
    setUploadErrors({});
    onReuseConsumedRef.current?.();
  }, [reuse, spec]);

  const missing = React.useMemo(
    () => missingRequired(spec, values, assets),
    [spec, values, assets],
  );

  const hasPrompt = prompt.trim().length > 0;
  // A model that requires no prompt (an upscaler taking only a file) must still be submittable, so
  // the gate is "something to send", not "words typed".
  const promptRequired = (selectedModel?.required ?? []).includes('prompt');
  const hasSomethingToSend = hasPrompt || hasSubmittableInput(spec, values, assets);
  const canSubmit = !!selectedModel
    && !disabled
    && !isRunning
    && uploadingKeys.size === 0
    && missing.length === 0
    && (promptRequired ? hasPrompt : hasSomethingToSend);

  const openPicker = (name: string, slot: number) => {
    pendingSlotRef.current = { name, slot };
    const input = fileInputRef.current;
    if (!input) return;
    const field = fileFields.find((f) => f.name === name);
    input.accept = field?.accept ?? '';
    // Reset so picking the SAME file twice in a row still fires a change event.
    input.value = '';
    input.click();
  };

  const handleFilePicked = async (file: File | undefined) => {
    const target = pendingSlotRef.current;
    pendingSlotRef.current = null;
    if (!file || !target) return;
    const key = `${target.name}#${target.slot}`;
    setUploadErrors((current) => {
      const { [key]: _cleared, ...rest } = current;
      return rest;
    });
    // The previous file goes BEFORE the new one is known to work. Kept, a failed replacement would
    // leave the old file attached under a slot showing an error, and the turn would generate from
    // the file the reader had just replaced.
    setAssets((current) => {
      const list = [...(current[target.name] ?? [])];
      list[target.slot] = undefined;
      return { ...current, [target.name]: list };
    });
    setUploadingKeys((current) => new Set(current).add(key));
    try {
      const uploaded = await fileService.uploadGeneric(file, 'generation-input');
      setAssets((current) => {
        const list = [...(current[target.name] ?? [])];
        list[target.slot] = {
          _type: 'file',
          path: uploaded.storageKey,
          name: uploaded.fileName,
          mimeType: uploaded.mimeType,
          size: uploaded.size,
          id: uploaded.id,
        } as FileRef;
        return { ...current, [target.name]: list };
      });
    } catch (error) {
      setUploadErrors((current) => ({
        ...current,
        [key]: error instanceof Error ? error.message : String(error),
      }));
    } finally {
      // Only the key this call owns: another upload may still be in flight.
      setUploadingKeys((current) => {
        const next = new Set(current);
        next.delete(key);
        return next;
      });
    }
  };

  const removeAsset = (name: string, slot: number) => {
    setAssets((current) => {
      const list = [...(current[name] ?? [])];
      list[slot] = undefined;
      return { ...current, [name]: list };
    });
  };

  const submit = async () => {
    if (!canSubmit || !selectedModel) return;
    // The rule lives in one pure function, where a test can reach it: see buildSubmissionParams.
    const params = buildSubmissionParams(spec, values, assets);
    const recorded = await onSubmit({ prompt: prompt.trim(), params });
    // Cleared only when the turn was RECORDED. A turn refused before it left keeps its words and
    // its files, which for the files is the difference between one click and re-uploading them.
    if (!recorded) return;
    setPrompt('');
    setAssets({});
    setUploadErrors({});
  };

  // Grows with what is typed, exactly as the chat composer does - same reset-then-measure, same
  // 52px floor and 200px ceiling. The studio box was fixed at two rows: a long prompt scrolled
  // inside a window three lines tall, so a reader could not see the sentence they were about to pay
  // to generate. The two composers sit on the same page behind one switch; one growing and the
  // other not reads as a bug in whichever you used second.
  React.useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.max(52, Math.min(textarea.scrollHeight, 200))}px`;
  }, [prompt]);

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void submit();
    }
  };

  // The SAME shell as the chat composer, deliberately: same surface token, same border, same 28px
  // radius. The studio is a second composer on the same home, not a different chrome - a reader
  // flipping the mode switch inside it should see the control move, not the box.
  // The chat composer's OWN wrapper, replicated here rather than left to the page.
  //
  // Measured on the running app, the two composers did not line up: the studio's box sat 16px
  // higher and 24px wider than the chat's on the same viewport. Copying the page-level nesting was
  // not enough, because MessageComposer wraps ITSELF in three more levels - `px-2 sm:px-3 pb-4`,
  // then `mx-auto max-w-4xl`, then `relative` - and a caller cannot see them. The studio has to
  // carry the same ones or the box moves the moment the mode switch is used.
  return (
    <div className="px-2 sm:px-3 pb-[calc(env(safe-area-inset-bottom,0px)+0.75rem)] sm:pb-4">
      <div className="mx-auto max-w-4xl">
        <div className="relative">
    <div
      ref={composerRef}
      className="w-full overflow-hidden border border-theme bg-theme-primary shadow-sm"
      style={{ borderRadius: '28px' }}
    >
      {/* The chat composer's inner envelope, to the pixel.
        *
        * Measured on the running app: with the shells already identical (same box, same 28px
        * radius, same border and shadow), the studio's TEXT still sat 6px closer to every edge and
        * 6px higher - 17/17/13 against the chat's 23/23/19 - because the chat insets its content
        * TWICE (`p-2.5` on this envelope, then `px-3 pt-2` on the text) and the studio did it once
        * with a single `px-4 pt-3`. Two boxes with the same outline whose contents sit differently
        * read as two different components, which is exactly what a mode switch must not produce. */}
      <div className="p-2.5">
      {notice && <div className="px-3 pt-2">{notice}</div>}

      {/* Attached files, above the words, so what is being transformed is visible while writing. */}
      {slotCount > 0 && (
        <AttachedFiles
          fields={fileFields}
          tGeneration={tLabels}
          assets={assets}
          uploadingKeys={uploadingKeys}
          uploadErrors={uploadErrors}
          onRemove={removeAsset}
          onReplace={openPicker}
        />
      )}

      <div className="px-3 pt-2 pb-1">
        <textarea
          ref={textareaRef}
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={selectedModel
            ? t('composer.placeholder', { model: selectedModel.label })
            : t('composer.placeholderNoModel')}
          disabled={isRunning || disabled}
          autoFocus={autoFocus}
          rows={2}
          className="w-full resize-none overflow-y-auto bg-transparent text-base leading-6 text-theme-primary placeholder-theme-muted focus:outline-none disabled:opacity-50"
          style={{ minHeight: '52px', maxHeight: '200px' }}
        />
      </div>

      {/* pt-1/pb-1: the 4px the chat's grid puts above and below its own button row (its `gap-y-1`
          plus the text cell's `pb-1`). Without them the studio's box came out 8px shorter than the
          chat's on the same content, which is visible the moment the mode switch is used. */}
      <div className="flex items-end gap-2 pt-1 pb-1">
        {/* ONE line, always. Neither a second row nor a sideways scroller: the row keeps its
            height and the controls give up their WORDS instead, below `sm` - the same trade the
            chat row makes when it runs out of width. `min-w-0` is what lets them shrink at all;
            without it a flex child refuses to go below its content width and pushes the send
            button off the end. */}
        <div ref={controlsRef} className="flex min-w-0 flex-1 items-center gap-1 overflow-hidden">
          {/* Leftmost, and the same position the chat composer gives it: the switch is read before
              the row it changes, not after it. `flex-shrink-0` keeps it out of the folding above -
              the parameter toggles give up their words for width, this never does. */}
          {modeSwitch && <div className="flex-shrink-0">{modeSwitch}</div>}
          {/* The attachment control exists only where a file can actually go. */}
          {slotCount > 0 && (
            <AddFileControl
              fields={fileFields}
              tGeneration={tLabels}
              assets={assets}
              disabled={isRunning || uploadingKeys.size > 0}
              onPick={openPicker}
            />
          )}

          {/* One toggle per parameter this model accepts. They change with the model because they
              ARE the model's declaration.

              On a narrow row they move behind ONE trigger rather than shrinking: stacked in the
              menu they get their WORDS back, which is the opposite of what squeezing did to them.
              A parameter left unset that the model requires is marked on the trigger too, so the
              reason a turn will be refused is not hidden one tap deep. */}
          {mergeParams && valueFields.length > 0 ? (
            <Popover open={paramsMenuOpen} onOpenChange={setParamsMenuOpen}>
              <PopoverTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={isRunning}
                  className={`h-9 w-9 shrink-0 ${missing.length > 0 ? 'text-red-500' : ''}`}
                  title={t('composer.parameters')}
                  aria-label={t('composer.parameters')}
                >
                  <SlidersHorizontal className="h-4 w-4" />
                </Button>
              </PopoverTrigger>
              <PopoverContent
                align="start"
                side="top"
                className={`${menuSurfaceClass} max-h-[60vh] w-64 overflow-y-auto`}
              >
                <div className="flex flex-col items-stretch gap-1">
                  {valueFields.map((field) => (
                    <StudioParamControl
                      key={field.name}
                      field={field}
                      value={values[field.name]}
                      optionsState={optionsByParam[field.name]}
                      onOpened={() => setOptionsWanted(true)}
                      missing={missing.includes(field.name)}
                      disabled={isRunning}
                      onChange={(next) => setValues((current) => ({ ...current, [field.name]: next }))}
                    />
                  ))}
                </div>
              </PopoverContent>
            </Popover>
          ) : (
            valueFields.map((field) => (
              <StudioParamControl
                key={field.name}
                field={field}
                value={values[field.name]}
                optionsState={optionsByParam[field.name]}
                onOpened={() => setOptionsWanted(true)}
                missing={missing.includes(field.name)}
                dense={denseParams}
                disabled={isRunning}
                onChange={(next) => setValues((current) => ({ ...current, [field.name]: next }))}
              />
            ))
          )}
        </div>

        {/* The right-hand group SHRINKS. It used to be flex-shrink-0, which is what made the row
            unusable on a phone: the price (capped at 144px) and the picker (190px) together already
            exceed a phone row, so the whole deficit fell on the controls opposite them and the mode
            switch was clipped away. Now the price truncates and the picker drops its label first,
            and only the send button is guaranteed - which is the same order of sacrifice the chat
            composer makes. */}
        <div className="flex min-w-0 items-center gap-1">
          {/* What this will cost, beside the button that spends it.
              Nothing published is NOT "free": such a generation is REFUSED on the platform key, so
              the absence is STATED once the question has been answered, rather than left as silence
              in front of a button the reader is about to press. Requiring a quote object here was
              the bug: a model with no integration, or a quote that errored, produced no object and
              therefore no words at all. Shown at every width: a price is not a desktop detail.
              Hidden while the reader's OWN key pays: this is the platform rate, and nothing is
              charged in platform credits then, so showing it would name the wrong number. */}
          {/* Rendered only when there IS an amount. A model the platform does not sell says so by
              its absence here and, if a run is attempted, in the refusal itself - naming it beside
              the button read as a warning about the model rather than about the key. */}
          {selectedModel && priceSettled && priceLabel && credentialSource !== 'user' && (
            <span
              // Dimmed, and announced as busy, while the amount belongs to a quantity the request
              // has already moved past. The alternative is worse than a dimmed number: a crisp one
              // that is simply too low, read at the exact moment the reader decides to spend.
              aria-busy={priceStale || undefined}
              className={`min-w-0 max-w-[9rem] truncate px-1 text-xs text-theme-muted transition-opacity sm:max-w-none sm:whitespace-nowrap ${priceStale ? 'opacity-50' : ''}`}
            >
              {priceLabel}
            </span>
          )}
          <StudioModelPicker
            models={models}
            selected={selectedModel}
            onSelect={onSelectModel}
            // Whose key pays is part of this choice, not a separate one: a credential belongs to an
            // integration, so the option only exists once a provider is picked.
            credentialSource={credentialSource}
            onCredentialSourceChange={onCredentialSourceChange}
            credentialId={credentialId}
            onCredentialIdChange={onCredentialIdChange}
            // The quantity already computed for the price beside it: one number, one call.
            quantity={quantity}
            // The widest thing in the row gives up its words first, so the row never has to give
            // up a control.
            compact={mergeParams}
            disabled={isRunning}
          />
          {/* No Stop control, deliberately. A generation is submitted once and cannot be recalled:
              the client giving up cancels nothing upstream, the server finishes and commits the
              charge, so a Stop button would promise a refund that does not exist. */}
          <Button
            size="icon"
            onClick={() => void submit()}
            disabled={!canSubmit}
            // shrink-0: the one control the row is never allowed to give up. Everything beside it
            // yields first - the price truncates, the picker drops its name, the parameters move
            // into a menu - because a composer you cannot send from is not a composer.
            className="h-9 w-9 shrink-0 rounded-full shadow-none hover:shadow-none"
            title={missing.length > 0 ? t('composer.missingRequired') : t('composer.send')}
          >
            {isRunning ? <Loader2 className="h-5 w-5 animate-spin" /> : <ArrowUp className="h-5 w-5" />}
          </Button>
        </div>
      </div>

      {/* One input for every slot - see pendingSlotRef. */}
      <input
        ref={fileInputRef}
        type="file"
        className="sr-only"
        onChange={(event) => void handleFilePicked(event.target.files?.[0])}
      />
      </div>
    </div>
        </div>
      </div>
    </div>
  );
}

/**
 * The control that adds a file, shaped by what the model takes.
 *
 * <p>One slot goes straight to the picker: a menu with a single entry asks the reader to choose
 * between one thing. Several open a menu naming each, because "Reference image" and "First frame"
 * are different jobs and the reader is choosing the job, not the file.
 */
function AddFileControl({
  fields, tGeneration, assets, disabled, onPick,
}: {
  fields: StudioField[];
  tGeneration: LabelTranslator;
  assets: AssetMap;
  disabled: boolean;
  onPick: (name: string, slot: number) => void;
}) {
  const t = useTranslations('studio');
  const [open, setOpen] = React.useState(false);

  /** The first slot with no file, or null when this parameter is full. */
  const firstFreeSlot = (field: StudioField): number | null => {
    const list = assets[field.name] ?? [];
    for (let slot = 0; slot < field.slots; slot += 1) {
      if (!list[slot]) return slot;
    }
    return null;
  };

  const targets = fields
    .map((field) => ({ field, slot: firstFreeSlot(field) }))
    .filter((entry): entry is { field: StudioField; slot: number } => entry.slot !== null);

  // Every slot is taken. The control is kept but inert, with the reason on it: removing it would
  // make the button flicker in and out as files are added and removed.
  if (targets.length === 0) {
    return (
      <Button variant="ghost" size="icon" className="h-9 w-9" disabled title={t('composer.allSlotsFull')}>
        <Paperclip className="h-4 w-4" />
      </Button>
    );
  }

  if (targets.length === 1) {
    const only = targets[0];
    return (
      <Button
        variant="ghost"
        size="icon"
        className="h-9 w-9"
        disabled={disabled}
        onClick={() => onPick(only.field.name, only.slot)}
        title={assetRoleLabel(only.field, tGeneration)}
      >
        <Paperclip className="h-4 w-4" />
      </Button>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="h-9 w-9" disabled={disabled} title={t('composer.addFile')}>
          <Paperclip className="h-4 w-4" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className={`${menuSurfaceClass} w-56`}>
        {targets.map(({ field, slot }) => (
          <button
            key={field.name}
            type="button"
            className={`${menuItemClass} justify-between`}
            onClick={() => { setOpen(false); onPick(field.name, slot); }}
          >
            <span className="truncate">{assetRoleLabel(field, tGeneration)}</span>
            {field.slots > 1 && (
              <span className="ml-2 flex-shrink-0 text-xs text-theme-muted">
                {slot + 1}/{field.slots}
              </span>
            )}
          </button>
        ))}
      </PopoverContent>
    </Popover>
  );
}

/** The chips for what is already attached, one per filled slot. */
/**
 * The attached image, as an image.
 *
 * <p>Rendered through {@link useAuthedObjectUrl} rather than by putting the file URL straight in
 * `src`. An `<img>` cannot send an Authorization header, so the shortcut is to append the session
 * token as a query parameter - which leaks a full-scope bearer into anything that logs a URL. The
 * hook fetches with the header and hands back an in-memory blob instead.
 *
 * <p>Non-images render nothing here: a PDF or an audio file has no useful thumbnail, and the pill
 * beside this already carries its name and its role.
 */
function AssetThumb({ file }: { file: FileRef }) {
  const isImage = isImageFile(file);
  const { url } = useAuthedObjectUrl(isImage ? fileRefToUrl(file, { inline: true }) : null);
  if (!isImage || !url) return null;
  return (
    <img
      src={url}
      alt=""
      // Decorative: the pill next to it names the file and its role, so a screen reader that also
      // announced the image would say the same thing twice.
      aria-hidden="true"
      className="h-6 w-6 flex-shrink-0 rounded object-cover"
    />
  );
}

function AttachedFiles({
  fields, tGeneration, assets, uploadingKeys, uploadErrors, onRemove, onReplace,
}: {
  fields: StudioField[];
  tGeneration: LabelTranslator;
  assets: AssetMap;
  uploadingKeys: ReadonlySet<string>;
  uploadErrors: Record<string, string>;
  onRemove: (name: string, slot: number) => void;
  onReplace: (name: string, slot: number) => void;
}) {
  const t = useTranslations('studio');
  const entries = fields.flatMap((field) =>
    Array.from({ length: field.slots }, (_, slot) => ({ field, slot }))
      .filter(({ slot }) => {
        const key = `${field.name}#${slot}`;
        return !!assets[field.name]?.[slot] || uploadingKeys.has(key) || !!uploadErrors[key];
      }));

  if (entries.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-2 px-3 pt-2">
      {entries.map(({ field, slot }) => {
        const key = `${field.name}#${slot}`;
        const file = assets[field.name]?.[slot];
        const error = uploadErrors[key];
        const uploading = uploadingKeys.has(key);
        return (
          <div
            key={key}
            className={`flex items-center gap-1.5 rounded-lg px-2 py-1 text-xs ${
              error ? 'bg-[var(--status-error)]/10 text-[var(--status-error)]' : 'bg-theme-tertiary text-theme-secondary'
            }`}
          >
            {/* The image itself, not only its name. An attachment on this row is the thing being
                transformed, and a filename is a poor way to check you picked the right frame -
                especially with four slots, where two files can differ only by a digit. */}
            {!uploading && !error && file && <AssetThumb file={file} />}
            <span className="font-medium">{assetRoleLabel(field, tGeneration, slot)}</span>
            <span className="max-w-[140px] truncate">
              {uploading ? t('composer.uploading') : error ? t('composer.uploadFailed') : file?.name}
            </span>
            {uploading && <Loader2 className="h-3 w-3 animate-spin" />}
            {!uploading && (
              <button
                type="button"
                className="rounded p-0.5 hover:bg-theme-primary/20"
                onClick={() => (error ? onReplace(field.name, slot) : onRemove(field.name, slot))}
                title={error ? t('composer.retryUpload') : t('composer.removeFile')}
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}
