// Unit + integration tests for the /internal/media v3 operation (subtitles). Same style
// as media-v2.test.js: pinned-argv builder tests against hand-built probes, every spec
// rejection case, and real-ffmpeg integration tests at the bottom (self-skipping when
// ffmpeg/ffprobe are not on PATH). The v1/v2 suites are untouched; this file only
// exercises the NEW operation plus the constants it added.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawnSync, execFile } = require('node:child_process');
const { promisify } = require('node:util');

const execFileAsync = promisify(execFile);

const {
  validateMediaSpec,
  buildProbeArgs,
  transformProbeJson,
  buildAssDocument,
  buildSubtitlesArgs,
  parseSubtitleColour,
  assTime,
  assEscape,
  mediaOutputExtension,
  SUBTITLE_STYLES,
  SUBTITLE_DEFAULT_FONT,
  SUBTITLE_MAX_CUES,
  SUBTITLE_MAX_TEXT_CHARS,
} = require('../lib');

const { runMediaOperation, probeFullInfo } = require('../media');

const BACKSLASH = String.fromCharCode(92);

// ---- fixtures ---------------------------------------------------------------

function subtitlesSpec(options = {}) {
  return {
    operation: 'subtitles',
    inputs: [{ name: 'input0', role: 'video' }],
    options: {
      cues: [
        { start_seconds: 0.1, end_seconds: 1, text: 'first line' },
        { start_seconds: 1, end_seconds: 2, text: 'second line' },
      ],
      ...options,
    },
  };
}

function validated(spec) {
  const v = validateMediaSpec(spec);
  assert.equal(v.ok, true, `expected a valid spec, got ${v.code}: ${v.error}`);
  return v.value;
}

function rejected(spec) {
  const v = validateMediaSpec(spec);
  assert.equal(v.ok, false, 'expected the spec to be rejected');
  assert.ok(v.code, 'a rejection must carry a machine code');
  assert.ok(v.error, 'a rejection must carry an agent-readable message');
  return v;
}

// A probe shaped like transformProbeJson's output, so the argv builders see real fields.
function probeStub({ width = 720, height = 1280, hasAudio = true, duration = 6 } = {}) {
  return {
    duration_seconds: duration,
    has_audio: hasAudio,
    has_video: true,
    video: { width, height },
  };
}

// ---- spec validation: the happy path ----------------------------------------

test('subtitles: a minimal spec normalises to the documented defaults', () => {
  const v = validated(subtitlesSpec());
  assert.equal(v.options.style, 'tiktok');
  assert.equal(v.options.fontFamily, SUBTITLE_DEFAULT_FONT);
  // null, not a number: the PRESET supplies the size once the video height is known, and
  // baking a default here would silently win over the preset the caller chose.
  assert.equal(v.options.fontSizePercent, null);
  assert.equal(v.options.positionPercent, null);
  assert.equal(v.options.primaryColour, '&H00FFFFFF');
  assert.equal(v.options.outlineColour, '&H00000000');
  assert.deepEqual(v.options.cues[0], { startSeconds: 0.1, endSeconds: 1, text: 'first line' });
});

test('subtitles: cue text is trimmed but never re-cased at validation time', () => {
  const v = validated(subtitlesSpec({ cues: [{ start_seconds: 0, end_seconds: 1, text: '  Keep My Case  ' }] }));
  assert.equal(v.options.cues[0].text, 'Keep My Case');
});

test('subtitles: the output is always an mp4', () => {
  assert.equal(mediaOutputExtension(validated(subtitlesSpec())), 'mp4');
});

// ---- spec validation: every rejection ---------------------------------------

test('subtitles: refuses a spec without exactly one video input', () => {
  const twoInputs = subtitlesSpec();
  twoInputs.inputs = [{ name: 'input0', role: 'video' }, { name: 'input1', role: 'image' }];
  assert.equal(rejected(twoInputs).code, 'INVALID_SPEC');

  const audioOnly = subtitlesSpec();
  audioOnly.inputs = [{ name: 'input0', role: 'audio' }];
  assert.equal(rejected(audioOnly).code, 'INVALID_SPEC');
});

test('subtitles: refuses missing, empty and over-long cue lists', () => {
  assert.match(rejected(subtitlesSpec({ cues: undefined })).error, /cues must be a non-empty array/);
  assert.match(rejected(subtitlesSpec({ cues: [] })).error, /cues must be a non-empty array/);
  const tooMany = Array.from({ length: SUBTITLE_MAX_CUES + 1 }, (_, i) => ({
    start_seconds: i, end_seconds: i + 0.5, text: 'x',
  }));
  const r = rejected(subtitlesSpec({ cues: tooMany }));
  assert.equal(r.code, 'VALUE_OUT_OF_RANGE');
  assert.match(r.error, new RegExp(String(SUBTITLE_MAX_CUES)));
});

test('subtitles: refuses a cue that ends before or exactly when it starts', () => {
  assert.match(rejected(subtitlesSpec({ cues: [{ start_seconds: 2, end_seconds: 2, text: 'x' }] })).error,
    /cues\[0\]\.end_seconds must be greater than cues\[0\]\.start_seconds/);
  assert.match(rejected(subtitlesSpec({ cues: [{ start_seconds: 2, end_seconds: 1, text: 'x' }] })).error,
    /must be greater than/);
});

test('subtitles: refuses a cue with missing timings or blank text', () => {
  assert.match(rejected(subtitlesSpec({ cues: [{ end_seconds: 1, text: 'x' }] })).error, /both required/);
  assert.match(rejected(subtitlesSpec({ cues: [{ start_seconds: 0, end_seconds: 1, text: '   ' }] })).error,
    /text must be a non-empty string/);
  assert.match(rejected(subtitlesSpec({ cues: [{ start_seconds: 0, end_seconds: 1, text: 42 }] })).error,
    /text must be a non-empty string/);
  const long = 'a'.repeat(SUBTITLE_MAX_TEXT_CHARS + 1);
  assert.match(rejected(subtitlesSpec({ cues: [{ start_seconds: 0, end_seconds: 1, text: long }] })).error,
    /longer than/);
});

test('subtitles: refuses overlapping cues instead of silently reordering them', () => {
  // Two cues over the same instant would STACK in libass. Repairing that quietly would
  // hide a timing bug and ship captions written over captions, so it is a hard refusal.
  const r = rejected(subtitlesSpec({
    cues: [
      { start_seconds: 0, end_seconds: 2, text: 'a' },
      { start_seconds: 1, end_seconds: 3, text: 'b' },
    ],
  }));
  assert.equal(r.code, 'VALUE_OUT_OF_RANGE');
  assert.match(r.error, /overlaps the previous cue/);
  assert.match(r.error, /ascending, non-overlapping/);
});

test('subtitles: refuses cues given out of order', () => {
  const r = rejected(subtitlesSpec({
    cues: [
      { start_seconds: 5, end_seconds: 6, text: 'later' },
      { start_seconds: 1, end_seconds: 2, text: 'earlier' },
    ],
  }));
  assert.match(r.error, /overlaps the previous cue/);
});

test('subtitles: refuses an unknown style, a bad colour, a bad font name and out-of-range sizes', () => {
  assert.match(rejected(subtitlesSpec({ style: 'neon' })).error, /style must be one of/);
  assert.match(rejected(subtitlesSpec({ text_color: 'red' })).error, /text_color must be a #RRGGBB/);
  assert.match(rejected(subtitlesSpec({ outline_color: '#12345' })).error, /outline_color must be a #RRGGBB/);
  // A comma or a newline in the family would break out of the ASS Style line.
  assert.match(rejected(subtitlesSpec({ font_family: 'Arial,Bold' })).error, /font_family must be/);
  assert.match(rejected(subtitlesSpec({ font_family: 'Arial\nStyle: evil' })).error, /font_family must be/);
  assert.match(rejected(subtitlesSpec({ font_size_percent: 0.5 })).error, /font_size_percent must be a number 1-20/);
  assert.match(rejected(subtitlesSpec({ font_size_percent: 21 })).error, /font_size_percent must be a number 1-20/);
  assert.match(rejected(subtitlesSpec({ position_percent: 101 })).error, /position_percent must be a number 0-100/);
});

// ---- colour, time and escaping ----------------------------------------------

test('parseSubtitleColour: #RRGGBB becomes the ASS &HAABBGGRR literal with the bytes swapped', () => {
  assert.equal(parseSubtitleColour('#FF0000', 'c', '#FFFFFF').value, '&H000000FF');
  assert.equal(parseSubtitleColour('#0000FF', 'c', '#FFFFFF').value, '&H00FF0000');
  assert.equal(parseSubtitleColour('00FF00', 'c', '#FFFFFF').value, '&H0000FF00');
  assert.equal(parseSubtitleColour(undefined, 'c', '#123456').value, '&H00563412');
  assert.match(parseSubtitleColour('#GGGGGG', 'c', '#FFFFFF').error, /must be a #RRGGBB/);
});

test('assTime: centiseconds, zero-padded minutes and seconds, unpadded hours', () => {
  assert.equal(assTime(0), '0:00:00.00');
  assert.equal(assTime(6.041), '0:00:06.04');
  assert.equal(assTime(59.999), '0:01:00.00');
  assert.equal(assTime(3725.5), '1:02:05.50');
  assert.equal(assTime(-3), '0:00:00.00');
});

test('assEscape: braces, backslashes and newlines cannot leak into the override syntax', () => {
  assert.equal(assEscape('a {b} c'), `a ${BACKSLASH}{b${BACKSLASH}} c`);
  assert.equal(assEscape(`x${BACKSLASH}y`), `x${BACKSLASH}${BACKSLASH}y`);
  assert.equal(assEscape('one\ntwo'), `one${BACKSLASH}Ntwo`);
  assert.equal(assEscape('one\r\ntwo'), `one${BACKSLASH}Ntwo`);
  // An attempt to inject an override block survives as literal text, not as a tag.
  assert.equal(assEscape('{\\an8}hi'), `${BACKSLASH}{${BACKSLASH}${BACKSLASH}an8${BACKSLASH}}hi`);
});

// ---- the ASS document -------------------------------------------------------

test('buildAssDocument: PlayRes matches the PROBED video, not libass default 384x288', () => {
  // Without PlayResX/Y libass assumes 384x288 and every caption comes out about a
  // quarter of the intended size on a phone cut.
  const doc = buildAssDocument(validated(subtitlesSpec()).options, 1080, 1920);
  assert.match(doc, /^PlayResX: 1080$/m);
  assert.match(doc, /^PlayResY: 1920$/m);
});

test('buildAssDocument: sizes scale with the video height so one preset reads the same at any resolution', () => {
  const options = validated(subtitlesSpec()).options;
  const small = styleFieldsOf(buildAssDocument(options, 720, 1280));
  const large = styleFieldsOf(buildAssDocument(options, 1080, 1920));
  // 1920 / 1280 = 1.5, so every vertical metric scales by 1.5. Both ends are rounded to
  // whole pixels independently, so the comparison tolerates the one-pixel rounding gap
  // (358.4 -> 358 against 537.6 -> 538) instead of pinning an exact product.
  assert.ok(Math.abs(Number(small.fontSize) * 1.5 - Number(large.fontSize)) <= 1,
    `fontSize did not scale: ${small.fontSize} -> ${large.fontSize}`);
  assert.ok(Math.abs(Number(small.marginV) * 1.5 - Number(large.marginV)) <= 1,
    `marginV did not scale: ${small.marginV} -> ${large.marginV}`);
});

test('buildAssDocument: the tiktok preset pins the exact style the format needs', () => {
  const f = styleFieldsOf(buildAssDocument(validated(subtitlesSpec()).options, 720, 1280));
  assert.equal(f.fontName, SUBTITLE_DEFAULT_FONT);
  assert.equal(f.fontSize, '56'); // 4.4% of 1280
  assert.equal(f.bold, '1');
  assert.equal(f.alignment, '2'); // bottom-centre, which is what MarginV is measured from
  assert.equal(f.marginV, '358'); // (100 - 72)% of 1280
  assert.equal(f.primary, '&H00FFFFFF');
  assert.equal(f.outlineColour, '&H00000000');
});

test('buildAssDocument: font_size_percent and position_percent override the preset', () => {
  const options = validated(subtitlesSpec({ font_size_percent: 10, position_percent: 50 })).options;
  const f = styleFieldsOf(buildAssDocument(options, 720, 1280));
  assert.equal(f.fontSize, '128'); // 10% of 1280
  assert.equal(f.marginV, '640'); // (100 - 50)% of 1280
});

test('buildAssDocument: tiktok upper-cases the text, classic leaves it alone', () => {
  const cues = [{ start_seconds: 0, end_seconds: 1, text: 'Keep My Case' }];
  const tiktok = buildAssDocument(validated(subtitlesSpec({ cues })).options, 720, 1280);
  const classic = buildAssDocument(validated(subtitlesSpec({ cues, style: 'classic' })).options, 720, 1280);
  assert.match(tiktok, /KEEP MY CASE$/m);
  assert.match(classic, /Keep My Case$/m);
  assert.equal(SUBTITLE_STYLES.tiktok.uppercase, true);
  assert.equal(SUBTITLE_STYLES.classic.uppercase, false);
});

test('buildAssDocument: one Dialogue line per cue, in order, with the cue timings', () => {
  const doc = buildAssDocument(validated(subtitlesSpec()).options, 720, 1280);
  const lines = doc.split('\n').filter((l) => l.startsWith('Dialogue:'));
  assert.equal(lines.length, 2);
  assert.match(lines[0], /^Dialogue: 0,0:00:00\.10,0:00:01\.00,LC,/);
  assert.match(lines[1], /^Dialogue: 0,0:00:01\.00,0:00:02\.00,LC,/);
});

test('buildAssDocument: a cue carrying ASS syntax is written as literal text', () => {
  const doc = buildAssDocument(
    validated(subtitlesSpec({ cues: [{ start_seconds: 0, end_seconds: 1, text: '50% {of} it' }] })).options,
    720, 1280);
  const line = doc.split('\n').find((l) => l.startsWith('Dialogue:'));
  assert.ok(line.endsWith(`50% ${BACKSLASH}{OF${BACKSLASH}} IT`), `unexpected dialogue line: ${line}`);
});

// Pull the V4+ Style line apart so assertions name fields instead of column numbers.
function styleFieldsOf(doc) {
  const line = doc.split('\n').find((l) => l.startsWith('Style: LC,'));
  assert.ok(line, 'the document has no LC style line');
  const f = line.slice('Style: '.length).split(',');
  return {
    name: f[0], fontName: f[1], fontSize: f[2], primary: f[3], secondary: f[4],
    outlineColour: f[5], back: f[6], bold: f[7], outline: f[16], shadow: f[17],
    alignment: f[18], marginL: f[19], marginR: f[20], marginV: f[21],
  };
}

// ---- argv -------------------------------------------------------------------

test('buildSubtitlesArgs: the ass filter names the file RELATIVELY (the filter parses ":" itself)', () => {
  const args = buildSubtitlesArgs(validated(subtitlesSpec()), probeStub(),
    { parts: { input0: { path: '/w/input0.mp4' } }, assName: 'subtitles.ass', outputPath: '/w/out.mp4' });
  const vf = args[args.indexOf('-vf') + 1];
  assert.equal(vf, 'ass=subtitles.ass');
  assert.ok(!vf.includes(':'), 'an absolute path here would be truncated by the filter parser');
});

test('buildSubtitlesArgs: audio is stream-copied when the input has some, and absent when it has none', () => {
  const paths = { parts: { input0: { path: '/w/input0.mp4' } }, assName: 'subtitles.ass', outputPath: '/w/out.mp4' };
  const withAudio = buildSubtitlesArgs(validated(subtitlesSpec()), probeStub({ hasAudio: true }), paths);
  assert.ok(withAudio.join(' ').includes('-c:a copy'));
  const noAudio = buildSubtitlesArgs(validated(subtitlesSpec()), probeStub({ hasAudio: false }), paths);
  assert.ok(!noAudio.join(' ').includes('-c:a'));
});

test('buildSubtitlesArgs: the video is re-encoded, because burnt captions are pixels', () => {
  const args = buildSubtitlesArgs(validated(subtitlesSpec()), probeStub(),
    { parts: { input0: { path: '/w/input0.mp4' } }, assName: 'subtitles.ass', outputPath: '/w/out.mp4' });
  assert.ok(args.join(' ').includes('-c:v libx264'));
  assert.ok(args.join(' ').includes('-movflags +faststart'));
  assert.equal(args[args.length - 1], '/w/out.mp4');
});

// ---- integration: real ffmpeg -----------------------------------------------

const ffmpegOnPath = (() => {
  try {
    return spawnSync('ffmpeg', ['-version'], { timeout: 5000 }).status === 0
      && spawnSync('ffprobe', ['-version'], { timeout: 5000 }).status === 0;
  } catch (_) {
    return false;
  }
})();

const fcMatchOnPath = (() => {
  try {
    return spawnSync('fc-match', ['--version'], { timeout: 5000 }).status === 0;
  } catch (_) {
    return false;
  }
})();

const FIXTURE_TIMEOUT = 120000;

async function makeClip(outPath, { duration = 3, size = '320x240', rate = 10, audio = true,
                                   black = false } = {}) {
  // black:true gives a source with NO ink of its own, so any luminance in the output
  // is necessarily burnt-in caption - which is what lets a test ask WHERE the caption
  // landed rather than only whether the frame changed.
  const source = black
    ? `color=c=black:duration=${duration}:size=${size}:rate=${rate}`
    : `testsrc=duration=${duration}:size=${size}:rate=${rate}`;
  const args = [
    '-nostdin', '-y', '-loglevel', 'error',
    '-f', 'lavfi', '-i', source,
  ];
  if (audio) args.push('-f', 'lavfi', '-i', `sine=frequency=440:duration=${duration}`, '-c:a', 'aac', '-shortest');
  args.push('-pix_fmt', 'yuv420p', outPath);
  await execFileAsync('ffmpeg', args, { timeout: FIXTURE_TIMEOUT });
}

async function probeOut(filePath) {
  const { stdout } = await execFileAsync('ffprobe', buildProbeArgs(filePath),
    { timeout: 15000, maxBuffer: 16 * 1024 * 1024 });
  return transformProbeJson(JSON.parse(stdout));
}

async function frameBytes(filePath, atSeconds, workDir, tag) {
  const out = path.join(workDir, `frame-${tag}.png`);
  await execFileAsync('ffmpeg', [
    '-nostdin', '-y', '-loglevel', 'error', '-ss', String(atSeconds), '-i', filePath,
    '-frames:v', '1', out,
  ], { timeout: FIXTURE_TIMEOUT });
  return fs.readFile(out);
}

test('integration: burning captions keeps the duration and the audio track', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 3 });
    // The default family is only guaranteed inside the sidecar image, so the integration
    // tests pin whatever fontconfig resolves here rather than assuming DejaVu is present.
    const spec = validated(subtitlesSpec({ font_family: localFontFamily() }));
    const out = await runMediaOperation(spec, { input0: input }, workDir, { execFileAsync });
    assert.equal(out.kind, 'file');
    assert.equal(out.mime, 'video/mp4');
    const probe = await probeOut(out.path);
    assert.ok(Math.abs(probe.duration_seconds - 3) < 0.35, `duration drifted: ${probe.duration_seconds}`);
    assert.equal(probe.has_audio, true);
    assert.equal(probe.video.width, 320);
    assert.equal(probe.video.height, 240);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: a clip with no audio stays silent instead of failing', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 2, audio: false });
    const spec = validated(subtitlesSpec({ font_family: localFontFamily() }));
    const out = await runMediaOperation(spec, { input0: input }, workDir, { execFileAsync });
    const probe = await probeOut(out.path);
    assert.equal(probe.has_audio, false);
    assert.equal(probe.has_video, true);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: the caption is visible only BETWEEN its start and end', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  // Measured as INK on a black source, not as "the frame changed": the whole video is
  // re-encoded, so every output frame differs from its source frame whether a caption
  // was drawn or not. A frame-equality assertion here passes on a run that burnt
  // nothing, on one that burnt the caption over the entire clip, and on one that
  // clipped it off the canvas - it cannot fail for the reasons that matter.
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 4, audio: false, size: '320x240', black: true });
    const spec = validated(subtitlesSpec({
      font_family: localFontFamily(),
      cues: [{ start_seconds: 0.5, end_seconds: 1.5, text: 'BURNED IN' }],
    }));
    const out = await runMediaOperation(spec, { input0: input }, workDir, { execFileAsync });

    const before = await stripLuma(out.path, 0.1, 0, 320, 240, workDir, 'before');
    const during = await stripLuma(out.path, 1.0, 0, 320, 240, workDir, 'during');
    const after = await stripLuma(out.path, 3.0, 0, 320, 240, workDir, 'after');

    assert.ok(during > 150, `no caption on screen inside the cue window (YMAX ${during})`);
    assert.ok(before <= NOISE_YMAX, `something is drawn BEFORE the cue starts (YMAX ${before})`);
    assert.ok(after <= NOISE_YMAX, `the caption never cleared after the cue ended (YMAX ${after})`);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

/**
 * Peak luminance (YMAX) of a strip of one frame. Used to ask "is there ink HERE?" on a
 * black source clip.
 *
 * A black frame does not come back as a perfect 0 after an h264 re-encode: measured
 * noise sits around 17. Drawn caption glyphs read ~235. NOISE_YMAX sits between the
 * two, far enough from both that neither codec noise nor a real caption can land on
 * the wrong side of it.
 */
const NOISE_YMAX = 60;

async function stripLuma(filePath, atSeconds, x, width, height, workDir, tag) {
  const out = path.join(workDir, `strip-${tag}.txt`);
  const { stdout } = await execFileAsync('ffmpeg', [
    '-nostdin', '-loglevel', 'error', '-ss', String(atSeconds), '-i', filePath,
    '-frames:v', '1', '-vf', `crop=${width}:${height}:${x}:0,signalstats,metadata=print:file=-`,
    '-f', 'null', '-',
  ], { timeout: FIXTURE_TIMEOUT, maxBuffer: 16 * 1024 * 1024 });
  const match = /lavfi\.signalstats\.YMAX=(\d+)/.exec(stdout);
  await fs.rm(out, { force: true });
  return match ? Number(match[1]) : 0;
}

test('integration: a long caption WRAPS inside the frame instead of bleeding off both edges', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  // This is the property a burn-in feature exists to deliver, and the one a
  // "the frame changed" assertion cannot see: a caption clipped off both sides
  // changes the frame exactly as much as a correctly wrapped one. The renderer
  // shipped WrapStyle 2 (no wrapping at all), which made the style's side margins
  // inert and pushed any long line straight off the canvas, with HTTP 200 and a
  // playable mp4 to show for it.
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 2, audio: false, size: '540x960', black: true });
    const spec = validated(subtitlesSpec({
      font_family: localFontFamily(),
      cues: [{
        start_seconds: 0.2,
        end_seconds: 1.8,
        text: 'Ninety metres below the surface nothing has moved for eight long months',
      }],
    }));
    const out = await runMediaOperation(spec, { input0: input }, workDir, { execFileAsync });

    const EDGE = 4;
    const leftEdge = await stripLuma(out.path, 1, 0, EDGE, 960, workDir, 'left');
    const rightEdge = await stripLuma(out.path, 1, 540 - EDGE, EDGE, 960, workDir, 'right');
    const centre = await stripLuma(out.path, 1, 250, 40, 960, workDir, 'centre');

    assert.ok(centre > 150, `no caption found in the centre of the frame (YMAX ${centre})`);
    assert.ok(leftEdge <= NOISE_YMAX,
      `caption ink reaches the LEFT edge (YMAX ${leftEdge}): the line is clipped`);
    assert.ok(rightEdge <= NOISE_YMAX,
      `caption ink reaches the RIGHT edge (YMAX ${rightEdge}): the line is clipped`);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('optNum refuses a non-number rather than coercing it to one', () => {
  // Number([]) is 0 and Number(true) is 1, so a coerced value would have been read as
  // a timing the caller never wrote - and the orchestrator refuses both.
  for (const bogus of [[], true, {}]) {
    const v = validateMediaSpec(subtitlesSpec({
      cues: [{ start_seconds: bogus, end_seconds: 2, text: 'Hi' }],
    }));
    assert.equal(v.ok, false, `${JSON.stringify(bogus)} was accepted as a timing`);
    assert.match(v.error, /must be a number/);
  }
});

test('a caption track in a non-Latin script is bounded by TOTAL length, not just per line', () => {
  // 240 CJK characters is ~720 bytes of UTF-8, so 600 legal cues serialise far past the
  // multipart spec cap - where the request is TRUNCATED in transit rather than refused,
  // and the caller is told their JSON is malformed. The total-length rule is what turns
  // that into an answer about the caption track.
  const line = 'AAA'.replace(/A/g, '\u4e2d');
  const cues = Array.from({ length: 600 }, (_, i) => ({
    start_seconds: i, end_seconds: i + 0.5, text: line.repeat(80),
  }));
  const v = validateMediaSpec(subtitlesSpec({ cues }));
  assert.equal(v.ok, false);
  assert.equal(v.code, 'VALUE_OUT_OF_RANGE');
  assert.match(v.error, /caption track is longer than \d+ characters in total/);
});

test('a non-Latin caption inside the limits is accepted and passed through unchanged', () => {
  const v = validateMediaSpec(subtitlesSpec({
    cues: [{ start_seconds: 0, end_seconds: 2, text: '\u4e5d\u5341\u7c73\u6df1\u7684\u6c34\u4e0b' }],
  }));
  assert.equal(v.ok, true);
  assert.equal(v.value.options.cues[0].text, '\u4e5d\u5341\u7c73\u6df1\u7684\u6c34\u4e0b');
});

test('a BLANK timing is treated as ABSENT, not as zero', () => {
  // Number('') is 0, so without the guard the sidecar accepted a half-written cue that
  // the orchestrator refuses - the two layers disagreeing about the same document.
  const v = validateMediaSpec(subtitlesSpec({
    cues: [{ start_seconds: '', end_seconds: '', text: 'Half written' }],
  }));
  assert.equal(v.ok, false);
  assert.match(v.error, /are both required/);
});

test('buildAssDocument: position_percent 0 keeps the line ON the frame, not above it', () => {
  // Alignment 2 measures the margin up from the bottom, so an uncapped margin of the
  // full height pushed the caption clean off the canvas: no captions, no error.
  const doc = buildAssDocument(
    validated(subtitlesSpec({ position_percent: 0 })).options, 720, 1280);
  const style = doc.split('\n').find((l) => l.startsWith('Style: LC,'));
  const marginV = Number(style.split(',')[21]);
  const fontSize = Number(style.split(',')[2]);
  assert.ok(marginV < 1280, `marginV ${marginV} leaves no room on a 1280-tall frame`);
  assert.ok(marginV + fontSize <= 1280,
    `a line of ${fontSize} at marginV ${marginV} does not fit inside 1280`);
});

test('buildAssDocument: position_percent 100 puts the line at the very bottom', () => {
  const doc = buildAssDocument(
    validated(subtitlesSpec({ position_percent: 100 })).options, 720, 1280);
  const style = doc.split('\n').find((l) => l.startsWith('Style: LC,'));
  assert.equal(Number(style.split(',')[21]), 0);
});

test('integration: braces and backslashes in a caption are drawn, not swallowed as ASS markup', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  // { } and \ are ASS override syntax. Unescaped, the caption silently disappears or
  // loses its text - a 200 with the wrong picture, which is what this whole operation
  // is written against. Measured as INK on a black clip, so it cannot pass by accident.
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 2, audio: false, size: '320x240', black: true });
    const spec = validated(subtitlesSpec({
      font_family: localFontFamily(),
      cues: [{ start_seconds: 0.2, end_seconds: 1.8, text: 'a {b} c \\d e' }],
    }));
    const out = await runMediaOperation(spec, { input0: input }, workDir, { execFileAsync });

    const during = await stripLuma(out.path, 1.0, 0, 320, 240, workDir, 'braces');
    assert.ok(during > 150,
      `nothing was drawn for a caption containing ASS syntax (YMAX ${during}): it was swallowed`);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: a track whose cues all sit past the end of the video is REFUSED', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  // Otherwise: HTTP 200, right duration, right audio, and not one caption on screen.
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 2, audio: false });
    const spec = validated(subtitlesSpec({
      font_family: localFontFamily(),
      cues: [{ start_seconds: 300, end_seconds: 302, text: 'Timed against another cut' }],
    }));
    await assert.rejects(
      () => runMediaOperation(spec, { input0: input }, workDir, { execFileAsync }),
      (err) => {
        assert.equal(err.status, 422);
        assert.match(err.message, /every caption starts at or after the end of the video/);
        return true;
      });
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: a video with NO video stream is refused, not captioned into nothing', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const audioOnly = path.join(workDir, 'input0.m4a');
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1',
      '-c:a', 'aac', audioOnly,
    ], { timeout: FIXTURE_TIMEOUT });
    const spec = validated(subtitlesSpec({ font_family: localFontFamily() }));
    await assert.rejects(
      () => runMediaOperation(spec, { input0: audioOnly }, workDir, { execFileAsync }),
      (err) => {
        assert.equal(err.status, 422);
        assert.match(err.message, /has no video stream/);
        return true;
      });
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: when fc-match itself is unavailable the render PROCEEDS, and says so', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  // Fail OPEN is the right call - a missing diagnostic tool must not refuse an
  // otherwise valid render - but it means the font guard silently did not run. The
  // warning is the only trace that the burn was unverified, so it is part of the
  // contract, not decoration.
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  const warnings = [];
  const realWarn = console.warn;
  console.warn = (...args) => warnings.push(args.join(' '));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 1, audio: false });
    const spec = validated(subtitlesSpec({ font_family: 'Absolutely No Such Face' }));
    // Every call behaves normally EXCEPT fc-match, which is made to look absent.
    const execWithoutFontconfig = (file, args, opts) => (file === 'fc-match'
      ? Promise.reject(new Error('spawn fc-match ENOENT'))
      : execFileAsync(file, args, opts));

    const out = await runMediaOperation(spec, { input0: input }, workDir,
      { execFileAsync: execWithoutFontconfig });

    assert.equal(out.kind, 'file', 'a missing fc-match must not fail an otherwise valid render');
    assert.ok(warnings.some((w) => w.includes('font verification unavailable')),
      `nothing warned that the font went unverified: ${JSON.stringify(warnings)}`);
  } finally {
    console.warn = realWarn;
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: a font fontconfig resolves to ANOTHER family is refused (stubbed fc-match)', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  // The same guarantee as the test below, but with fc-match STUBBED, so the guard has
  // coverage on any machine - including a CI runner where fontconfig cannot be
  // installed. Without this the headline safety property was verified only where
  // fontconfig happened to exist, which is a guard that ships unverified.
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 1, audio: false });
    const spec = validated(subtitlesSpec({ font_family: 'Helvetica' }));
    // Exactly what fontconfig does on a Linux image: Helvetica is ALIASED to a
    // metric-compatible substitute rather than being present itself.
    const execWithAlias = (file, args, opts) => (file === 'fc-match'
      ? Promise.resolve({ stdout: 'Nimbus Sans\n', stderr: '' })
      : execFileAsync(file, args, opts));

    await assert.rejects(
      () => runMediaOperation(spec, { input0: input }, workDir, { execFileAsync: execWithAlias }),
      (err) => {
        assert.equal(err.status, 422);
        assert.match(err.message, /is not available on the renderer as itself/);
        assert.match(err.message, /Nimbus Sans/, 'the message must name what it resolved to');
        return true;
      });
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: a font family that RESOLVES TO ITSELF is accepted (stubbed fc-match)', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH');
    return;
  }
  // The other half of the guard: it must not refuse a family that IS installed, or
  // the default path would be broken everywhere.
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 1, audio: false });
    const spec = validated(subtitlesSpec({ font_family: 'DejaVu Sans' }));
    const execExact = (file, args, opts) => (file === 'fc-match'
      ? Promise.resolve({ stdout: 'DejaVu Sans\n', stderr: '' })
      : execFileAsync(file, args, opts));

    const out = await runMediaOperation(spec, { input0: input }, workDir, { execFileAsync: execExact });
    assert.equal(out.kind, 'file');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: a font family that is not installed is REFUSED, never substituted', async (t) => {
  if (!ffmpegOnPath || !fcMatchOnPath) {
    t.skip('ffmpeg and fontconfig both required (both present in the sidecar image)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v3-it-'));
  try {
    const input = path.join(workDir, 'input0.mp4');
    await makeClip(input, { duration: 1, audio: false });
    const spec = validated(subtitlesSpec({ font_family: 'Absolutely No Such Face' }));
    await assert.rejects(
      () => runMediaOperation(spec, { input0: input }, workDir, { execFileAsync }),
      (err) => {
        assert.equal(err.status, 422);
        assert.match(err.message, /is not available on the renderer as itself/);
        return true;
      });
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

// fc-match tells us what this HOST actually has; the sidecar image guarantees DejaVu Sans
// but a dev laptop may not, and the run-time guard would then refuse the render.
function localFontFamily() {
  if (!fcMatchOnPath) return SUBTITLE_DEFAULT_FONT;
  const r = spawnSync('fc-match', [`${SUBTITLE_DEFAULT_FONT}:family`, 'family'], { timeout: 5000, encoding: 'utf8' });
  const matched = String(r.stdout || '').trim().split(',')[0].trim();
  return matched.length > 0 ? matched : SUBTITLE_DEFAULT_FONT;
}
