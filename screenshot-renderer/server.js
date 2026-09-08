// Headless Chromium sidecar for the orchestrator. Stateless HTML renderer with four jobs:
//   POST /internal/render/screenshot  -> PNG / JPEG bytes (full page, viewport, or element)
//   POST /internal/render/pdf         -> PDF bytes (print CSS, page format, margins, header/footer)
//   POST /internal/render/video       -> MP4 / WEBM bytes (records the page's animation)
//   POST /internal/media              -> ffmpeg ops for the core:media node (probe /
//                                        mux_audio / mix / extract_audio / concat / frame /
//                                        overlay / subtitles) - browser-free, guarded by
//                                        its own semaphore, never a pool slot
//
// The orchestrator (InterfaceScreenshotServiceImpl / interface node) resolves templates,
// inlines CSS/JS and injects __RESOLVED_DATA__ BEFORE the POST. This service knows nothing
// about interface templates, workflow plans or tenants - it only turns HTML into pixels/PDF.
//
// The concurrency guard, fresh-context-per-request isolation, browser recycling and error->status
// mapping live in lib.js (RenderPool + classifyRenderError) so they are unit-testable without
// launching Chromium. This file is the Express wiring + the real Playwright browser launch.
//
// Failure modes: 400 (bad input) / 429 (at capacity) / 502 (render error) / 504 (timeout).

const express = require('express');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { chromium } = require('playwright');
const {
  validateScreenshotRequest,
  validatePdfRequest,
  validateVideoRequest,
  drivePageForVideo,
  drivePageForSmoothVideo,
  buildFfmpegArgs,
  buildImagePipeArgs,
  buildAudioMuxArgs,
  synthesizeClipAudio,
  readPageAudioTimeline,
  createFrameSink,
  classifyRenderError,
  RenderPool,
  MAX_VIDEO_DURATION_MS,
  buildRenderOutcomeHeaders,
  VIRTUAL_TIME_INIT_SCRIPT,
  SMOOTH_JPEG_QUALITY,
  DEFAULT_SMOOTH_WALL_TIMEOUT_MS,
  Semaphore,
  validateMediaSpec,
  mediaError,
  MAX_MEDIA_INPUT_BYTES,
  MEDIA_HEADER_DURATION,
  MEDIA_HEADER_OPERATION,
  MEDIA_HEADER_TIMESTAMP,
} = require('./lib');
const { parseMediaMultipart, runMediaOperation } = require('./media');

const { spawn } = require('node:child_process');

const execFileAsync = promisify(execFile);

const app = express();

const PORT = process.env.PORT ? Number(process.env.PORT) : 8094;
const BODY_LIMIT = process.env.BODY_LIMIT || '32mb';
const MAX_CONCURRENCY = Number(process.env.MAX_CONCURRENCY) > 0 ? Number(process.env.MAX_CONCURRENCY) : 4;
const MAX_QUEUE = Number.isFinite(Number(process.env.MAX_QUEUE)) && Number(process.env.MAX_QUEUE) >= 0
  ? Number(process.env.MAX_QUEUE)
  : 16;
const RECYCLE_AFTER_REQUESTS = Number(process.env.RECYCLE_AFTER_REQUESTS) > 0
  ? Number(process.env.RECYCLE_AFTER_REQUESTS)
  : 500;
// Recording ceiling: env can LOWER it below the hard lib.js max, never raise it.
const VIDEO_MAX_DURATION_MS = Number(process.env.VIDEO_MAX_DURATION_MS) > 0
  ? Math.min(Number(process.env.VIDEO_MAX_DURATION_MS), MAX_VIDEO_DURATION_MS)
  : MAX_VIDEO_DURATION_MS;
const FFMPEG_PATH = process.env.FFMPEG_PATH || 'ffmpeg';
const FFPROBE_PATH = process.env.FFPROBE_PATH || 'ffprobe';
const FFMPEG_TIMEOUT_MS = Number(process.env.FFMPEG_TIMEOUT_MS) > 0
  ? Number(process.env.FFMPEG_TIMEOUT_MS)
  : 60000;
// /internal/media concurrency - ffmpeg-only work never takes a browser-pool slot, so it
// gets its own (small) guard: audio transcodes are CPU-bound, 2 is plenty per pod.
const MEDIA_MAX_CONCURRENT = Number(process.env.MEDIA_MAX_CONCURRENT) > 0
  ? Number(process.env.MEDIA_MAX_CONCURRENT)
  : 2;
const MEDIA_MAX_QUEUE = Number.isFinite(Number(process.env.MEDIA_MAX_QUEUE)) && Number(process.env.MEDIA_MAX_QUEUE) >= 0
  ? Number(process.env.MEDIA_MAX_QUEUE)
  : 8;
// Wall-clock budget for a whole smooth (frame-by-frame) render; past it the clip is
// finalised truncated (best-effort) instead of erroring.
const SMOOTH_WALL_TIMEOUT_MS = Number(process.env.SMOOTH_WALL_TIMEOUT_MS) > 0
  ? Number(process.env.SMOOTH_WALL_TIMEOUT_MS)
  : DEFAULT_SMOOTH_WALL_TIMEOUT_MS;

app.use(express.json({ limit: BODY_LIMIT }));

const pool = new RenderPool({
  launchBrowser: () => chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] }),
  maxConcurrent: MAX_CONCURRENCY,
  maxQueue: MAX_QUEUE,
  recycleAfter: RECYCLE_AFTER_REQUESTS,
});

// ffmpeg-only guard for /internal/media - deliberately NOT the RenderPool: a mux/mix
// needs no browser page, so it must never starve a screenshot of its Chromium slot.
const mediaSem = new Semaphore(MEDIA_MAX_CONCURRENT, MEDIA_MAX_QUEUE);

function sendRenderError(res, err, kind) {
  const { status, message } = classifyRenderError(err);
  if (status !== 429) console.error(`[render] ${kind} failed:`, message);
  return res.status(status).json({ error: message });
}

// /internal/media failures answer {error, code} (+ stderr_tail for FFMPEG_FAILED) at the
// status carried by mediaError; anything without one is an unexpected 500.
function sendMediaError(res, err) {
  const status = (err && Number(err.status)) ? Number(err.status) : 500;
  const body = {
    error: (err && err.message) ? err.message : String(err),
    code: (err && err.code) ? err.code : 'INTERNAL',
  };
  if (err && err.stderrTail) body.stderr_tail = err.stderrTail;
  if (status >= 500) console.error('[media] failed:', body.error);
  return res.status(status).json(body);
}

// /internal/health returns 200 as soon as the Node server binds (does NOT require a warm
// browser), so k8s readiness is fast and a slow Chromium cold start never blocks a rollout.
// `media: true` advertises the /internal/media capability to the orchestrator.
app.get('/internal/health', (_req, res) => {
  res.json({ ...pool.health(), media: true });
});

app.post('/internal/render/screenshot', async (req, res) => {
  const v = validateScreenshotRequest(req.body);
  if (!v.ok) return res.status(400).json({ error: v.error });
  const o = v.value;

  try {
    const bytes = await pool.withContext(o, async (page) => {
      await page.setContent(o.html, { waitUntil: o.waitUntil, timeout: o.timeoutMs });

      const shotOpts = { type: o.type, timeout: o.timeoutMs };
      if (o.type !== 'png' && o.quality !== undefined) shotOpts.quality = o.quality;
      if (o.omitBackground) shotOpts.omitBackground = true;

      if (o.selector) {
        const el = page.locator(o.selector).first();
        await el.waitFor({ state: 'visible', timeout: o.timeoutMs });
        return el.screenshot(shotOpts);
      }
      // Capture the FULL scrollable page by default so tall interfaces (dashboards, long
      // result grids) are never cropped to the viewport. Callers opt out with fullPage:false.
      shotOpts.fullPage = o.fullPage;
      return page.screenshot(shotOpts);
    });
    res.set('Content-Type', o.mime);
    return res.status(200).send(bytes);
  } catch (err) {
    return sendRenderError(res, err, 'screenshot');
  }
});

app.post('/internal/render/pdf', async (req, res) => {
  const v = validatePdfRequest(req.body);
  if (!v.ok) return res.status(400).json({ error: v.error });
  const o = v.value;

  try {
    const bytes = await pool.withContext({ viewport: { width: 1280, height: 800 } }, async (page) => {
      // Emulate print media so @media print / @page rules apply, then wait for web fonts so
      // text is not rasterised in a fallback face before the custom font loads.
      await page.emulateMedia({ media: 'print' });
      await page.setContent(o.html, { waitUntil: o.waitUntil, timeout: o.timeoutMs });
      try { await page.evaluate(() => (document.fonts ? document.fonts.ready : null)); } catch (_) { /* fonts API absent */ }

      const pdfOpts = {
        format: o.format,
        landscape: o.landscape,
        printBackground: o.printBackground,
        scale: o.scale,
        preferCSSPageSize: o.preferCSSPageSize,
        displayHeaderFooter: o.displayHeaderFooter,
      };
      if (o.margin) pdfOpts.margin = o.margin;
      if (o.pageRanges) pdfOpts.pageRanges = o.pageRanges;
      if (o.headerTemplate !== undefined) pdfOpts.headerTemplate = o.headerTemplate;
      if (o.footerTemplate !== undefined) pdfOpts.footerTemplate = o.footerTemplate;

      return page.pdf(pdfOpts);
    });
    res.set('Content-Type', 'application/pdf');
    return res.status(200).send(bytes);
  } catch (err) {
    return sendRenderError(res, err, 'pdf');
  }
});

app.post('/internal/render/video', async (req, res) => {
  const v = validateVideoRequest(req.body, { maxDurationMs: VIDEO_MAX_DURATION_MS });
  if (!v.ok) return res.status(400).json({ error: v.error });
  const o = v.value;

  // A video render holds its concurrency slot for the whole recording (seconds, not
  // milliseconds) - that is by design: the guard is exactly what protects Chromium from
  // ten parallel 60s recordings. The orchestrator's own semaphore caps its side too.
  let workDir;
  try {
    workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-video-'));
    let bytes;
    if (o.mode === 'smooth') {
      const rendered = await renderSmoothVideo(o, workDir);
      bytes = rendered.bytes;
      // Truncation is a SILENT outcome otherwise: the clip is a valid, shorter mp4 and the
      // caller sees a plain 200. Surface it so the orchestrator can tell "the page ended the
      // clip" apart from "we ran out of wall-clock budget".
      res.set(buildRenderOutcomeHeaders(rendered));
    } else {
      let audioEvents = [];
      const webmPath = await pool.withVideoContext(
        { viewport: o.viewport, videoDir: workDir },
        async (page) => {
          await drivePageForVideo(page, o);
          // Read before withVideoContext closes the context to finalise the recording.
          if (o.audio) audioEvents = await readPageAudioTimeline(page, o.audioFlag);
        },
      );
      if (!webmPath) throw new Error('recording produced no video file');

      let outPath = webmPath;
      if (o.format === 'mp4') {
        outPath = path.join(workDir, 'out.mp4');
        await execFileAsync(
          FFMPEG_PATH,
          buildFfmpegArgs(webmPath, outPath, { fps: o.fps }),
          { timeout: FFMPEG_TIMEOUT_MS, maxBuffer: 8 * 1024 * 1024 },
        );
        outPath = await muxPageAudio(outPath, audioEvents, workDir, o.maxDurationMs / 1000);
      }
      bytes = await fs.readFile(outPath);
    }
    res.set('Content-Type', o.mime);
    return res.status(200).send(bytes);
  } catch (err) {
    return sendRenderError(res, err, 'video');
  } finally {
    if (workDir) fs.rm(workDir, { recursive: true, force: true }).catch(() => {});
  }
});

// ffmpeg audio operations for the core:media node. multipart/form-data in: one `spec`
// JSON part + binary parts input0..inputN. Out: JSON for probe, raw media bytes for the
// producing ops (Content-Type per output format, X-Media-Duration-Seconds +
// X-Media-Operation headers). 400 spec violation / 413 over the input cap / 422 ffmpeg
// failure (stderr_tail <= 2KB) / 429 at capacity / 504 budget-timeout kill.
app.post('/internal/media', async (req, res) => {
  // Cheap pre-check: a declared Content-Length over the cap never starts streaming.
  const declaredLength = Number(req.headers['content-length']);
  if (Number.isFinite(declaredLength) && declaredLength > MAX_MEDIA_INPUT_BYTES) {
    return res.status(413).json({
      error: `total input bytes exceed the ${MAX_MEDIA_INPUT_BYTES} limit`,
      code: 'INPUT_TOO_LARGE',
    });
  }
  try {
    await mediaSem.acquire(); // rejects {code:'BUSY'} when saturated - never reaches release()
  } catch (_) {
    return res.status(429).json({ error: 'media pipeline at capacity, retry shortly', code: 'BUSY' });
  }
  let workDir;
  try {
    workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-'));
    const { specText, parts } = await parseMediaMultipart(req, workDir, {
      maxTotalBytes: MAX_MEDIA_INPUT_BYTES,
    });
    if (!specText) {
      throw mediaError(400, 'INVALID_SPEC', "multipart part 'spec' (application/json) is required");
    }
    let spec;
    try {
      spec = JSON.parse(specText);
    } catch (_) {
      throw mediaError(400, 'INVALID_SPEC', "part 'spec' is not valid JSON");
    }
    const v = validateMediaSpec(spec);
    if (!v.ok) throw mediaError(400, v.code, v.error);
    const missing = v.value.inputs.filter((inp) => !parts[inp.name]).map((inp) => inp.name);
    if (missing.length > 0) {
      throw mediaError(400, 'MISSING_INPUT', `spec references input parts that were not uploaded: ${missing.join(', ')}`);
    }
    const out = await runMediaOperation(v.value, parts, workDir, {
      ffmpegPath: FFMPEG_PATH,
      ffprobePath: FFPROBE_PATH,
    });
    res.set(MEDIA_HEADER_OPERATION, v.value.operation);
    // frame returns durationSeconds:null (a still has no duration) - its header is
    // omitted and X-Media-Timestamp-Seconds carries the actual seek time instead.
    // Every OTHER operation still always sends the duration header: probe coerces an
    // unreadable duration to 0 in transformProbeJson and the v1 file ops fall back to
    // 0 in runMediaOperation, so durationSeconds is a NUMBER for all of them and this
    // branch changes nothing for v1 consumers.
    if (out.durationSeconds !== null && out.durationSeconds !== undefined) {
      res.set(MEDIA_HEADER_DURATION, String(Math.round(out.durationSeconds * 1000) / 1000));
    }
    if (out.timestampSeconds !== null && out.timestampSeconds !== undefined) {
      res.set(MEDIA_HEADER_TIMESTAMP, String(Math.round(out.timestampSeconds * 1000) / 1000));
    }
    if (out.kind === 'json') return res.status(200).json(out.body);
    const bytes = await fs.readFile(out.path);
    res.set('Content-Type', out.mime);
    return res.status(200).send(bytes);
  } catch (err) {
    return sendMediaError(res, err);
  } finally {
    mediaSem.release();
    if (workDir) fs.rm(workDir, { recursive: true, force: true }).catch(() => {});
  }
});

/**
 * Smooth (offline) render: a fresh context gets the virtual-clock init script, the page is
 * stepped one frame at a time, each frame is screenshot as JPEG and streamed straight into
 * ffmpeg (image2pipe -> H.264) via a crash-proof sink (see lib.js createFrameSink: the exit
 * promise always resolves, so no failure path can raise an unhandled rejection). ffmpeg is
 * spawned INSIDE the pool slot, so queued requests do not each hold an idle child process.
 */
async function renderSmoothVideo(o, workDir) {
  const outPath = path.join(workDir, 'out.mp4');
  let outcome = { frames: 0, truncated: false };
  let audioEvents = [];
  await pool.withContext(
    { viewport: o.viewport, initScript: VIRTUAL_TIME_INIT_SCRIPT },
    async (page) => {
      const sink = createFrameSink(spawn, FFMPEG_PATH, buildImagePipeArgs(outPath, { fps: o.fps }));
      try {
        const result = await drivePageForSmoothVideo(
          page,
          { ...o, wallTimeoutMs: SMOOTH_WALL_TIMEOUT_MS },
          async () => {
            const frame = await page.screenshot({ type: 'jpeg', quality: SMOOTH_JPEG_QUALITY });
            await sink.write(frame);
          },
        );
        outcome = result;
        if (result.truncated) {
          console.warn(`[render] smooth video hit the wall-clock budget (${SMOOTH_WALL_TIMEOUT_MS}ms) - finalising truncated clip (${result.frames} frames)`);
        }
        await sink.finalize();
        // Must be read INSIDE the pool callback: the context (and the page with it) is
        // closed as soon as this returns.
        if (o.audio) audioEvents = await readPageAudioTimeline(page, o.audioFlag);
      } catch (err) {
        await sink.abort();
        throw err;
      }
    },
  );
  const finalPath = await muxPageAudio(outPath, audioEvents, workDir, outcome.frames / o.fps);
  return { bytes: await fs.readFile(finalPath), ...outcome };
}

/**
 * Lay the page's own soundtrack onto a finished clip. Best-effort by design: no events, a
 * synth that produces nothing, or an ffmpeg failure all fall back to the silent clip that
 * would have shipped anyway - a missing soundtrack must never turn a good render into a 502.
 * Returns the path to use.
 */
async function muxPageAudio(videoPath, events, workDir, durationSeconds) {
  if (!events || events.length === 0) return videoPath;
  try {
    const wav = synthesizeClipAudio(events, { durationSeconds });
    if (!wav) return videoPath;
    const wavPath = path.join(workDir, 'score.wav');
    const withAudio = path.join(workDir, 'out-av.mp4');
    await fs.writeFile(wavPath, wav);
    await execFileAsync(
      FFMPEG_PATH,
      buildAudioMuxArgs(videoPath, wavPath, withAudio),
      { timeout: FFMPEG_TIMEOUT_MS, maxBuffer: 8 * 1024 * 1024 },
    );
    console.log(`[render] muxed page audio: ${events.length} events`);
    return withAudio;
  } catch (err) {
    console.warn(`[render] page audio mux failed, shipping the silent clip: ${err && err.message}`);
    return videoPath;
  }
}

app.listen(PORT, () => {
  console.log(`[render] listening on :${PORT} (maxConcurrency=${MAX_CONCURRENCY}, recycleAfter=${RECYCLE_AFTER_REQUESTS}, videoMaxDurationMs=${VIDEO_MAX_DURATION_MS})`);
});

// Fail loud (in logs) at boot if the mp4 transcoder is absent - video requests with
// format=mp4 and every /internal/media operation would fail at run time. webm output
// does not need ffmpeg.
execFileAsync(FFMPEG_PATH, ['-version'], { timeout: 5000 })
  .then(() => console.log(`[render] ffmpeg available at '${FFMPEG_PATH}'`))
  .catch(() => console.warn(`[render] WARNING: ffmpeg not found at '${FFMPEG_PATH}' - /internal/render/video with format=mp4 and /internal/media will fail (webm still works)`));
// /internal/media also needs ffprobe (probe op + the per-input duration pass).
execFileAsync(FFPROBE_PATH, ['-version'], { timeout: 5000 })
  .then(() => console.log(`[render] ffprobe available at '${FFPROBE_PATH}'`))
  .catch(() => console.warn(`[render] WARNING: ffprobe not found at '${FFPROBE_PATH}' - /internal/media will fail`));

// Graceful shutdown - drains the warm browser so deploys don't leak Chromium processes.
async function shutdown(signal) {
  console.log(`[render] ${signal} received, shutting down`);
  await pool.shutdown();
  process.exit(0);
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
