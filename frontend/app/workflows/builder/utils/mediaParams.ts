// Bounds, defaults, and plan (de)serialization helpers for the core:media node,
// mirrored from the backend contract (MediaNode). The node's config lives in the
// GENERIC `params` map: { operation, ...operation-specific params } with the
// contract field names, exactly like public_link. Every param also accepts a
// {{...}} template expression, so string values are passed through verbatim.

export const MEDIA_OPERATIONS = ['probe', 'mux_audio', 'mix', 'extract_audio', 'concat', 'frame', 'overlay', 'subtitles'] as const;
export type MediaOperation = (typeof MEDIA_OPERATIONS)[number];

export function isMediaOperation(value: unknown): value is MediaOperation {
  return typeof value === 'string' && (MEDIA_OPERATIONS as readonly string[]).includes(value);
}

// Volume is a percentage (0-400), default 100 (unchanged).
export const MEDIA_VOLUME_MIN = 0;
export const MEDIA_VOLUME_MAX = 400;
export const MEDIA_VOLUME_DEFAULT = 100;

// Track speed (atempo, pitch-preserving), 0.5-2.0, default 1.0.
export const MEDIA_SPEED_MIN = 0.5;
export const MEDIA_SPEED_MAX = 2.0;
export const MEDIA_SPEED_DEFAULT = 1.0;

// mix accepts 1..8 tracks.
export const MEDIA_TRACKS_MIN = 1;
export const MEDIA_TRACKS_MAX = 8;

// normalize: true -> loudnorm at -16 LUFS, a number -> that LUFS target
// (clamped -70..-5), false -> no loudness normalization.
export const MEDIA_NORMALIZE_LUFS_MIN = -70;
export const MEDIA_NORMALIZE_LUFS_MAX = -5;
export const MEDIA_NORMALIZE_LUFS_DEFAULT = -16;

export const MEDIA_AUDIO_BITRATE_DEFAULT = '192k';

export const MEDIA_AUDIO_FITS = ['pad', 'shortest', 'loop'] as const;
export type MediaAudioFit = (typeof MEDIA_AUDIO_FITS)[number];
export const MEDIA_AUDIO_FIT_DEFAULT: MediaAudioFit = 'pad';

export const MEDIA_OUTPUT_FORMATS = ['mp3', 'wav', 'aac'] as const;
export type MediaOutputFormat = (typeof MEDIA_OUTPUT_FORMATS)[number];
export const MEDIA_OUTPUT_FORMAT_DEFAULT: MediaOutputFormat = 'mp3';

// mux_audio fade_out_seconds defaults to 1.0; every other fade defaults to 0.
export const MEDIA_MUX_FADE_OUT_DEFAULT = 1.0;

// concat accepts 1..8 input clips (a SINGLE input is the trim/speed-edit use case).
export const MEDIA_CONCAT_INPUTS_MIN = 1;
export const MEDIA_CONCAT_INPUTS_MAX = 8;

export const MEDIA_TRANSITIONS = ['cut', 'crossfade'] as const;
export type MediaTransition = (typeof MEDIA_TRANSITIONS)[number];
export const MEDIA_TRANSITION_DEFAULT: MediaTransition = 'cut';

// crossfade duration (video xfade + audio acrossfade), 0.1-5.0, default 0.5.
export const MEDIA_TRANSITION_SECONDS_MIN = 0.1;
export const MEDIA_TRANSITION_SECONDS_MAX = 5.0;
export const MEDIA_TRANSITION_SECONDS_DEFAULT = 0.5;

// concat target_width/target_height and frame width, 16-4096 (backend rounds
// the concat canvas down to even values for yuv420p).
export const MEDIA_DIMENSION_MIN = 16;
export const MEDIA_DIMENSION_MAX = 4096;

export const MEDIA_TARGET_FPS_MIN = 1;
export const MEDIA_TARGET_FPS_MAX = 60;

export const MEDIA_IMAGE_FORMATS = ['jpeg', 'png'] as const;
export type MediaImageFormat = (typeof MEDIA_IMAGE_FORMATS)[number];
export const MEDIA_IMAGE_FORMAT_DEFAULT: MediaImageFormat = 'jpeg';

export const MEDIA_OVERLAY_POSITIONS = ['top_left', 'top_right', 'bottom_left', 'bottom_right', 'center'] as const;
export type MediaOverlayPosition = (typeof MEDIA_OVERLAY_POSITIONS)[number];
export const MEDIA_OVERLAY_POSITION_DEFAULT: MediaOverlayPosition = 'bottom_right';

export const MEDIA_MARGIN_PX_DEFAULT = 24;

// overlay image width as a percent of the VIDEO width (height auto, AR kept).
export const MEDIA_WIDTH_PERCENT_MIN = 1;
export const MEDIA_WIDTH_PERCENT_MAX = 100;
export const MEDIA_WIDTH_PERCENT_DEFAULT = 15;

export const MEDIA_OPACITY_MIN = 0;
export const MEDIA_OPACITY_MAX = 1;
export const MEDIA_OPACITY_DEFAULT = 1.0;

// subtitles: the look is a PRESET, never a caller-authored style block. tiktok is
// the big uppercase block above centre, classic the smaller mixed-case line near the
// bottom. Every size in a preset is a percent of the video HEIGHT, so one style reads
// the same on a 720x1280 phone cut and a 1080x1920 master.
export const MEDIA_SUBTITLE_STYLES = ['tiktok', 'classic'] as const;
export type MediaSubtitleStyle = (typeof MEDIA_SUBTITLE_STYLES)[number];
export const MEDIA_SUBTITLE_STYLE_DEFAULT: MediaSubtitleStyle = 'tiktok';

export const MEDIA_SUBTITLE_CUES_MAX = 600;
export const MEDIA_SUBTITLE_TEXT_MAX = 240;
// The whole track, not one line: a per-line cap alone lets a legal 600-line track in a
// non-Latin script overrun the renderer's request size, where it is truncated rather
// than refused.
export const MEDIA_SUBTITLE_TOTAL_MAX = 40000;

export const MEDIA_FONT_SIZE_PERCENT_MIN = 1;
export const MEDIA_FONT_SIZE_PERCENT_MAX = 20;

export const MEDIA_POSITION_PERCENT_MIN = 0;
export const MEDIA_POSITION_PERCENT_MAX = 100;

export const MEDIA_TEXT_COLOR_DEFAULT = '#FFFFFF';
export const MEDIA_OUTLINE_COLOR_DEFAULT = '#000000';

// Sidechain ducking defaults (per track, only meaningful with duck_under set).
export const MEDIA_DUCK_AMOUNT_DB_DEFAULT = 12;
export const MEDIA_DUCK_ATTACK_MS_DEFAULT = 20;
export const MEDIA_DUCK_RELEASE_MS_DEFAULT = 300;

/**
 * Clamp a volume percent input on commit (blur). Non-numeric input falls back
 * to the provided fallback (default 100) so a typed value can never leave 0-400.
 */
export function clampMediaVolume(raw: unknown, fallback: number = MEDIA_VOLUME_DEFAULT): number {
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(MEDIA_VOLUME_MAX, Math.max(MEDIA_VOLUME_MIN, parsed));
}

/**
 * Clamp a track speed input on commit. Non-numeric input falls back to 1.0.
 */
export function clampMediaSpeed(raw: unknown): number {
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return MEDIA_SPEED_DEFAULT;
  return Math.min(MEDIA_SPEED_MAX, Math.max(MEDIA_SPEED_MIN, parsed));
}

/**
 * Clamp a seconds/milliseconds/dB style non-negative number input on commit.
 * Returns undefined on junk/empty input so the param is OMITTED from the plan
 * (all offsets, trims, fades, and duck timings must be >= 0).
 */
export function clampMediaNonNegative(raw: unknown): number | undefined {
  if (raw === undefined || raw === null || raw === '') return undefined;
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return undefined;
  return Math.max(0, parsed);
}

/**
 * Clamp a custom loudness target (LUFS) input on commit. Non-numeric input
 * falls back to the -16 LUFS default; the backend accepts -70..-5.
 */
export function clampMediaNormalizeLufs(raw: unknown): number {
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return MEDIA_NORMALIZE_LUFS_DEFAULT;
  return Math.min(MEDIA_NORMALIZE_LUFS_MAX, Math.max(MEDIA_NORMALIZE_LUFS_MIN, parsed));
}

/**
 * Clamp a crossfade duration input on commit. Non-numeric input falls back
 * to the 0.5s default; the backend accepts 0.1-5.0.
 */
export function clampMediaTransitionSeconds(raw: unknown): number {
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return MEDIA_TRANSITION_SECONDS_DEFAULT;
  return Math.min(MEDIA_TRANSITION_SECONDS_MAX, Math.max(MEDIA_TRANSITION_SECONDS_MIN, parsed));
}

/**
 * Clamp a pixel dimension (concat target_width/target_height, frame width) on
 * commit. Returns undefined on junk/empty input so the param is OMITTED (the
 * backend defaults apply). Clamped to 16-4096; the concat canvas additionally
 * needs EVEN values (yuv420p), so odd inputs are rounded down like the backend.
 */
export function clampMediaDimension(raw: unknown): number | undefined {
  if (raw === undefined || raw === null || raw === '') return undefined;
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return undefined;
  const clamped = Math.min(MEDIA_DIMENSION_MAX, Math.max(MEDIA_DIMENSION_MIN, parsed));
  return Math.floor(clamped / 2) * 2;
}

/**
 * Clamp a concat target_fps input on commit. Returns undefined on junk/empty
 * input so the param is OMITTED (default: first input's fps). 1-60.
 */
export function clampMediaTargetFps(raw: unknown): number | undefined {
  if (raw === undefined || raw === null || raw === '') return undefined;
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return undefined;
  return Math.min(MEDIA_TARGET_FPS_MAX, Math.max(MEDIA_TARGET_FPS_MIN, parsed));
}

/**
 * Clamp an overlay width_percent input on commit. Non-numeric input falls
 * back to the 15% default; the backend accepts 1-100.
 */
export function clampMediaWidthPercent(raw: unknown): number {
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return MEDIA_WIDTH_PERCENT_DEFAULT;
  return Math.min(MEDIA_WIDTH_PERCENT_MAX, Math.max(MEDIA_WIDTH_PERCENT_MIN, parsed));
}

/**
 * Clamp an overlay opacity input on commit. Non-numeric input falls back to
 * the 1.0 default; the backend accepts 0-1.
 */
export function clampMediaOpacity(raw: unknown): number {
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return MEDIA_OPACITY_DEFAULT;
  return Math.min(MEDIA_OPACITY_MAX, Math.max(MEDIA_OPACITY_MIN, parsed));
}

/**
 * Clamp a subtitle font_size_percent input on commit. Returns undefined on junk/empty
 * input so the param is OMITTED and the STYLE PRESET decides - a default invented here
 * would silently override the preset with a value nobody chose. 1-20 percent of the
 * video height.
 */
export function clampMediaFontSizePercent(raw: unknown): number | undefined {
  if (raw === undefined || raw === null || raw === '') return undefined;
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return undefined;
  return Math.min(MEDIA_FONT_SIZE_PERCENT_MAX, Math.max(MEDIA_FONT_SIZE_PERCENT_MIN, parsed));
}

/**
 * Clamp a subtitle position_percent input on commit (distance from the TOP of the
 * frame). Returns undefined on junk/empty input so the param is OMITTED and the style
 * preset decides. 0-100.
 */
export function clampMediaPositionPercent(raw: unknown): number | undefined {
  if (raw === undefined || raw === null || raw === '') return undefined;
  const parsed = typeof raw === 'number' ? raw : parseFloat(String(raw));
  if (!Number.isFinite(parsed)) return undefined;
  return Math.min(MEDIA_POSITION_PERCENT_MAX, Math.max(MEDIA_POSITION_PERCENT_MIN, parsed));
}

// Contract param keys per area (used for import filtering; unknown keys are dropped,
// mirroring public_link which only reads its known params).
const SCALAR_PARAM_KEYS = [
  'input',
  'video',
  'audio',
  'volume',
  'offset_seconds',
  'trim_start_seconds',
  'trim_end_seconds',
  'loop',
  'fade_in_seconds',
  'fade_out_seconds',
  'keep_original_audio',
  'original_volume',
  'audio_fit',
  'normalize',
  'audio_bitrate',
  'output_format',
  // concat
  'transition',
  'transition_seconds',
  'target_width',
  'target_height',
  'target_fps',
  // frame
  'at_seconds',
  'image_format',
  'width',
  // overlay
  'image',
  'position',
  'margin_px',
  'width_percent',
  'opacity',
  'start_seconds',
  'end_seconds',
  // subtitles
  'style',
  'font_family',
  'font_size_percent',
  'position_percent',
  'text_color',
  'outline_color',
] as const;

const SUBTITLE_CUE_PARAM_KEYS = ['start_seconds', 'end_seconds', 'text'] as const;

const CONCAT_INPUT_PARAM_KEYS = [
  'source',
  'trim_start_seconds',
  'trim_end_seconds',
  'speed',
] as const;

const TRACK_PARAM_KEYS = [
  'id',
  'source',
  'volume',
  'offset_seconds',
  'trim_start_seconds',
  'trim_end_seconds',
  'loop',
  'fade_in_seconds',
  'fade_out_seconds',
  'speed',
  'duck_under',
  'duck_amount_db',
  'duck_attack_ms',
  'duck_release_ms',
] as const;

/** A concat input clip as stored in builder node data AND in the plan (contract field names). */
export interface MediaConcatInput {
  source: string | Record<string, unknown>;
  trim_start_seconds?: number | string;
  trim_end_seconds?: number | string;
  speed?: number | string;
}

/** A subtitle cue as stored in builder node data AND in the plan (contract field names). */
export interface MediaSubtitleCue {
  start_seconds?: number | string;
  end_seconds?: number | string;
  text?: string;
}

/** A mix track as stored in builder node data AND in the plan (contract field names). */
export interface MediaTrack {
  id?: string;
  // an expression string OR a literal FileRef object (both accepted by the backend)
  source: string | Record<string, unknown>;
  volume?: number | string;
  offset_seconds?: number | string;
  trim_start_seconds?: number | string;
  trim_end_seconds?: number | string;
  loop?: boolean | string;
  fade_in_seconds?: number | string;
  fade_out_seconds?: number | string;
  speed?: number | string;
  duck_under?: string;
  duck_amount_db?: number | string;
  duck_attack_ms?: number | string;
  duck_release_ms?: number | string;
}

function isEmptyValue(v: unknown): boolean {
  return v === undefined || v === null || (typeof v === 'string' && v.trim() === '');
}

function putParam(target: Record<string, any>, source: Record<string, any>, key: string, defaultValue?: unknown): void {
  const v = source[key];
  if (isEmptyValue(v)) return;
  if (defaultValue !== undefined && v === defaultValue) return;
  target[key] = v;
}

/**
 * A file param is an expression STRING or a LITERAL FileRef object (Files picker /
 * agent-built plans - the backend accepts both). Export must pass the object through:
 * coercing it to '' silently wiped an agent-configured literal ref on the next
 * builder save.
 */
export function fileParamValue(v: unknown): unknown {
  if (typeof v === 'string') return v;
  if (v && typeof v === 'object' && typeof (v as Record<string, any>).path === 'string') return v;
  return '';
}

function buildConcatInputPlanParams(raw: any): Record<string, any> {
  const i = (raw && typeof raw === 'object' ? raw : {}) as Record<string, any>;
  const item: Record<string, any> = {};
  // source is the clip's required param: always emitted (empty when unset) so
  // validation has something to point at; literal FileRefs pass through.
  item.source = fileParamValue(i.source);
  putParam(item, i, 'trim_start_seconds');
  putParam(item, i, 'trim_end_seconds');
  putParam(item, i, 'speed', MEDIA_SPEED_DEFAULT);
  return item;
}

function buildSubtitleCuePlanParams(raw: any): Record<string, any> {
  const c = (raw && typeof raw === 'object' ? raw : {}) as Record<string, any>;
  const cue: Record<string, any> = {};
  // All three are required per cue: always emitted (empty when unset) so validation
  // has something to point at, rather than a cue that silently disappears.
  cue.start_seconds = c.start_seconds ?? '';
  cue.end_seconds = c.end_seconds ?? '';
  cue.text = typeof c.text === 'string' ? c.text : '';
  return cue;
}

function buildTrackPlanParams(raw: any): Record<string, any> {
  const t = (raw && typeof raw === 'object' ? raw : {}) as Record<string, any>;
  const track: Record<string, any> = {};
  putParam(track, t, 'id');
  // source is the track's required param: always emitted (empty when unset) so
  // validation has something to point at, mirroring public_link's `file`.
  track.source = fileParamValue(t.source);
  putParam(track, t, 'volume', MEDIA_VOLUME_DEFAULT);
  putParam(track, t, 'offset_seconds', 0);
  putParam(track, t, 'trim_start_seconds');
  putParam(track, t, 'trim_end_seconds');
  putParam(track, t, 'loop', false);
  putParam(track, t, 'fade_in_seconds', 0);
  putParam(track, t, 'fade_out_seconds', 0);
  putParam(track, t, 'speed', MEDIA_SPEED_DEFAULT);
  if (typeof t.duck_under === 'string' && t.duck_under.trim() !== '') {
    track.duck_under = t.duck_under;
    putParam(track, t, 'duck_amount_db', MEDIA_DUCK_AMOUNT_DB_DEFAULT);
    putParam(track, t, 'duck_attack_ms', MEDIA_DUCK_ATTACK_MS_DEFAULT);
    putParam(track, t, 'duck_release_ms', MEDIA_DUCK_RELEASE_MS_DEFAULT);
  }
  return track;
}

/**
 * Serialize builder node data (mediaOperation + mediaParams) into the plan's
 * generic params map with the EXACT contract field names. Values equal to the
 * contract defaults are omitted; required file expressions (input/video/audio/
 * track source) are always emitted, empty when unset, so validation can flag them.
 */
export function buildMediaPlanParams(
  operation: string | undefined,
  rawParams: Record<string, any> | undefined,
): Record<string, any> {
  const p = rawParams || {};
  const op = typeof operation === 'string' ? operation : '';
  const params: Record<string, any> = { operation: op };

  switch (op) {
    case 'probe':
      params.input = fileParamValue(p.input);
      break;
    case 'extract_audio':
      params.input = fileParamValue(p.input);
      putParam(params, p, 'output_format', MEDIA_OUTPUT_FORMAT_DEFAULT);
      putParam(params, p, 'audio_bitrate', MEDIA_AUDIO_BITRATE_DEFAULT);
      putParam(params, p, 'trim_start_seconds');
      putParam(params, p, 'trim_end_seconds');
      break;
    case 'mux_audio':
      params.video = fileParamValue(p.video);
      params.audio = fileParamValue(p.audio);
      putParam(params, p, 'volume', MEDIA_VOLUME_DEFAULT);
      putParam(params, p, 'offset_seconds', 0);
      putParam(params, p, 'trim_start_seconds');
      putParam(params, p, 'trim_end_seconds');
      putParam(params, p, 'loop', false);
      putParam(params, p, 'fade_in_seconds', 0);
      putParam(params, p, 'fade_out_seconds', MEDIA_MUX_FADE_OUT_DEFAULT);
      putParam(params, p, 'keep_original_audio', false);
      if (p.keep_original_audio === true) {
        putParam(params, p, 'original_volume', MEDIA_VOLUME_DEFAULT);
      }
      putParam(params, p, 'audio_fit', MEDIA_AUDIO_FIT_DEFAULT);
      putParam(params, p, 'normalize', true);
      putParam(params, p, 'audio_bitrate', MEDIA_AUDIO_BITRATE_DEFAULT);
      break;
    case 'mix':
      putParam(params, p, 'video');
      params.tracks = Array.isArray(p.tracks) ? p.tracks.map(buildTrackPlanParams) : [];
      // keep_original_audio only exists WITH a video (the backend rejects it on an
      // audio-only mix): drop a stale toggle left over from before the video was removed.
      if (!isEmptyValue(p.video)) {
        putParam(params, p, 'keep_original_audio', false);
        if (p.keep_original_audio === true) {
          putParam(params, p, 'original_volume', MEDIA_VOLUME_DEFAULT);
        }
      }
      putParam(params, p, 'audio_fit', MEDIA_AUDIO_FIT_DEFAULT);
      putParam(params, p, 'normalize', true);
      putParam(params, p, 'audio_bitrate', MEDIA_AUDIO_BITRATE_DEFAULT);
      // With a video the output is FORCED to mp4: output_format only applies audio-only
      if (isEmptyValue(p.video)) {
        putParam(params, p, 'output_format', MEDIA_OUTPUT_FORMAT_DEFAULT);
      }
      break;
    case 'concat':
      params.inputs = Array.isArray(p.inputs) ? p.inputs.map(buildConcatInputPlanParams) : [];
      putParam(params, p, 'transition', MEDIA_TRANSITION_DEFAULT);
      // transition_seconds only exists WITH a crossfade: drop a stale value left
      // over from before the transition was switched back to cut.
      if (p.transition === 'crossfade') {
        putParam(params, p, 'transition_seconds', MEDIA_TRANSITION_SECONDS_DEFAULT);
      }
      putParam(params, p, 'target_width');
      putParam(params, p, 'target_height');
      putParam(params, p, 'target_fps');
      putParam(params, p, 'fade_in_seconds', 0);
      putParam(params, p, 'fade_out_seconds', 0);
      // NOTE: concat's normalize default is FALSE (differs from mux/mix): loudness
      // normalization forces the re-encode path, so it is opt-in here.
      putParam(params, p, 'normalize', false);
      putParam(params, p, 'audio_bitrate', MEDIA_AUDIO_BITRATE_DEFAULT);
      break;
    case 'frame':
      params.input = fileParamValue(p.input);
      putParam(params, p, 'at_seconds');
      putParam(params, p, 'image_format', MEDIA_IMAGE_FORMAT_DEFAULT);
      putParam(params, p, 'width');
      break;
    case 'overlay':
      params.video = fileParamValue(p.video);
      params.image = fileParamValue(p.image);
      putParam(params, p, 'position', MEDIA_OVERLAY_POSITION_DEFAULT);
      putParam(params, p, 'margin_px', MEDIA_MARGIN_PX_DEFAULT);
      putParam(params, p, 'width_percent', MEDIA_WIDTH_PERCENT_DEFAULT);
      putParam(params, p, 'opacity', MEDIA_OPACITY_DEFAULT);
      putParam(params, p, 'start_seconds');
      putParam(params, p, 'end_seconds');
      break;
    case 'subtitles':
      params.video = fileParamValue(p.video);
      // A caption track can be an EXPRESSION (computed upstream by a code node or a
      // transcription). Coercing it to [] here would silently wipe an agent-configured
      // track the first time the workflow was opened and saved in the builder - the
      // same data loss the literal-FileRef passthrough above exists to prevent.
      params.cues = typeof p.cues === 'string'
        ? p.cues
        : (Array.isArray(p.cues) ? p.cues.map(buildSubtitleCuePlanParams) : []);
      putParam(params, p, 'style', MEDIA_SUBTITLE_STYLE_DEFAULT);
      // font_family, font_size_percent, position_percent and the colours are omitted
      // unless set: the style preset owns the look, and the renderer owns the default
      // font (a family it cannot find is refused, never silently substituted).
      putParam(params, p, 'font_family');
      putParam(params, p, 'font_size_percent');
      putParam(params, p, 'position_percent');
      putParam(params, p, 'text_color', MEDIA_TEXT_COLOR_DEFAULT);
      putParam(params, p, 'outline_color', MEDIA_OUTLINE_COLOR_DEFAULT);
      break;
    default:
      // Unset/unknown operation: emit it as-is so validation flags it; nothing else.
      break;
  }
  return params;
}

/**
 * Extract builder node data from a plan's generic params map on import.
 * Known contract keys are kept VERBATIM (numbers stay numbers, booleans stay
 * booleans, template strings pass through); unknown keys are dropped. Tracks
 * are pruned to the known per-track contract keys.
 */
export function extractMediaDataFromPlanParams(
  params: Record<string, any> | undefined,
): { mediaOperation?: MediaOperation; mediaParams: Record<string, any> } {
  const p = params || {};
  const mediaParams: Record<string, any> = {};

  for (const key of SCALAR_PARAM_KEYS) {
    if (p[key] !== undefined && p[key] !== null) {
      mediaParams[key] = p[key];
    }
  }
  if (Array.isArray(p.inputs)) {
    mediaParams.inputs = p.inputs
      .filter((i: any) => i && typeof i === 'object')
      .map((i: Record<string, any>) => {
        const item: Record<string, any> = {};
        for (const key of CONCAT_INPUT_PARAM_KEYS) {
          if (i[key] !== undefined && i[key] !== null) {
            item[key] = i[key];
          }
        }
        return item;
      });
  }
  if (typeof p.cues === 'string') {
    // An expression survives the import verbatim, for the same reason it survives the
    // export: the builder must not be able to destroy a computed caption track.
    mediaParams.cues = p.cues;
  } else if (Array.isArray(p.cues)) {
    mediaParams.cues = p.cues
      .filter((c: any) => c && typeof c === 'object')
      .map((c: Record<string, any>) => {
        const cue: Record<string, any> = {};
        for (const key of SUBTITLE_CUE_PARAM_KEYS) {
          if (c[key] !== undefined && c[key] !== null) {
            cue[key] = c[key];
          }
        }
        return cue;
      });
  }
  if (Array.isArray(p.tracks)) {
    mediaParams.tracks = p.tracks
      .filter((t: any) => t && typeof t === 'object')
      .map((t: Record<string, any>) => {
        const track: Record<string, any> = {};
        for (const key of TRACK_PARAM_KEYS) {
          if (t[key] !== undefined && t[key] !== null) {
            track[key] = t[key];
          }
        }
        return track;
      });
  }

  return {
    ...(isMediaOperation(p.operation) ? { mediaOperation: p.operation } : {}),
    mediaParams,
  };
}
