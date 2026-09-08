package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed, validated form of the {@code generation} block declared on an
 * endpoint in {@code scripts/api-migrations/&lt;provider&gt;.json} and stored in
 * {@code catalog.api_tools.generation_spec} (V428).
 *
 * <p>This is what turns an ordinary catalog endpoint into one or more
 * <em>generation models</em>: addressable by a single model id through the
 * unified {@code generation} tool and the {@code agent:generate} node, priced
 * per model, and executed through the catalog's existing credential + billing
 * path. Adding a provider or a whole new format is therefore a JSON change plus
 * a targeted re-import, with no Java to write.
 *
 * <p><b>Why parsing lives here and not in the importer</b> - the same shape has
 * to be understood in two places: at import time (fail loudly on a malformed
 * seed, exactly like {@code outputSchema} does) and at runtime (resolve a model
 * id to a callable endpoint). One parser serves both, so the two can never
 * drift.
 *
 * <p><b>Fail-fast posture</b> - every {@code parse} failure carries the
 * offending field and the endpoint it came from. A malformed descriptor that
 * reached runtime would surface as a mysterious "unknown model", which is far
 * more expensive to diagnose than a red import.
 *
 * @param kind       modality produced: image, video, audio, voice, music,
 *                   model3d, slides, document, ... Free-form by design; adding
 *                   a format must not require touching this class.
 * @param assetPath  where the produced asset is in the TERMINAL response:
 *                   {@link #ASSET_BINARY} when the body is the asset itself,
 *                   {@link #ASSET_BASE64_PREFIX} + a path when it is base64
 *                   text in the JSON body (e.g.
 *                   {@code $base64:data[0].b64_json}), otherwise a dotted path
 *                   to its URL (e.g. {@code content.video_url}). Waiting for a long job is NOT
 *                   described here: that is the endpoint's own
 *                   {@code execution.mode='async_poll'} block, which the
 *                   catalog already executes.
 * @param paramMap   unified parameter name to the upstream binding that carries
 *                   it. Unified names are the vocabulary the agent and the
 *                   builder speak; the upstream names never leak out.
 * @param constants  upstream paths always sent with a fixed value, used to
 *                   build the request scaffolding a provider expects around the
 *                   dynamic values (see {@link ParamBinding} for why paths, not
 *                   names).
 * @param models     the models this endpoint backs, in declaration order.
 */
public record GenerationSpec(
        String kind,
        String modelParam,
        String assetPath,
        Map<String, ParamBinding> paramMap,
        Map<String, Object> constants,
        List<Model> models) {

    /**
     * {@code assetPath} sentinel meaning "the HTTP response body IS the asset".
     *
     * <p>The majority of generation endpoints answer with the bytes rather than
     * a URL (speech synthesis, sound effects, music). Those already flow
     * through the catalog's binary handling, so the descriptor only has to say
     * so instead of describing a path that does not exist.
     */
    public static final String ASSET_BINARY = "$binary";

    /**
     * {@code assetPath} prefix meaning "the asset is base64 text at this path
     * inside the JSON body", e.g. {@code $base64:data[0].b64_json}.
     *
     * <p>This is the third camp, and the one that kept the two biggest image
     * providers out of the system. OpenAI's {@code gpt-image-*} returns
     * {@code b64_json} whatever {@code response_format} asks for, and Gemini
     * answers with {@code inlineData.data}: neither hands back the bytes as the
     * body ({@link #ASSET_BINARY}) nor a URL to fetch, so neither could be
     * described at all.
     *
     * <p><b>Declared rather than sniffed.</b> The catalog already dehydrates
     * large inline base64 leaves into stored files on its way out, so a real
     * image (any of them, in practice: the threshold is 64 KB) arrives here as
     * a FileRef and this camp picks it up. Below that threshold the leaf is
     * still text, and without a declared path it would be handed back as a wall
     * of base64 for a generation the customer paid for. So the descriptor names
     * the path to cover BOTH sides of a heuristic it does not control.
     *
     * <p>The two sides are not identical, and the difference is visible: the
     * dehydrator files what it stores under the catalog's own binary category,
     * while the small-asset branch files it under the generated-asset one. The
     * caller gets a working FileRef either way. Unifying them would mean
     * re-storing bytes the catalog has already stored, which costs the customer
     * a second copy to fix a cosmetic inconsistency.
     */
    public static final String ASSET_BASE64_PREFIX = "$base64:";

    /** True when the provider returns the asset bytes directly. */
    public boolean isBinaryAsset() {
        return ASSET_BINARY.equals(assetPath);
    }

    /** True when the asset is base64 text at a declared path in the JSON body. */
    public boolean isBase64Asset() {
        return assetPath != null && assetPath.startsWith(ASSET_BASE64_PREFIX);
    }

    /** The response path carrying the base64 payload, sentinel stripped. */
    public String base64Path() {
        return isBase64Asset() ? assetPath.substring(ASSET_BASE64_PREFIX.length()) : null;
    }

    /**
     * True when the endpoint carries a model parameter. A single-model endpoint
     * (ElevenLabs sound effects, for instance) has none, and nothing must be
     * added to its request.
     */
    public boolean sendsModelParam() {
        return modelParam != null && !modelParam.isBlank();
    }

    /**
     * Where one unified parameter lands in the upstream request, and how its
     * value has to be adapted on the way.
     *
     * <p><b>Why a path and not a name.</b> Real providers do not take a flat
     * body. Seedance wants the prompt inside a multimodal array
     * ({@code content[0].text}); others nest under a config object. A dotted
     * path with {@code [n]} indices expresses all of those without a single
     * provider-specific branch in Java, which is the difference between adding
     * a provider by editing JSON and adding one by writing code.
     *
     * <p><b>Why a scale.</b> The same dimension is expressed in different units
     * by different providers: ElevenLabs takes music length in milliseconds
     * while the platform speaks seconds. A multiplier keeps the unified
     * vocabulary honest ({@code duration_seconds} always means seconds) without
     * inventing a second parameter per provider.
     *
     * @param path     dotted upstream path, e.g. {@code text},
     *                 {@code content[0].text}, {@code config.duration}
     * @param scale    multiplier applied to a numeric value before sending, or
     *                 null for none
     * @param encoding for an input asset only, the form the provider wants the
     *                 file in. Mandatory there and forbidden everywhere else:
     *                 an input asset arrives as a FileRef, which means nothing
     *                 to any provider, so a binding without an encoding would
     *                 serialise {@code {_type=file, path=...}} into the request
     *                 and fail upstream on a call already paid for.
     * @param mimePath where the asset's own media type goes, for a provider
     *                 that takes the bytes and the type in two separate fields
     *                 (Gemini's {@code inlineData}). Null when the encoding
     *                 already carries it, as a data URL does.
     */
    /**
     * @param itemConstants paths written BESIDE each file, moving with it.
     *
     *        <p>Some providers do not take a bare URL in an array: they take an OBJECT per element,
     *        and the object needs fields the file itself does not supply - Seedance's
     *        {@code content[n]} wants {@code type: "image_url"} and a {@code role} next to the URL.
     *        The endpoint's own {@code constants} cannot say that: they are written once, at a
     *        fixed path, whether or not a file was given, so an absent file would leave an element
     *        carrying a type and a role and NO url - a malformed request the provider refuses after
     *        the reservation.
     *
     *        <p>These are written only for the files actually present, and their last index shifts
     *        exactly as {@code path}'s does, so element 2's type lands beside element 2's url.
     */
    public record ParamBinding(String path, BigDecimal scale, AssetEncoding encoding, String mimePath,
                                AssetRole role, int maxItems, Map<String, Object> itemConstants) {

        public ParamBinding {
            itemConstants = itemConstants == null ? Map.of() : Map.copyOf(itemConstants);
        }

        /** A binding that only moves a value, which is every non-asset parameter. */
        public ParamBinding(String path, BigDecimal scale) {
            this(path, scale, null, null, null, 1, Map.of());
        }

        public ParamBinding(String path, BigDecimal scale, AssetEncoding encoding, String mimePath) {
            this(path, scale, encoding, mimePath, null, 1, Map.of());
        }

        public ParamBinding(String path, BigDecimal scale, AssetEncoding encoding, String mimePath,
                             AssetRole role, int maxItems) {
            this(path, scale, encoding, mimePath, role, maxItems, Map.of());
        }

        /** True when this endpoint takes more than one file in this slot. */
        public boolean acceptsSeveral() {
            return maxItems > 1;
        }

        /** Apply the scale to a numeric value; non-numeric values pass through. */
        public Object adapt(Object value) {
            if (scale == null || value == null) return value;
            BigDecimal n;
            if (value instanceof BigDecimal bd) {
                n = bd;
            } else if (value instanceof Number num) {
                n = BigDecimal.valueOf(num.doubleValue());
            } else {
                try {
                    n = new BigDecimal(String.valueOf(value).trim());
                } catch (NumberFormatException e) {
                    return value;
                }
            }
            BigDecimal scaled = n.multiply(scale);
            return scaled.stripTrailingZeros().scale() <= 0
                    ? scaled.longValueExact()
                    : scaled;
        }
    }

    /**
     * The form a provider wants an input file in.
     *
     * <p>Every generation surface hands an input asset over as a FileRef, the
     * platform's own handle on a stored file. No provider understands one, and
     * they do not agree on what they do understand, so the descriptor says
     * which shape this endpoint takes and the conversion happens once, in one
     * place.
     *
     * <p>Only shapes a shipped descriptor actually uses live here. A presigned
     * link was written and removed unused: it would have been a code path no
     * endpoint exercised and no provider had ever answered, which is exactly
     * the sort of thing that rots. Adding it back is a dozen lines the day a
     * provider needs it.
     */
    public enum AssetEncoding {
        /** {@code data:<mime>;base64,<bytes>} in a JSON field. */
        DATA_URL,
        /** Bare base64 in a JSON field, usually with {@code mimePath} alongside. */
        BASE64,
        /**
         * The FileRef itself, untouched, for a multipart endpoint whose part is
         * declared {@code source: "fileRef"}: the catalog's own multipart
         * encoder downloads the bytes, so converting here would upload them
         * twice.
         */
        FILE_REF;

        static AssetEncoding parse(String raw) {
            for (AssetEncoding e : values()) {
                if (e.name().equalsIgnoreCase(raw) || e.wire().equalsIgnoreCase(raw)) return e;
            }
            return null;
        }

        /** The lowercase spelling used in the descriptor. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What an input file MEANS to the provider that receives it.
     *
     * <p>The same {@code input_image} slot is a different thing per endpoint,
     * and the difference is not cosmetic: Runway and xAI animate FROM a still,
     * so the file becomes the first frame of a video; OpenAI and Stability
     * transform the file itself; Flux never returns the file at all and only
     * takes its style. A surface that offers "reference image" for all three
     * mis-describes two of them, and the reader only finds out after paying.
     *
     * <p>A closed set on purpose. Each value is translated once by the surfaces
     * that show it, so a provider added later either fits one of these or is a
     * deliberate decision to add another, rather than free text no locale file
     * has ever seen.
     */
    public enum AssetRole {
        /** The file is transformed and comes back changed: an edit, an upscale, image-to-image. */
        SOURCE,
        /** The file becomes the opening frame of the produced clip. */
        FIRST_FRAME,
        /** The file guides the result without appearing in it: style, subject, remix. */
        REFERENCE,
        /** Marks WHERE another file may be changed, rather than what to draw. */
        MASK;

        static AssetRole parse(String raw) {
            for (AssetRole r : values()) {
                if (r.name().equalsIgnoreCase(raw) || r.wire().equalsIgnoreCase(raw)) return r;
            }
            return null;
        }

        /** The lowercase spelling used in the descriptor and on the wire. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Unified parameters that carry a file rather than a value. */
    public static final Set<String> ASSET_PARAMS = Set.of("input_image", "input_audio", "input_video");

    /**
     * Ceiling on how many files one slot may take.
     *
     * <p>No provider is anywhere near it, and the number is the platform's
     * rather than any provider's: each file is read out of storage and inlined
     * into the request, so the count multiplies the request body.
     */
    static final int MAX_ASSET_ITEMS = 8;

    /** An indexed segment, which is what a multi-file slot walks forward from. */
    static final Pattern INDEXED_SEGMENT = Pattern.compile("\\[(\\d+)\\]");

    /** Same permissive shape as the model-category constraint: lowercase snake_case. */
    private static final Pattern KIND_SHAPE = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** A model id has to survive being typed by an agent and used as a map key. */
    private static final Pattern MODEL_ID_SHAPE = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

    /**
     * Unified parameters the platform understands. A capability outside this
     * set is rejected at import: it would be a parameter no surface could ever
     * populate, so declaring it is always a seed authoring mistake.
     *
     * <p>Extending the vocabulary is a one-line change here, which is the
     * intended way to support a new format's own dimension.
     */
    public static final Set<String> UNIFIED_PARAMS = Set.of(
            "prompt",            // the text instruction, every kind
            "negative_prompt",   // what to avoid
            "n",                 // how many assets to produce
            "seed",              // deterministic generation
            "input_image",       // a FileRef used as reference / first frame
            "input_audio",       // a FileRef used as reference voice / track
            "input_video",       // a FileRef used as reference / continuation
            "duration_seconds",  // video, music, speech length
            "aspect_ratio",      // video, image framing
            "resolution",        // video, image size
            "quality",           // provider quality tier
            "voice",             // speech synthesis voice id
            "language",          // speech synthesis language
            "style"              // provider style preset
    );

    /**
     * One addressable model.
     *
     * @param id           the public identifier an agent or a builder node
     *                     writes (e.g. {@code seedance-2.0-fast}). Unique
     *                     across the whole catalog.
     * @param upstream     the value actually sent to the provider as its model
     *                     parameter. Often an opaque vendor string, which is
     *                     precisely why it is not the public id.
     * @param label        human-readable name for the admin screens.
     * @param capabilities unified parameters this model accepts. Anything else
     *                     is refused with the accepted list, so an agent
     *                     recovers on its next turn instead of guessing.
     * @param required     subset of the capabilities the provider will reject the
     *                     call without, checked before the customer pays.
     * @param constraints  per-parameter value restrictions, used to reject a
     *                     bad call before it is paid for.
     * @param constants    upstream paths this MODEL always sends with a fixed
     *                     value, on top of the endpoint's own constants.
     *                     <p>This exists for a pricing reason rather than a
     *                     convenience one. Some providers sell one endpoint at
     *                     several rates chosen by a request parameter: OpenAI
     *                     charges roughly thirty-five times as much for a
     *                     {@code gpt-image-2} image at its highest quality as at
     *                     its lowest. A single price per model cannot describe
     *                     that, so the tier has to BE the model, pinned here and
     *                     absent from the paramMap. Left settable by the caller,
     *                     a call priced at the cheap tier could run at the
     *                     expensive one.
     *
     *                     <p>Deliberately no numbers here. An earlier draft
     *                     quoted the provider's own rates as if they were the
     *                     platform's, and the seed then shipped those figures
     *                     as the SELLING price: seventeen models went out at
     *                     exactly cost while the rest of the catalogue sat at
     *                     twice it.
     * @param price        the STARTING price shipped with the seed. The
     *                     platform owner overrides it from the admin screens;
     *                     this value only ever seeds version 1.
     */
    public record Model(
            String id,
            String upstream,
            String label,
            Set<String> capabilities,
            Set<String> required,
            Map<String, Constraint> constraints,
            Map<String, Object> constants,
            Price price) {

        /** A model that pins nothing, which is the overwhelming majority. */
        public Model(String id, String upstream, String label, Set<String> capabilities,
                     Set<String> required, Map<String, Constraint> constraints, Price price) {
            this(id, upstream, label, capabilities, required, constraints, Map.of(), price);
        }

        /** True when this parameter must be supplied for the call to be accepted. */
        public boolean requires(String unifiedParam) {
            return required.contains(unifiedParam);
        }

        /** True when this model accepts the given unified parameter. */
        public boolean accepts(String unifiedParam) {
            return capabilities.contains(unifiedParam);
        }

        /**
         * Platform unit a call on this model is MEASURED in: seconds of media,
         * assets produced, characters of text, or a bare call.
         *
         * <p>Derived from the seed's price unit because that is where the
         * platform declares what this model is SOLD BY, but it is deliberately
         * not the same value: a model whose seed price is per minute is still
         * measured in seconds. Seconds are the platform's unit for time, and
         * keeping the measurement there is what lets the published rate be
         * expressed per second or per minute without the two being multiplied
         * together.
         */
        public String platformUnit() {
            String unit = price == null ? "call" : price.unit();
            if (unit == null) return "call";
            return switch (unit) {
                case "second", "minute" -> "second";
                case "image" -> "image";
                case "character" -> "character";
                default -> "call";
            };
        }

        /**
         * Unified parameter that states how big a call on this model is, or null
         * when the model is sold flat and nothing measures it.
         *
         * <p>This is the parameter the price multiplies, so it is also the one a
         * call cannot be priced without. Speech is measured by its own prompt,
         * which every model already requires, so a per-character model always
         * has its measurement.
         */
        public String measuringParam() {
            return switch (platformUnit()) {
                case "second" -> "duration_seconds";
                case "image" -> "n";
                case "character" -> "prompt";
                default -> null;
            };
        }

        /**
         * Size to use, and to SEND, when a caller does not state one, or null
         * when this model has no defensible default.
         *
         * <p>A default is only ever taken from what the model itself declares:
         * <ul>
         *   <li>an {@code allowed} list of sizes is the set of clips this model
         *       actually produces, so the smallest of them is a real product
         *       choice and the cheapest one the caller can be charged for;</li>
         *   <li>{@code n} counts assets, and one asset is what every provider
         *       produces when asked for no particular number.</li>
         * </ul>
         *
         * <p>A bare {@code min}/{@code max} range is deliberately NOT a default:
         * the bottom of a range is a boundary, not a size anybody wants (half a
         * second of sound, ten seconds of music). A model priced that way has to
         * list its measuring parameter in {@code required} instead, so the
         * caller is asked for a length rather than handed the shortest one.
         */
        public BigDecimal defaultMeasurement() {
            String param = measuringParam();
            if (param == null || "prompt".equals(param)) return null;
            Constraint c = constraints.get(param);
            BigDecimal smallestListed = c == null ? null : c.smallestAllowedNumber();
            if (smallestListed != null) return smallestListed;
            if (!"n".equals(param)) return null;
            BigDecimal min = c == null ? null : c.min();
            return min != null && min.compareTo(BigDecimal.ONE) > 0 ? min : BigDecimal.ONE;
        }

        /**
         * True when this model can state the size of every call: it either
         * defaults the measuring parameter or refuses a call that omits it.
         *
         * <p>The seed authoring gate ({@code validate_apis.py}) is where a model
         * that can do neither is caught, because that is where the descriptor
         * can still be edited.
         */
        public boolean canAlwaysStateItsSize() {
            String param = measuringParam();
            return param == null
                    || required.contains(param)
                    || defaultMeasurement() != null;
        }
    }

    /**
     * Value restriction on one parameter.
     *
     * @param allowed discrete accepted values, or empty when unrestricted
     * @param min     inclusive lower bound, or null
     * @param max     inclusive upper bound, or null
     */
    public record Constraint(List<Object> allowed, BigDecimal min, BigDecimal max, Integer maxLength) {

        /** A constraint that only restricts the value set or its range. */
        public Constraint(List<Object> allowed, BigDecimal min, BigDecimal max) {
            this(allowed, min, max, null);
        }

        /**
         * Describe the restriction for an error message the agent can act on.
         * Returns null when the value is acceptable.
         */
        public String violation(Object value) {
            if (value == null) return null;
            if (maxLength != null && String.valueOf(value).length() > maxLength) {
                // Text limits are the one constraint a caller trips by accident
                // rather than by guessing: a prompt assembled upstream grows
                // past the provider's cap with nothing to signal it. Refusing
                // here costs nothing, where the provider refuses a call the
                // customer has already been reserved for.
                return "must be at most " + maxLength + " characters (got "
                        + String.valueOf(value).length() + ")";
            }
            if (!allowed.isEmpty()) {
                for (Object a : allowed) {
                    if (String.valueOf(a).equalsIgnoreCase(String.valueOf(value))) return null;
                }
                return "must be one of " + allowed;
            }
            if (min != null || max != null) {
                BigDecimal n;
                try {
                    n = new BigDecimal(String.valueOf(value).trim());
                } catch (NumberFormatException e) {
                    return "must be a number";
                }
                if (min != null && n.compareTo(min) < 0) return "must be >= " + min.toPlainString();
                if (max != null && n.compareTo(max) > 0) return "must be <= " + max.toPlainString();
            }
            return null;
        }

        /**
         * Smallest value of the {@code allowed} list, or null when the list is
         * empty or holds anything that is not a number.
         *
         * <p>An enumerated list of sizes is the model saying which sizes it
         * produces, so its smallest entry is a legal size by construction. A
         * list of words ({@code "16:9"}, {@code "720p"}) measures nothing and
         * yields null rather than a parse failure.
         */
        public BigDecimal smallestAllowedNumber() {
            BigDecimal smallest = null;
            for (Object a : allowed) {
                BigDecimal n;
                try {
                    n = new BigDecimal(String.valueOf(a).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (smallest == null || n.compareTo(smallest) < 0) smallest = n;
            }
            return smallest;
        }
    }

    /**
     * The starting price declared in the seed, in credits (1 credit = $0.001).
     *
     * <p>Mirrors the columns on {@code auth.pricing_version_entry} so the
     * importer can seed a version-1 price row verbatim:
     * {@code price = base + unit x quantity}, clamped to [min, max].
     *
     * @param unit    what {@code perUnit} is charged per: call, second, minute,
     *                image or character
     * @param base    fixed component
     * @param perUnit credits charged per unit
     * @param min     optional floor, null when unbounded
     * @param max     optional ceiling, null when unbounded
     */
    public record Price(String unit, BigDecimal base, BigDecimal perUnit, BigDecimal min, BigDecimal max) {

        static final Set<String> UNITS = Set.of("call", "second", "minute", "image", "character");

        /** A price that charges nothing, used when a seed omits the block. */
        public static Price free() {
            return new Price("call", BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        }
    }

    /** Models indexed by id, preserving declaration order. */
    public Map<String, Model> modelsById() {
        Map<String, Model> out = new LinkedHashMap<>();
        for (Model m : models) out.put(m.id(), m);
        return Collections.unmodifiableMap(out);
    }

    /** Look up one model by its public id. */
    public Optional<Model> model(String id) {
        if (id == null) return Optional.empty();
        for (Model m : models) {
            if (m.id().equals(id)) return Optional.of(m);
        }
        return Optional.empty();
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    /**
     * Parse and validate a {@code generation} block.
     *
     * @param node    the JSON object, may be null or a missing node
     * @param context endpoint identifier quoted in every error message
     * @return the parsed spec, or empty when no descriptor is present
     * @throws IllegalArgumentException when a descriptor IS present but malformed
     */
    public static Optional<GenerationSpec> parse(JsonNode node, String context) {
        if (node == null || node.isMissingNode() || node.isNull()) return Optional.empty();
        if (!node.isObject()) {
            throw new IllegalArgumentException(err(context, "generation must be a JSON object (got "
                    + node.getNodeType() + ")"));
        }

        String kind = text(node, "kind");
        if (kind == null) {
            throw new IllegalArgumentException(err(context, "generation.kind is required "
                    + "(the modality produced, e.g. image, video, audio)"));
        }
        kind = kind.toLowerCase(Locale.ROOT);
        if (!KIND_SHAPE.matcher(kind).matches()) {
            throw new IllegalArgumentException(err(context,
                    "generation.kind must be lowercase snake_case (got '" + kind + "')"));
        }

        String assetPath = text(node, "assetPath");
        if (assetPath == null) {
            throw new IllegalArgumentException(err(context, "generation.assetPath is required: '"
                    + ASSET_BINARY + "' when the response body is the asset itself, '"
                    + ASSET_BASE64_PREFIX + "<path>' when it is base64 text in the JSON body "
                    + "(e.g. " + ASSET_BASE64_PREFIX + "data[0].b64_json), or a dotted path to the "
                    + "asset URL in the terminal response (e.g. content.video_url)"));
        }
        if (assetPath.startsWith(ASSET_BASE64_PREFIX)) {
            String base64Path = assetPath.substring(ASSET_BASE64_PREFIX.length());
            if (base64Path.isBlank()) {
                throw new IllegalArgumentException(err(context, "generation.assetPath '"
                        + ASSET_BASE64_PREFIX + "' needs the path the base64 payload sits at, "
                        + "e.g. " + ASSET_BASE64_PREFIX + "data[0].b64_json"));
            }
            validateReadPath(base64Path, context, "generation.assetPath");
        } else if (!ASSET_BINARY.equals(assetPath)) {
            // A URL path is read by the same walker, so a typo there fails the
            // same way and just as late: after the call has been charged.
            validateReadPath(assetPath, context, "generation.assetPath");
        }

        // `pollTool` was a second way to express "this job finishes later",
        // duplicating the endpoint's own execution.mode=async_poll block that
        // the catalog already executes. Two mechanisms for one thing is how the
        // two drift apart, so the descriptor now refuses it outright and points
        // at the one that actually runs.
        if (!node.path("pollTool").isMissingNode()) {
            throw new IllegalArgumentException(err(context, "generation.pollTool is not supported. "
                    + "Waiting for a long job is the endpoint's own concern: declare "
                    + "execution.mode='async_poll' with its execution.async block, which the catalog "
                    + "already executes, and point generation.assetPath at the asset URL in the "
                    + "TERMINAL response."));
        }

        String modelParam = text(node, "modelParam");

        Map<String, ParamBinding> paramMap = new LinkedHashMap<>();
        JsonNode pm = node.path("paramMap");
        if (pm.isObject()) {
            pm.fields().forEachRemaining(e -> {
                String unified = e.getKey();
                if (!UNIFIED_PARAMS.contains(unified)) {
                    throw new IllegalArgumentException(err(context,
                            "generation.paramMap key '" + unified + "' is not a unified parameter. "
                                    + "Accepted: " + sorted(UNIFIED_PARAMS)));
                }
                paramMap.put(unified, parseBinding(e.getValue(), context, unified));
            });
        }

        if (modelParam != null) {
            validatePath(modelParam, context, "generation.modelParam");
        }

        Map<String, Object> constants = new LinkedHashMap<>();
        JsonNode cn = node.path("constants");
        if (cn.isObject()) {
            cn.fields().forEachRemaining(e -> {
                if (e.getValue().isContainerNode()) {
                    throw new IllegalArgumentException(err(context,
                            "generation.constants['" + e.getKey() + "'] must be a scalar. "
                                    + "Build nested shapes with indexed paths (e.g. content[0].type) instead."));
                }
                // A constant is written by the same path walker as a mapped
                // parameter, so it needs the same guard: an unbounded index here
                // allocates every slot below it just as surely.
                validatePath(e.getKey(), context, "generation.constants['" + e.getKey() + "']");
                constants.put(e.getKey(), literal(e.getValue()));
            });
        }

        JsonNode modelsNode = node.path("models");
        if (!modelsNode.isArray() || modelsNode.isEmpty()) {
            throw new IllegalArgumentException(err(context,
                    "generation.models must be a non-empty array (each entry declares one addressable model)"));
        }

        List<Model> models = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (JsonNode m : modelsNode) {
            Model parsed = parseModel(m, context, paramMap.keySet(), modelParam != null);
            if (!seenIds.add(parsed.id())) {
                throw new IllegalArgumentException(err(context,
                        "duplicate generation model id '" + parsed.id() + "'"));
            }
            models.add(parsed);
        }

        // A model's pinned value must not be reachable by the caller on that
        // same model, must not displace the model selector, and must not fight
        // the endpoint's own scaffolding. Checked per model rather than
        // endpoint-wide on purpose: only one model runs per call, so another
        // model's capability is no hazard, and refusing it would forbid the
        // ordinary shape where most models expose a parameter and one pins it.
        for (Model model : models) {
            // Two pins of the same model landing on the same place: one silently
            // replaces the other, or lands a scalar where the other needs an
            // object. Same rule the endpoint's own constants already live under.
            List<String> pins = List.copyOf(model.constants().keySet());
            for (int i = 0; i < pins.size(); i++) {
                for (int j = i + 1; j < pins.size(); j++) {
                    if (pathsCollide(pins.get(i), pins.get(j))) {
                        throw new IllegalArgumentException(err(context, "generation.models['"
                                + model.id() + "'].constants writes to '" + pins.get(i) + "' and '"
                                + pins.get(j) + "', which collide: one would overwrite the other, or "
                                + "land a value where the other needs an object or an array."));
                    }
                }
            }
            for (String pinned : model.constants().keySet()) {
                String what = "generation.models['" + model.id() + "'].constants['" + pinned + "']";
                if (modelParam != null && pathsCollide(modelParam, pinned)) {
                    throw new IllegalArgumentException(err(context, what + " writes to the modelParam '"
                            + modelParam + "', which selects the model being priced."));
                }
                for (String constant : constants.keySet()) {
                    if (pathsCollide(constant, pinned)) {
                        throw new IllegalArgumentException(err(context, what + " collides with '"
                                + constant + "', a path generation.constants also sets."));
                    }
                }
                for (String capability : model.capabilities()) {
                    ParamBinding binding = paramMap.get(capability);
                    if (binding == null) continue;
                    // BOTH writes of the binding, not just the value one. An
                    // input asset also writes its media type, and the input
                    // resolver writes it at dispatch, after the builder wrote
                    // the pin: a pin sitting on that path is overwritten just as
                    // surely, and reading only `path` here missed it.
                    for (String written : binding.mimePath() == null
                            ? List.of(binding.path())
                            : List.of(binding.path(), binding.mimePath())) {
                        if (pathsCollide(written, pinned)) {
                            throw new IllegalArgumentException(err(context, what + " collides with '"
                                    + written + "', where this model's own '" + capability
                                    + "' lands. The caller's value is written last, so it would overwrite "
                                    + "the pinned one and run a call the price was not computed for. Drop "
                                    + "'" + capability + "' from this model's capabilities, or stop pinning it."));
                        }
                    }
                }
            }
        }

        if (modelParam == null && models.size() > 1) {
            throw new IllegalArgumentException(err(context, "generation declares " + models.size()
                    + " models but no modelParam, so the endpoint has no way to tell them apart. "
                    + "Set modelParam to the upstream parameter that selects the model."));
        }

        // A caller-supplied value must never be able to land where the model
        // selector or a constant goes. Mapping a parameter onto modelParam
        // would let a caller run the expensive model while the cheap one is
        // priced and billed; mapping it onto a constant would let them undo
        // the request scaffolding the provider requires.
        //
        // Compared SEGMENT-WISE, in both directions, because a shorter path
        // that merely STARTS the same is not an innocent neighbour: constants
        // are written first, so a paramMap on 'content' overwrites the array
        // that constants['content[0].type'] just built, and the reverse order
        // makes the writer walk into a scalar. Either way the provider gets a
        // request that cannot work, with no error at import and none at
        // runtime. Exact-equality missed both.
        for (Map.Entry<String, ParamBinding> e : paramMap.entrySet()) {
            String path = e.getValue().path();
            if (modelParam != null && pathsCollide(modelParam, path)) {
                String what = modelParam.equals(path)
                        ? "which is the modelParam"
                        : "which collides with the modelParam '" + modelParam + "'";
                throw new IllegalArgumentException(err(context, "generation.paramMap['" + e.getKey()
                        + "'] writes to '" + path + "', " + what + ". A caller could then "
                        + "select a different model than the one being priced."));
            }
            for (String constant : constants.keySet()) {
                if (pathsCollide(constant, path)) {
                    throw new IllegalArgumentException(err(context, "generation.paramMap['" + e.getKey()
                            + "'] writes to '" + path + "', which collides with '" + constant
                            + "', a path generation.constants also sets. A caller would be able to "
                            + "overwrite it or destroy the shape it builds."));
                }
            }
        }

        // The same collision between two paths the DESCRIPTOR itself writes is
        // not a security hole but a request that is simply wrong: one write
        // silently replaces the other, or lands a scalar where the next write
        // needs a list. Refusing it here is what turns an unexplained provider
        // rejection into a red import naming both paths.
        List<Map.Entry<String, String>> declared = new ArrayList<>();
        for (String constant : constants.keySet()) {
            declared.add(Map.entry("generation.constants['" + constant + "']", constant));
        }
        if (modelParam != null) {
            declared.add(Map.entry("generation.modelParam", modelParam));
        }
        for (Map.Entry<String, ParamBinding> e : paramMap.entrySet()) {
            declared.add(Map.entry("generation.paramMap['" + e.getKey() + "']", e.getValue().path()));
            if (e.getValue().mimePath() != null) {
                // The media type is a second write from the same binding, so it
                // can collide exactly like the first one.
                declared.add(Map.entry("generation.paramMap['" + e.getKey() + "'].mimePath",
                        e.getValue().mimePath()));
            }
        }
        for (int i = 0; i < declared.size(); i++) {
            for (int j = i + 1; j < declared.size(); j++) {
                if (pathsCollide(declared.get(i).getValue(), declared.get(j).getValue())) {
                    throw new IllegalArgumentException(err(context, declared.get(i).getKey()
                            + " writes to '" + declared.get(i).getValue() + "' and "
                            + declared.get(j).getKey() + " writes to '" + declared.get(j).getValue()
                            + "', which collide: one would overwrite the other, or land a value where "
                            + "the other needs an object or an array."));
                }
            }
        }

        return Optional.of(new GenerationSpec(kind, modelParam, assetPath,
                Collections.unmodifiableMap(paramMap),
                Collections.unmodifiableMap(constants),
                Collections.unmodifiableList(models)));
    }

    /**
     * A binding is written either as a bare upstream path ({@code "text"}) or,
     * when the value needs adapting, as an object ({@code {path, scale}}). The
     * short form covers the overwhelming majority of parameters and keeps the
     * seeds readable.
     */
    /**
     * Largest array index a descriptor may address.
     *
     * <p>Writing a path materialises every slot up to the index, so an
     * unbounded one is an out-of-memory kill for the whole service triggered by
     * a one-character typo in a seed. No real provider body needs a deep array,
     * so the cap costs nothing and removes the failure mode entirely.
     */
    static final int MAX_PATH_INDEX = 32;

    /**
     * Nine digits is wide enough that an absurd index is reported as "above the
     * maximum" (which says what to fix) rather than as a malformed path (which
     * does not), while still fitting an int without overflowing the parse.
     */
    private static final Pattern PATH_SEGMENT = Pattern.compile("^([^\\[\\]]+)(?:\\[(\\d{1,9})\\])?$");

    /**
     * Reject a path that cannot be written safely. Done at PARSE time, so a
     * malformed or hostile descriptor never reaches the request builder.
     */
    private static void validatePath(String path, String context, String what) {
        for (String segment : path.split("\\.")) {
            Matcher m = PATH_SEGMENT.matcher(segment);
            if (!m.matches()) {
                throw new IllegalArgumentException(err(context, what + " has a malformed upstream path '"
                        + path + "' at segment '" + segment + "'"));
            }
            if (m.group(2) != null && Integer.parseInt(m.group(2)) > MAX_PATH_INDEX) {
                throw new IllegalArgumentException(err(context, what + " uses array index "
                        + m.group(2) + " in '" + path + "', above the maximum of " + MAX_PATH_INDEX
                        + ". Writing that path would allocate every slot below it."));
            }
        }
    }

    /**
     * One segment of a path that is only ever READ from a response. Same shape
     * as {@link #PATH_SEGMENT} plus the {@code [*]} wildcard.
     */
    private static final Pattern READ_PATH_SEGMENT =
            Pattern.compile("^([^\\[\\]]+)(?:\\[(\\d{1,9}|\\*)\\])?$");

    /**
     * Reject a response path that cannot be read.
     *
     * <p>Separate from {@link #validatePath} because the two directions do not
     * have the same rules: a WRITE path is capped at {@link #MAX_PATH_INDEX} so
     * building it cannot allocate every slot below it, while a READ path
     * allocates nothing and may carry {@code [*]}, which no write could mean.
     * The wildcard exists for a real response shape rather than for generality:
     * Gemini returns its parts in an order that moves, so the image can sit at
     * {@code parts[0]} on one call and {@code parts[1]} on the next when the
     * model also emits text. A fixed index reads the wrong part or nothing at
     * all, for a generation that has already been charged.
     */
    private static void validateReadPath(String path, String context, String what) {
        for (String segment : path.split("\\.")) {
            if (!READ_PATH_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(err(context, what + " has a malformed response path '"
                        + path + "' at segment '" + segment + "'. Segments are dotted names, optionally "
                        + "indexed with [n] or [*] for the first element that carries the rest of the path."));
            }
        }
    }

    /**
     * True when writing both paths touches the same place in the request.
     *
     * <p>Compared segment by segment rather than as text, because text gets
     * both directions wrong. {@code startsWith} would call {@code content} and
     * {@code contents[0]} a collision (different keys entirely) while exact
     * equality calls {@code content} and {@code content[0].type} unrelated,
     * which is the one that actually breaks: the first writes a value at the
     * key, the second needs an array there, and whichever runs last destroys
     * the other's work with no error on either side.
     *
     * <p>Two paths are disjoint as soon as one segment names a different key,
     * or two indexed segments name different slots of the same array. Anything
     * else, including one path being a prefix of the other, is a collision.
     */
    static boolean pathsCollide(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int shared = Math.min(left.length, right.length);
        for (int i = 0; i < shared; i++) {
            Matcher l = PATH_SEGMENT.matcher(left[i]);
            Matcher r = PATH_SEGMENT.matcher(right[i]);
            boolean lOk = l.matches();
            boolean rOk = r.matches();
            if (!lOk || !rOk) {
                // A malformed segment is refused by validatePath before this
                // runs; compare it literally rather than guessing at its shape.
                if (!left[i].equals(right[i])) return false;
                continue;
            }
            if (!l.group(1).equals(r.group(1))) return false;
            String li = l.group(2);
            String ri = r.group(2);
            if (li != null && ri != null) {
                if (Integer.parseInt(li) != Integer.parseInt(ri)) return false;
            } else if (li != null || ri != null) {
                // Same key, but one side stores an array there and the other
                // does not. That is the scalar-versus-array collision, and it
                // is exactly the case an exact-equality check waved through.
                return true;
            }
        }
        // Every shared segment matched: one path is a prefix of the other, or
        // they are the same path.
        return true;
    }

    private static ParamBinding parseBinding(JsonNode value, String context, String unified) {
        String what = "generation.paramMap['" + unified + "']";
        boolean carriesAFile = ASSET_PARAMS.contains(unified);

        if (value.isTextual()) {
            String path = value.asText().trim();
            if (path.isEmpty()) {
                throw new IllegalArgumentException(err(context, what + " must be a non-blank upstream path"));
            }
            validatePath(path, context, what);
            requireEncoding(carriesAFile, null, context, what);
            return new ParamBinding(path, null);
        }
        if (value.isObject()) {
            String path = text(value, "path");
            if (path == null) {
                throw new IllegalArgumentException(err(context,
                        what + " object form requires a non-blank 'path'"));
            }
            validatePath(path, context, what);
            BigDecimal scale = value.hasNonNull("scale") ? value.get("scale").decimalValue() : null;
            if (scale != null && scale.signum() <= 0) {
                throw new IllegalArgumentException(err(context,
                        what + ".scale must be > 0 (got " + scale + ")"));
            }

            AssetEncoding encoding = null;
            String rawEncoding = text(value, "encoding");
            if (rawEncoding != null) {
                encoding = AssetEncoding.parse(rawEncoding);
                if (encoding == null) {
                    throw new IllegalArgumentException(err(context, what + ".encoding '" + rawEncoding
                            + "' is not a supported form. Accepted: " + encodings()));
                }
                if (!carriesAFile) {
                    throw new IllegalArgumentException(err(context, what + ".encoding only means "
                            + "something for a parameter that carries a file (" + sorted(ASSET_PARAMS)
                            + "); '" + unified + "' carries a value, which is sent as it stands."));
                }
            }
            requireEncoding(carriesAFile, encoding, context, what);

            AssetRole role = null;
            String rawRole = text(value, "role");
            if (rawRole != null) {
                role = AssetRole.parse(rawRole);
                if (role == null) {
                    throw new IllegalArgumentException(err(context, what + ".role '" + rawRole
                            + "' is not one the surfaces know how to name. Accepted: " + roles()));
                }
                if (!carriesAFile) {
                    throw new IllegalArgumentException(err(context, what + ".role describes what a "
                            + "FILE means to this endpoint, and '" + unified + "' does not carry one."));
                }
            }
            if (carriesAFile && role == null) {
                // The slot alone says "an image goes here"; it does not say
                // whether the image comes BACK changed, becomes the first frame
                // of a clip, or only lends its style. A surface cannot label the
                // field without that, and labelling it wrong costs a paid call.
                throw new IllegalArgumentException(err(context, what + " carries a file, so it needs "
                        + "a 'role' saying what the file IS to this endpoint: " + roles()));
            }

            int maxItems = 1;
            if (value.hasNonNull("maxItems")) {
                maxItems = value.get("maxItems").asInt(0);
                if (!carriesAFile) {
                    throw new IllegalArgumentException(err(context, what + ".maxItems counts FILES, "
                            + "and '" + unified + "' carries a value."));
                }
                if (maxItems < 1 || maxItems > MAX_ASSET_ITEMS) {
                    throw new IllegalArgumentException(err(context, what + ".maxItems must be between 1 and "
                            + MAX_ASSET_ITEMS + " (got " + maxItems + ")"));
                }
            }
            // A slot that takes several files has to say WHERE each one lands,
            // and the path already can: it names the FIRST file's position, and
            // the ones after it walk forward from there. Without an index they
            // would all be written to the same place, and the provider would
            // receive one image while the caller was told it took three.
            //
            // No new syntax on purpose. Gemini is why: its images sit AFTER the
            // text part, so an expansion that always started at zero would
            // overwrite the prompt. Writing contents[0].parts[1]... says both
            // where to start and where to continue.
            if (maxItems > 1 && !INDEXED_SEGMENT.matcher(path).find()) {
                throw new IllegalArgumentException(err(context, what + " takes up to " + maxItems
                        + " files, so its path must carry an index saying where the first one goes "
                        + "(e.g. image[0].url, or contents[0].parts[1]... when a text part comes "
                        + "first). Without it they would all land on '" + path + "'."));
            }

            String mimePath = text(value, "mimePath");
            if (mimePath != null) {
                if (encoding != AssetEncoding.BASE64) {
                    throw new IllegalArgumentException(err(context, what + ".mimePath is only for the '"
                            + AssetEncoding.BASE64.wire() + "' encoding, where the provider takes the "
                            + "bytes and their type in two fields. A data URL already carries the type."));
                }
                validatePath(mimePath, context, what + ".mimePath");
            }
            Map<String, Object> itemConstants = Map.of();
            if (value.hasNonNull("itemConstants")) {
                JsonNode node = value.get("itemConstants");
                if (!node.isObject() || node.isEmpty()) {
                    throw new IllegalArgumentException(err(context, what + ".itemConstants must be an "
                            + "object of path -> value, and a non-empty one: an empty block says "
                            + "nothing and hides a field somebody meant to send."));
                }
                if (!carriesAFile) {
                    throw new IllegalArgumentException(err(context, what + ".itemConstants are written "
                            + "beside a FILE, and '" + unified + "' carries a value."));
                }
                Map<String, Object> collected = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String itemPath = field.getKey();
                    validatePath(itemPath, context, what + ".itemConstants['" + itemPath + "']");
                    if (itemPath.equals(path) || itemPath.equals(mimePath)) {
                        throw new IllegalArgumentException(err(context, what + ".itemConstants['"
                                + itemPath + "'] writes over the file itself: a constant that "
                                + "overwrites the value it travels with sends a type and no bytes."));
                    }
                    // It has to move WITH the file. A constant on a fixed path would be written once
                    // per element to the same place, so element 2's type would land on element 1.
                    if (!sharesIndexedPrefix(path, itemPath)) {
                        throw new IllegalArgumentException(err(context, what + ".itemConstants['"
                                + itemPath + "'] must sit in the same element as '" + path + "', so "
                                + "it moves with the file. Share the indexed prefix (e.g. content[1].type "
                                + "beside content[1].image_url.url)."));
                    }
                    collected.put(itemPath, literal(field.getValue()));
                }
                itemConstants = collected;
            }
            return new ParamBinding(path, scale, encoding, mimePath, role, maxItems, itemConstants);
        }

        throw new IllegalArgumentException(err(context, what + " must be an upstream path string, "
                + "or an object {path, scale} when the value needs converting"
                + (carriesAFile ? ", and a parameter carrying a file needs {path, encoding}" : "")));
    }

    /**
     * True when two paths name fields of the SAME array element.
     *
     * <p>Compared on the segment up to and including the last index of the file's own path: a
     * constant that does not share it is anchored somewhere the file never moves to, so shifting
     * it would either do nothing or land it on a neighbour's element.
     */
    private static boolean sharesIndexedPrefix(String filePath, String itemPath) {
        java.util.regex.Matcher m = INDEXED_SEGMENT.matcher(filePath);
        int end = -1;
        while (m.find()) end = m.end();
        if (end < 0) return false;
        return itemPath.startsWith(filePath.substring(0, end));
    }


    /**
     * A parameter that carries a file cannot be bound without saying what the
     * provider wants it as.
     *
     * <p>This is the rule that makes the previously broken state impossible to
     * write down. The surfaces hand these parameters over as a FileRef; with no
     * encoding the request builder wrote that Map straight into the provider's
     * body, which no provider understands. Nothing failed at import and nothing
     * failed at build: the call went out, was charged, and came back rejected.
     */
    private static void requireEncoding(boolean carriesAFile, AssetEncoding encoding,
                                         String context, String what) {
        if (carriesAFile && encoding == null) {
            throw new IllegalArgumentException(err(context, what + " carries a file, so it needs an "
                    + "'encoding' saying what this provider wants it as: " + encodings()
                    + ". Without one the platform's file handle would be written into the request "
                    + "and the provider would refuse a call that has already been paid for."));
        }
    }

    private static String roles() {
        return Arrays.stream(AssetRole.values()).map(AssetRole::wire).sorted().toList().toString();
    }

    private static String encodings() {
        return Arrays.stream(AssetEncoding.values()).map(AssetEncoding::wire).sorted().toList().toString();
    }

    /** Narrow a scalar JSON node to the Java literal that will be sent upstream. */
    private static Object literal(JsonNode v) {
        if (v.isBoolean()) return v.booleanValue();
        if (v.isIntegralNumber()) return v.longValue();
        if (v.isNumber()) return v.decimalValue();
        if (v.isNull()) return null;
        return v.asText();
    }

    private static Model parseModel(JsonNode m, String context, Set<String> mappedParams,
                                     boolean endpointSelectsModel) {
        if (!m.isObject()) {
            throw new IllegalArgumentException(err(context, "each generation.models entry must be an object"));
        }
        String id = text(m, "id");
        if (id == null) {
            throw new IllegalArgumentException(err(context, "generation.models[].id is required"));
        }
        id = id.toLowerCase(Locale.ROOT);
        if (!MODEL_ID_SHAPE.matcher(id).matches()) {
            throw new IllegalArgumentException(err(context, "generation model id '" + id
                    + "' must be lowercase alphanumeric with . _ or - separators"));
        }
        // `upstream` only means something when the endpoint actually selects a
        // model. A single-model endpoint has nothing to select, so requiring it
        // there would be ceremony; the id stands in and is never sent.
        String upstream = text(m, "upstream");
        if (upstream == null) {
            if (endpointSelectsModel) {
                throw new IllegalArgumentException(err(context, "generation.models['" + id
                        + "'].upstream is required because generation.modelParam is set "
                        + "(it is the value sent as that parameter)"));
            }
            upstream = id;
        }
        String label = text(m, "label");
        if (label == null) label = id;

        Set<String> capabilities = new LinkedHashSet<>();
        JsonNode caps = m.path("capabilities");
        if (caps.isArray()) {
            for (JsonNode c : caps) {
                String cap = c.asText("");
                if (!UNIFIED_PARAMS.contains(cap)) {
                    throw new IllegalArgumentException(err(context, "generation.models['" + id
                            + "'].capabilities contains '" + cap + "', which is not a unified parameter. "
                            + "Accepted: " + sorted(UNIFIED_PARAMS)));
                }
                if (!mappedParams.contains(cap)) {
                    throw new IllegalArgumentException(err(context, "generation.models['" + id
                            + "'] declares capability '" + cap + "' but generation.paramMap has no mapping for it, "
                            + "so the value could never reach the provider"));
                }
                capabilities.add(cap);
            }
        }
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException(err(context, "generation.models['" + id
                    + "'].capabilities must list at least one unified parameter (a model that accepts nothing "
                    + "cannot be called)"));
        }

        // Parameters the provider will reject the call without. Declaring them
        // here is what keeps the promise that validation happens BEFORE the
        // customer pays: ElevenLabs needs a voice, and without this the call
        // passes every check, dispatches, and fails upstream on a missing path
        // parameter that was already billed for.
        Set<String> required = new LinkedHashSet<>();
        JsonNode req = m.path("required");
        if (req.isArray()) {
            for (JsonNode r : req) {
                String name = r.asText("");
                if (!capabilities.contains(name)) {
                    throw new IllegalArgumentException(err(context, "generation.models['" + id
                            + "'].required lists '" + name + "', which is not one of its capabilities"));
                }
                required.add(name);
            }
        }
        // Every model needs an instruction, so prompt is required by default
        // rather than by repetition in every seed.
        if (capabilities.contains("prompt")) {
            required.add("prompt");
        }

        final String modelId = id;
        Map<String, Constraint> constraints = new LinkedHashMap<>();
        JsonNode cons = m.path("constraints");
        if (cons.isObject()) {
            cons.fields().forEachRemaining(e -> {
                String param = e.getKey();
                if (!capabilities.contains(param)) {
                    throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                            + "'].constraints references '" + param + "', which is not one of its capabilities"));
                }
                constraints.put(param, parseConstraint(e.getValue(), context, modelId, param));
            });
        }

        // Values this model always sends, e.g. the quality tier its price is
        // for. Refused when the model also ACCEPTS that parameter: the request
        // builder writes constants first and caller values second, so the
        // caller would silently overwrite the tier the price was computed from.
        Map<String, Object> modelConstants = new LinkedHashMap<>();
        JsonNode mc = m.path("constants");
        if (mc.isObject()) {
            mc.fields().forEachRemaining(e -> {
                if (e.getValue().isContainerNode()) {
                    throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                            + "'].constants['" + e.getKey() + "'] must be a scalar. Build nested shapes "
                            + "with indexed paths (e.g. content[0].type) instead."));
                }
                validatePath(e.getKey(), context,
                        "generation.models['" + modelId + "'].constants['" + e.getKey() + "']");
                modelConstants.put(e.getKey(), literal(e.getValue()));
            });
        }

        Model model = new Model(id, upstream, label,
                Collections.unmodifiableSet(capabilities),
                Collections.unmodifiableSet(required),
                Collections.unmodifiableMap(constraints),
                Collections.unmodifiableMap(modelConstants),
                parsePrice(m.path("price"), context, id));

        // A model sold by a dimension has to be able to state that dimension on
        // every call, or the price multiplies a number nobody supplied. Here we
        // refuse only the case that NO call could ever fix: the parameter that
        // measures the dimension is not even accepted, so neither the caller nor
        // the platform can ever say how big the call is.
        //
        // The softer case (the parameter is accepted but the model neither
        // defaults nor requires it) is deliberately NOT fatal here. This parser
        // also runs against descriptors already stored in the catalog, and
        // throwing would delete a live model from the platform on upgrade
        // instead of fixing anything. That case is caught where it can still be
        // edited - the seed authoring gate - and, at runtime, the individual
        // call is refused with the parameter named.
        String measuring = model.measuringParam();
        if (measuring != null && !model.accepts(measuring)) {
            throw new IllegalArgumentException(err(context, "generation.models['" + id
                    + "'] is priced per " + model.price().unit() + " but does not accept '"
                    + measuring + "', so nothing can ever say how big a call is and the price "
                    + "would multiply a number nobody supplied. Add '" + measuring
                    + "' to its capabilities, or price it per 'call'."));
        }
        return model;
    }

    private static Constraint parseConstraint(JsonNode c, String context, String modelId, String param) {
        if (!c.isObject()) {
            throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                    + "'].constraints['" + param + "'] must be an object with "
                    + sorted(CONSTRAINT_KEYS)));
        }
        // An unknown key here used to be dropped in silence, which is the worst
        // possible outcome for a validation block: the seed reads as if the
        // value were restricted, the restriction never runs, and the provider
        // refuses the call after it has been paid for. A misspelling is now a
        // red import.
        c.fieldNames().forEachRemaining(key -> {
            if (!CONSTRAINT_KEYS.contains(key)) {
                throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                        + "'].constraints['" + param + "'] has unknown key '" + key
                        + "'. Accepted: " + sorted(CONSTRAINT_KEYS)));
            }
        });
        List<Object> allowed = new ArrayList<>();
        JsonNode a = c.path("allowed");
        if (a.isArray()) {
            for (JsonNode v : a) {
                allowed.add(v.isNumber() ? v.numberValue() : v.asText());
            }
        }
        BigDecimal min = c.hasNonNull("min") ? c.get("min").decimalValue() : null;
        BigDecimal max = c.hasNonNull("max") ? c.get("max").decimalValue() : null;
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                    + "'].constraints['" + param + "'] has min > max"));
        }
        Integer maxLength = null;
        if (c.hasNonNull("maxLength")) {
            int declared = c.get("maxLength").asInt(0);
            if (declared <= 0) {
                throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                        + "'].constraints['" + param + "'].maxLength must be > 0 (got " + declared + ")"));
            }
            maxLength = declared;
        }
        return new Constraint(Collections.unmodifiableList(allowed), min, max, maxLength);
    }

    /** Every key a constraint may carry. Anything else is a seed mistake. */
    private static final Set<String> CONSTRAINT_KEYS = Set.of("allowed", "min", "max", "maxLength");

    /**
     * Parse the seed's starting price. An omitted block yields
     * {@link Price#free()} rather than an error: a provider can legitimately be
     * onboarded before its price is decided, and the platform's fail-closed
     * gate at call time is what prevents it being given away.
     */
    private static Price parsePrice(JsonNode p, String context, String modelId) {
        if (p == null || p.isMissingNode() || p.isNull()) return Price.free();
        if (!p.isObject()) {
            throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                    + "'].price must be an object"));
        }
        String unit = text(p, "unit");
        if (unit == null) unit = "call";
        unit = unit.toLowerCase(Locale.ROOT);
        if (!Price.UNITS.contains(unit)) {
            throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                    + "'].price.unit '" + unit + "' is not supported. Accepted: " + sorted(Price.UNITS)));
        }
        BigDecimal base = decimal(p, "baseCredits", BigDecimal.ZERO);
        BigDecimal per = decimal(p, "unitCredits", BigDecimal.ZERO);
        BigDecimal min = p.hasNonNull("minCredits") ? p.get("minCredits").decimalValue() : null;
        BigDecimal max = p.hasNonNull("maxCredits") ? p.get("maxCredits").decimalValue() : null;

        if (base.signum() < 0 || per.signum() < 0) {
            throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                    + "'].price credits must be >= 0"));
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                    + "'].price has minCredits > maxCredits"));
        }
        if (!"call".equals(unit) && per.signum() == 0) {
            throw new IllegalArgumentException(err(context, "generation.models['" + modelId
                    + "'].price declares unit '" + unit + "' but unitCredits is 0, so the unit has no effect. "
                    + "Set unitCredits, or use unit 'call' for a flat price."));
        }
        return new Price(unit, base, per, min, max);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.decimalValue() : fallback;
    }

    private static String sorted(Set<String> values) {
        List<String> l = new ArrayList<>(values);
        Collections.sort(l);
        return String.join(", ", l);
    }

    private static String err(String context, String message) {
        return "[generation spec] " + context + ": " + message;
    }
}
