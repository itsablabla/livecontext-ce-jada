// Impure side of POST /internal/media: multipart intake (busboy), the quick per-input
// ffprobe duration pass, and running the ffmpeg/ffprobe children. The pure spec
// validation + argv builders live in lib.js so they stay unit-testable; server.js
// wires this module into Express. ffmpeg-only work needs NO browser page, so none of
// this touches the RenderPool - server.js guards it with its own small Semaphore.

const fsSync = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const busboy = require('busboy');

const {
  mediaError,
  computeMediaTimeoutMs,
  buildProbeArgs,
  transformProbeJson,
  extractVideoSar,
  buildMuxAudioArgs,
  buildMixArgs,
  buildExtractAudioArgs,
  validateConcatStatic,
  planConcat,
  buildConcatListText,
  buildConcatCopyArgs,
  buildConcatFilterArgs,
  resolveFrameTimestamp,
  buildFrameArgs,
  isImageProbeFormat,
  buildOverlayArgs,
  buildAssDocument,
  buildSubtitlesArgs,
  mediaOutputExtension,
  MEDIA_MIME_TYPES,
  MEDIA_STDERR_TAIL_BYTES,
  MEDIA_TIMEOUT_MIN_MS,
} = require('./lib');

const defaultExecFileAsync = promisify(execFile);

// The quick duration probe is bounded independently of the main ffmpeg budget.
const PROBE_TIMEOUT_MS = 15000;
// ffprobe JSON + ffmpeg stderr both fit comfortably; same order as the video transcode's cap.
const MEDIA_MAX_BUFFER = 16 * 1024 * 1024;
// Extension hint kept from the uploaded filename (helps ffmpeg's demuxer probing).
const SAFE_EXTENSION_PATTERN = /^\.[A-Za-z0-9]{1,5}$/;
// A real spec is a few KB; cap it so a chunked request cannot buffer unbounded JSON.
const MAX_SPEC_BYTES = 256 * 1024;

function stderrTailOf(err) {
  const s = err && err.stderr ? String(err.stderr) : '';
  return s.slice(-MEDIA_STDERR_TAIL_BYTES);
}

// execFileAsync's timeout kill surfaces as err.killed / err.signal - that is OUR budget
// firing, not an ffmpeg defect, so it maps to 504 instead of 422.
function isTimeoutKill(err) {
  return !!(err && (err.killed || err.signal));
}

/**
 * Parse a multipart/form-data request: the `spec` part (JSON, sent as a field or as a
 * file-less part) is buffered, every `input<N>` binary part is streamed to workDir, and
 * the summed byte count is enforced against maxTotalBytes (413 on breach). Resolves
 * { specText, parts: { <name>: absolutePath }, totalBytes }.
 */
function parseMediaMultipart(req, workDir, { maxTotalBytes }) {
  return new Promise((resolve, reject) => {
    let bb;
    try {
      // fieldSize caps the spec sent as a FIELD; the spec-as-file path below applies
      // the same cap manually (chunked encoding bypasses the Content-Length precheck,
      // so an uncapped spec buffer would be an unbounded memory sink).
      bb = busboy({ headers: req.headers, limits: { files: 20, fields: 10, fieldSize: MAX_SPEC_BYTES } });
    } catch (err) {
      reject(mediaError(400, 'INVALID_SPEC', `expected multipart/form-data: ${err.message}`));
      return;
    }
    const parts = {};
    const pendingWrites = [];
    let specText = null;
    let totalBytes = 0;
    let settled = false;

    const fail = (err) => {
      if (settled) return;
      settled = true;
      try { req.unpipe(bb); } catch (_) { /* already detached */ }
      try { req.resume(); } catch (_) { /* stream gone */ }
      reject(err);
    };

    bb.on('field', (name, value, info) => {
      if (name !== 'spec') return;
      // busboy TRUNCATES an oversized field rather than failing, so without this the
      // spec is parsed as JSON, the parse fails, and the caller is told their document
      // is malformed - sending them to hunt a syntax error in a document they wrote
      // correctly. Say what actually happened instead.
      if (info && info.valueTruncated) {
        fail(mediaError(413, 'INPUT_TOO_LARGE',
          `the spec exceeds the ${MAX_SPEC_BYTES} byte limit and was truncated in transit; `
          + 'send fewer or shorter entries (a long caption track is the usual cause)'));
        return;
      }
      specText = value;
    });

    bb.on('file', (name, stream, info) => {
      if (name === 'spec') {
        // Some clients attach the JSON with a filename - buffer it like the field path,
        // with the same size cap as the field variant.
        let buf = '';
        stream.on('data', (d) => {
          buf += d;
          if (buf.length > MAX_SPEC_BYTES) {
            fail(mediaError(400, 'INVALID_SPEC', `spec part exceeds the ${MAX_SPEC_BYTES} byte limit`));
            stream.resume();
          }
        });
        stream.on('end', () => { specText = buf; });
        stream.on('error', () => { /* surfaced by busboy 'error' */ });
        return;
      }
      if (!/^input\d+$/.test(name)) {
        stream.resume(); // unknown part - drain and ignore
        return;
      }
      const extRaw = info && info.filename ? path.extname(info.filename) : '';
      const ext = SAFE_EXTENSION_PATTERN.test(extRaw) ? extRaw.toLowerCase() : '';
      const dest = path.join(workDir, `${name}${ext}`); // name is whitelisted input\d+ - safe on disk
      const ws = fsSync.createWriteStream(dest);
      parts[name] = dest;
      pendingWrites.push(new Promise((done) => {
        ws.on('close', done);
        ws.on('error', (err) => {
          fail(mediaError(500, 'UPLOAD_FAILED', `failed writing upload part '${name}': ${err.message}`));
          done();
        });
        stream.on('error', () => { ws.destroy(); done(); });
        stream.on('data', (d) => {
          totalBytes += d.length;
          if (totalBytes > maxTotalBytes) {
            stream.unpipe(ws);
            ws.destroy();
            fail(mediaError(413, 'INPUT_TOO_LARGE', `total input bytes exceed the ${maxTotalBytes} limit`));
            stream.resume(); // keep draining so busboy can finish the connection
          }
        });
        stream.pipe(ws);
      }));
    });

    bb.on('error', (err) => {
      fail(mediaError(400, 'INVALID_SPEC', `multipart parse error: ${err.message}`));
    });

    bb.on('close', async () => {
      await Promise.allSettled(pendingWrites);
      if (settled) return;
      settled = true;
      resolve({ specText, parts, totalBytes });
    });

    req.pipe(bb);
  });
}

// Quick `ffprobe -show_entries format=duration` on one file. strict=true turns any
// failure into a 422 (an uploaded part that ffprobe cannot read is caller data, not
// a server fault); strict=false (output files) degrades to null.
async function probeDurationSeconds(exec, ffprobePath, filePath, partName, strict) {
  try {
    const { stdout } = await exec(
      ffprobePath,
      ['-v', 'error', '-show_entries', 'format=duration', '-of', 'json', filePath],
      { timeout: PROBE_TIMEOUT_MS, maxBuffer: MEDIA_MAX_BUFFER },
    );
    const parsed = JSON.parse(stdout);
    const d = Number(parsed && parsed.format && parsed.format.duration);
    return Number.isFinite(d) && d > 0 ? d : null;
  } catch (err) {
    if (!strict) return null;
    throw mediaError(422, 'FFMPEG_FAILED', `input part '${partName}' is not readable media`, {
      stderrTail: stderrTailOf(err),
    });
  }
}

// Full `ffprobe -show_format -show_streams` on one uploaded part, transformed to the
// probe contract shape (+ SAR for the concat homogeneity check). Any failure is caller
// data -> 422, exactly like the quick duration probe.
async function probeFullInfo(exec, ffprobePath, filePath, partName) {
  let stdout;
  try {
    ({ stdout } = await exec(ffprobePath, buildProbeArgs(filePath), {
      timeout: PROBE_TIMEOUT_MS,
      maxBuffer: MEDIA_MAX_BUFFER,
    }));
  } catch (err) {
    throw mediaError(422, 'FFMPEG_FAILED', `input part '${partName}' is not readable media`, {
      stderrTail: stderrTailOf(err),
    });
  }
  let raw;
  try {
    raw = JSON.parse(stdout);
  } catch (_) {
    throw mediaError(422, 'FFMPEG_FAILED', `input part '${partName}' is not readable media`);
  }
  return { info: transformProbeJson(raw), sar: extractVideoSar(raw) };
}

// One ffmpeg run under the per-op budget with the shared error mapping: a timeout kill
// is OUR budget firing (504 MEDIA_TIMEOUT), anything else is 422 FFMPEG_FAILED + tail.
async function execFfmpegOrThrow(exec, ffmpegPath, args, timeoutMs, cwd) {
  try {
    const opts = { timeout: timeoutMs, maxBuffer: MEDIA_MAX_BUFFER };
    // Only the subtitles op passes a cwd, so it can name its .ass file RELATIVELY: the
    // ass filter parses ':' as its own option separator, and an absolute path that ever
    // contains one truncates the filename with no error at all.
    if (cwd) opts.cwd = cwd;
    await exec(ffmpegPath, args, opts);
  } catch (err) {
    if (isTimeoutKill(err)) {
      throw mediaError(504, 'MEDIA_TIMEOUT', `ffmpeg exceeded the ${timeoutMs}ms budget for this operation`);
    }
    throw mediaError(422, 'FFMPEG_FAILED', 'ffmpeg failed', { stderrTail: stderrTailOf(err) });
  }
}

/**
 * concat: static 422 preflight first (no child process spawned on a doomed spec), then a
 * full probe of every clip (homogeneity check, xfade offsets, duration math), then either
 * the fast copy path (demuxer list + -c copy, small fixed budget) or the re-encode path
 * (filter_complex, budget scaled with the effective OUTPUT duration).
 */
async function runConcat(specValue, partPaths, workDir, { exec, ffmpegPath, ffprobePath }) {
  validateConcatStatic(specValue);
  const probes = {};
  const parts = {};
  for (const inp of specValue.inputs) {
    probes[inp.name] = await probeFullInfo(exec, ffprobePath, partPaths[inp.name], inp.name);
    parts[inp.name] = { path: partPaths[inp.name], durationSeconds: probes[inp.name].info.duration_seconds };
  }
  const plan = planConcat(specValue, probes);
  const outputPath = path.join(workDir, 'out.mp4');
  let args;
  let timeoutMs;
  if (plan.copy) {
    const listPath = path.join(workDir, 'concat.txt');
    const ordered = specValue.options.items.map((it) => partPaths[it.sourcePart]);
    await fsp.writeFile(listPath, buildConcatListText(ordered), 'utf8');
    args = buildConcatCopyArgs(listPath, outputPath);
    timeoutMs = MEDIA_TIMEOUT_MIN_MS; // near-instant remux - the small fixed budget
  } else {
    args = buildConcatFilterArgs(specValue, probes, plan, { parts, outputPath });
    timeoutMs = computeMediaTimeoutMs(plan.outputDurationSeconds);
  }
  await execFfmpegOrThrow(exec, ffmpegPath, args, timeoutMs);
  const durationSeconds = await probeDurationSeconds(exec, ffprobePath, outputPath, 'output', false);
  return {
    kind: 'file',
    path: outputPath,
    mime: MEDIA_MIME_TYPES.mp4,
    durationSeconds: durationSeconds !== null ? durationSeconds : 0,
  };
}

/**
 * frame: quick duration probe -> resolve the actual timestamp (default middle, clamp at
 * or past the end) -> one still under the small fixed budget. duration_seconds is NOT
 * meaningful for a still, so it stays null (the route omits the duration header);
 * timestampSeconds feeds the X-Media-Timestamp-Seconds response header.
 */
async function runFrame(specValue, partPaths, workDir, { exec, ffmpegPath, ffprobePath }) {
  const inp = specValue.inputs[0];
  const inputPath = partPaths[inp.name];
  const durationSeconds = await probeDurationSeconds(exec, ffprobePath, inputPath, inp.name, true);
  const timestampSeconds = resolveFrameTimestamp(specValue.options.atSeconds, durationSeconds);
  const ext = mediaOutputExtension(specValue);
  const outputPath = path.join(workDir, `out.${ext}`);
  const args = buildFrameArgs(specValue, inputPath, timestampSeconds, outputPath);
  await execFfmpegOrThrow(exec, ffmpegPath, args, MEDIA_TIMEOUT_MIN_MS);
  return {
    kind: 'file',
    path: outputPath,
    mime: MEDIA_MIME_TYPES[ext],
    durationSeconds: null,
    timestampSeconds,
  };
}

/**
 * overlay: full-probe both inputs (the video's width drives the image scale, its duration
 * the enable-window default and the budget), 422 when the image part is not a still image,
 * then burn the image in (re-encode video, stream-copy audio when present).
 */
async function runOverlay(specValue, partPaths, workDir, { exec, ffmpegPath, ffprobePath }) {
  const videoIn = specValue.inputs.find((i) => i.role === 'video');
  const imageIn = specValue.inputs.find((i) => i.role === 'image');
  const videoProbe = await probeFullInfo(exec, ffprobePath, partPaths[videoIn.name], videoIn.name);
  const imageProbe = await probeFullInfo(exec, ffprobePath, partPaths[imageIn.name], imageIn.name);
  if (!isImageProbeFormat(imageProbe.info.format_name)) {
    throw mediaError(422, 'FFMPEG_FAILED',
      `input part '${imageIn.name}' is not an image (png or jpeg expected, got '${imageProbe.info.format_name || 'unknown'}')`);
  }
  if (!videoProbe.info.has_video) {
    throw mediaError(422, 'FFMPEG_FAILED', `input part '${videoIn.name}' has no video stream`);
  }
  const outputPath = path.join(workDir, 'out.mp4');
  const parts = {
    [videoIn.name]: { path: partPaths[videoIn.name] },
    [imageIn.name]: { path: partPaths[imageIn.name] },
  };
  const args = buildOverlayArgs(specValue, videoProbe.info, { parts, outputPath });
  const timeoutMs = computeMediaTimeoutMs(videoProbe.info.duration_seconds);
  await execFfmpegOrThrow(exec, ffmpegPath, args, timeoutMs);
  const durationSeconds = await probeDurationSeconds(exec, ffprobePath, outputPath, 'output', false);
  return {
    kind: 'file',
    path: outputPath,
    mime: MEDIA_MIME_TYPES.mp4,
    durationSeconds: durationSeconds !== null ? durationSeconds : 0,
  };
}

/**
 * Ask fontconfig which family a request actually resolves to. libass NEVER errors on a
 * missing family, it silently substitutes one, so a caption burnt in the wrong face
 * would only ever be caught by eye. Returns the matched family, or null when fc-match
 * itself is unavailable - a missing DIAGNOSTIC tool must not fail an otherwise valid
 * render, so the caller treats null as "cannot verify" and proceeds.
 */
async function matchFontFamily(exec, family) {
  try {
    const { stdout } = await exec('fc-match', [`${family}:family`, 'family'], {
      timeout: PROBE_TIMEOUT_MS,
      maxBuffer: MEDIA_MAX_BUFFER,
    });
    const matched = String(stdout || '').trim().split(',')[0].trim();
    return matched.length > 0 ? matched : null;
  } catch (err) {
    // Fail OPEN: a missing diagnostic tool must not refuse an otherwise valid render.
    // But say so - the caller is about to burn a font nobody verified, which is the
    // very substitution this check exists to catch, and a silent return would leave
    // no trace of why the guard did not run.
    console.warn(`[media] font verification unavailable (fc-match failed: ${err && err.message}); `
      + `burning '${family}' unverified`);
    return null;
  }
}

/**
 * subtitles: probe the video (its dimensions drive every percentage in the style preset,
 * its duration the budget), refuse a request whose font family is not installed, write
 * the generated ASS beside the input, then burn it in (re-encode video, stream-copy
 * audio when present).
 */
async function runSubtitles(specValue, partPaths, workDir, { exec, ffmpegPath, ffprobePath }) {
  const videoIn = specValue.inputs.find((i) => i.role === 'video');
  const videoProbe = await probeFullInfo(exec, ffprobePath, partPaths[videoIn.name], videoIn.name);
  if (!videoProbe.info.has_video) {
    throw mediaError(422, 'FFMPEG_FAILED', `input part '${videoIn.name}' has no video stream`);
  }
  const width = videoProbe.info.video ? videoProbe.info.video.width : 0;
  const height = videoProbe.info.video ? videoProbe.info.video.height : 0;
  if (!(width > 0 && height > 0)) {
    throw mediaError(422, 'FFMPEG_FAILED',
      `input part '${videoIn.name}' reports no usable frame size, so the caption size cannot be computed`);
  }

  const requested = specValue.options.fontFamily;
  const matched = await matchFontFamily(exec, requested);
  if (matched !== null && matched.toLowerCase() !== requested.toLowerCase()) {
    throw mediaError(422, 'FFMPEG_FAILED',
      `font family '${requested}' is not available on the renderer as itself (fontconfig resolved it to `
      + `'${matched}', either because it is not installed or because it is aliased to a metric-compatible `
      + `substitute). Burning it would silently use a different face, so the request is refused: omit `
      + `font_family to use the default, ask for '${matched}' by name if that face is what you want, or `
      + 'have an administrator add this font to the renderer image.');
  }

  const duration = videoProbe.info.duration_seconds;
  if (typeof duration === 'number' && duration > 0) {
    const firstStart = Math.min(...specValue.options.cues.map((c) => c.startSeconds));
    if (firstStart >= duration) {
      throw mediaError(422, 'FFMPEG_FAILED',
        `every caption starts at or after the end of the video (the earliest is at ${firstStart}s, `
        + `the video is ${duration}s long), so the result would carry no visible caption at all. `
        + 'Time the cues against this video, not a longer one.');
    }
  }

  const assName = 'subtitles.ass';
  const assPath = path.join(workDir, assName);
  await fsp.writeFile(assPath, buildAssDocument(specValue.options, width, height), 'utf8');

  const outputPath = path.join(workDir, 'out.mp4');
  const parts = { [videoIn.name]: { path: partPaths[videoIn.name] } };
  const args = buildSubtitlesArgs(specValue, videoProbe.info, { parts, assName, outputPath });
  const timeoutMs = computeMediaTimeoutMs(videoProbe.info.duration_seconds);
  await execFfmpegOrThrow(exec, ffmpegPath, args, timeoutMs, workDir);
  const durationSeconds = await probeDurationSeconds(exec, ffprobePath, outputPath, 'output', false);
  return {
    kind: 'file',
    path: outputPath,
    mime: MEDIA_MIME_TYPES.mp4,
    durationSeconds: durationSeconds !== null ? durationSeconds : 0,
  };
}

const ARG_BUILDERS = {
  mux_audio: buildMuxAudioArgs,
  mix: buildMixArgs,
  extract_audio: buildExtractAudioArgs,
};

/**
 * Execute one validated media spec against local input files. `partPaths` maps part
 * name -> absolute path (already written by parseMediaMultipart); workDir hosts the
 * output file (caller owns the directory lifecycle, including cleanup). Returns
 * { kind:'json', body, durationSeconds } for probe, or
 * { kind:'file', path, mime, durationSeconds } for the producing operations.
 * Failures throw mediaError: 422 FFMPEG_FAILED (with stderr tail) / 504 MEDIA_TIMEOUT.
 */
async function runMediaOperation(specValue, partPaths, workDir, opts = {}) {
  const exec = opts.execFileAsync || defaultExecFileAsync;
  const ffmpegPath = opts.ffmpegPath || 'ffmpeg';
  const ffprobePath = opts.ffprobePath || 'ffprobe';

  // v2 operations run their own probe pass (they need full stream info / clamp math),
  // so they branch before the v1 pipeline - which stays byte-for-byte as shipped.
  const tools = { exec, ffmpegPath, ffprobePath };
  if (specValue.operation === 'concat') return runConcat(specValue, partPaths, workDir, tools);
  if (specValue.operation === 'frame') return runFrame(specValue, partPaths, workDir, tools);
  if (specValue.operation === 'overlay') return runOverlay(specValue, partPaths, workDir, tools);
  if (specValue.operation === 'subtitles') return runSubtitles(specValue, partPaths, workDir, tools);

  // Quick duration pass: feeds the timeout formula AND rejects unreadable uploads early.
  const parts = {};
  let totalSeconds = 0;
  for (const inp of specValue.inputs) {
    const filePath = partPaths[inp.name];
    const durationSeconds = await probeDurationSeconds(exec, ffprobePath, filePath, inp.name, true);
    parts[inp.name] = { path: filePath, durationSeconds };
    if (durationSeconds !== null) totalSeconds += durationSeconds;
  }
  const timeoutMs = computeMediaTimeoutMs(totalSeconds);

  if (specValue.operation === 'probe') {
    const inputPath = parts[specValue.inputs[0].name].path;
    let stdout;
    try {
      ({ stdout } = await exec(ffprobePath, buildProbeArgs(inputPath), {
        timeout: PROBE_TIMEOUT_MS,
        maxBuffer: MEDIA_MAX_BUFFER,
      }));
    } catch (err) {
      throw mediaError(422, 'FFMPEG_FAILED', 'ffprobe failed on the input', { stderrTail: stderrTailOf(err) });
    }
    const body = transformProbeJson(JSON.parse(stdout));
    return { kind: 'json', body, durationSeconds: body.duration_seconds };
  }

  const ext = mediaOutputExtension(specValue);
  const outputPath = path.join(workDir, `out.${ext}`);
  const args = ARG_BUILDERS[specValue.operation](specValue, { parts, outputPath });
  try {
    await exec(ffmpegPath, args, { timeout: timeoutMs, maxBuffer: MEDIA_MAX_BUFFER });
  } catch (err) {
    if (isTimeoutKill(err)) {
      throw mediaError(504, 'MEDIA_TIMEOUT', `ffmpeg exceeded the ${timeoutMs}ms budget for this operation`);
    }
    throw mediaError(422, 'FFMPEG_FAILED', 'ffmpeg failed', { stderrTail: stderrTailOf(err) });
  }
  const durationSeconds = await probeDurationSeconds(exec, ffprobePath, outputPath, 'output', false);
  return {
    kind: 'file',
    path: outputPath,
    mime: MEDIA_MIME_TYPES[ext],
    durationSeconds: durationSeconds !== null ? durationSeconds : 0,
  };
}

module.exports = {
  parseMediaMultipart,
  runMediaOperation,
  probeDurationSeconds,
  probeFullInfo,
  PROBE_TIMEOUT_MS,
  MEDIA_MAX_BUFFER,
};
