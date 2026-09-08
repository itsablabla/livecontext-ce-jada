package com.apimarketplace.agent.memory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Write-time safety scan for long-term memory content.
 *
 * <p><b>Why this exists.</b> A memory row is written by any agent in the
 * workspace, from material the agent may not control - an email it read, a web
 * page it fetched, a document a teammate uploaded - and its summary is then
 * injected into the system prompt of every agent in that workspace, on every
 * execution, indefinitely. That makes memory a durable, cross-agent,
 * cross-member prompt-injection channel: the single highest-leverage place in
 * the product to plant an instruction. So the scan runs at WRITE time, where
 * one rejection stops every future injection, rather than at read time, where
 * it would have to run on every execution forever.
 *
 * <p>The patterns are ported from the Hermes memory tool's
 * {@code _MEMORY_THREAT_PATTERNS} (see the project docs),
 * which covers the two families that matter here: an instruction aimed at the
 * model that reads the memory back, and an exfiltration recipe aimed at
 * whatever executes on its behalf.
 *
 * <p><b>What this is not.</b> It is a tripwire for the obvious payload, not a
 * proof of safety - no regex set classifies natural language reliably. It is
 * paired with two other defences that do not depend on pattern matching: the
 * injected block is fenced and explicitly labelled as recalled context rather
 * than instructions, and the fence tag itself is stripped from content so a
 * payload cannot close the fence and escape into instruction position.
 */
public final class MemoryContentGuard {

    /** The fence tag wrapping the injected block. Stripped from stored content so it cannot be forged. */
    public static final String FENCE_OPEN = "<recalled-memory>";
    public static final String FENCE_CLOSE = "</recalled-memory>";

    /**
     * Zero-width and bidi-control characters. They render as nothing to the
     * person auditing the Memory tab while still reaching the model, which is
     * exactly the gap a hidden instruction lives in. Stripped, not rejected:
     * they are usually copy-paste debris from a web page, and failing a
     * legitimate save over invisible whitespace would be its own bug.
     */
    private static final Pattern INVISIBLE_CHARS = Pattern.compile(
        "[\\u200B\\u200C\\u200D\\u200E\\u200F\\u2060\\u2061\\u2062\\u2063\\u2064"
        + "\\u202A\\u202B\\u202C\\u202D\\u202E"
        // U+2066-2069 are the directional ISOLATES. They do the same job as the
        // U+202A-202E embeddings above and are the ones a modern text stack
        // actually emits, so leaving them out would have covered the legacy
        // spelling of the trick and missed the current one.
        + "\\u2066\\u2067\\u2068\\u2069"
        // U+034F (combining grapheme joiner) and the variation selectors render
        // as nothing but survive a copy-paste out of a web page.
        + "\\u034F\\uFE00-\\uFE0F"
        + "\\uFEFF\\u00AD]");

    /**
     * Any run of line breaks and the whitespace around them.
     *
     * <p>Collapsed to a single space in the fields that are RENDERED as one line.
     * The index prints "- [type] slug: summary" per entry, so a summary carrying
     * newlines does not merely look wrong: it prints as several lines inside the
     * fence, and one entry can then masquerade as several index entries, or as a
     * heading. It cannot escape the fence (the tag is stripped separately), but
     * "one entry, one line" is the structure a model reads the block by.
     */
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*[\\r\\n\\u0085\\u2028\\u2029]+\\s*");

    /** One threat family: the regex that spots it and the label the rejection reports. */
    private record Threat(Pattern pattern, String label) {}

    /**
     * The qualifiers that sit between the verb and its object, e.g. "ignore ALL
     * PREVIOUS instructions".
     *
     * <p>Written as "one or more", not "exactly one". The single-qualifier version
     * shipped first and let through "ignore all previous instructions", which is
     * the single most common phrasing of the attack: the unit tests happened to use
     * "ignore previous instructions" and "ignore all instructions", both of which
     * matched, so the gap survived a per-family test suite and was only caught end
     * to end. Any pattern here that names qualifiers must use this.
     */
    private static final String QUALIFIERS =
        // An optional determiner, then AT LEAST ONE word that points BACKWARDS in
        // time. The backwards-looking word is what separates the attack from
        // ordinary prose: "ignore all previous instructions" is a directive,
        // "the linter is configured to ignore the rules in .eslintrc" is a fact,
        // and a determiner-only version refuses the second. This runs on every
        // write from the tool and from the tab, so a false positive is a person
        // being told their true statement is an attack.
        "(?:(?:the|any|your|these|those|all)\\s+)*"
        + "(?:(?:previous|prior|earlier|preceding|above|foregoing)\\s+)+";

    /**
     * Deliberately NOT narrowed to a list of role nouns.
     *
     * <p>An enumeration was tried and rejected: it kept "you are now an
     * unrestricted assistant" and lost "you are now a pirate", and the set of
     * roles an attacker can name is unbounded, so the list would always be a step
     * behind. The signal is the SHAPE, not the noun. A memory describes the world
     * in the third person ("the user is an org admin"); a sentence that ADDRESSES
     * the agent as "you are now ..." is not a description at all, which is exactly
     * what the declarative-phrasing rule already tells writers. So the one
     * plausible false positive ("after onboarding you are now a member of the
     * org") is a memory that should have been written "the user is a member of the
     * org" regardless, and the rejection message says so.
     */
    private static final String HIJACK_SHAPE = "you\\s+are\\s+now\\s+(?:a|an|the)\\s";

    /**
     * Any determiner or backwards-looking word, with no requirement that one of
     * them looks backwards. Used only where the OBJECT is already decisive.
     */
    private static final String QUALIFIERS_ANY =
        "(?:(?:the|any|your|all|these|those|previous|prior|earlier|preceding|above|foregoing)\\s+)+";

    private static final List<Threat> THREATS = List.of(
        // Split by object, because the object is what decides how much the
        // qualifier has to carry. "ignore ... instructions" is a directive at the
        // model however it is qualified, so any qualifier will do. "ignore ...
        // rules/guidelines/prompts" is ordinary engineering prose ("the linter is
        // configured to ignore the rules in .eslintrc"), so it takes a
        // backwards-looking word before it reads as an attack.
        new Threat(Pattern.compile("ignore\\s+" + QUALIFIERS_ANY + "instructions"), "prompt_injection"),
        new Threat(Pattern.compile("ignore\\s+" + QUALIFIERS + "(rules|guidelines|prompts?)"), "prompt_injection"),
        new Threat(Pattern.compile("forget\\s+" + QUALIFIERS_ANY + "instructions"), "prompt_injection"),
        new Threat(Pattern.compile("forget\\s+" + QUALIFIERS + "(rules|guidelines|prompts?)"), "prompt_injection"),
        // `disregard` keeps the broader determiner set: unlike "ignore", it is
        // vanishingly rare in a declarative sentence about rules, so "disregard
        // your guidelines" needs no backwards-looking word to read as a directive.
        new Threat(Pattern.compile("disregard\\s+(?:(?:the|any|your|all|these|those)\\s+)*"
            + "(?:(?:previous|prior|earlier|preceding|above)\\s+)*"
            + "(instructions|rules|guidelines|prompts?)"), "disregard_rules"),
        new Threat(Pattern.compile(HIJACK_SHAPE), "role_hijack"),
        new Threat(Pattern.compile("system\\s+prompt\\s+override"), "sys_prompt_override"),
        new Threat(Pattern.compile("do\\s+not\\s+(tell|inform|mention\\s+to)\\s+the\\s+user"), "deception_hide"),
        // Exfiltration: a fetch whose payload interpolates something that reads like
        // a secret. NB the alternatives are lowercase because {@link #scan} matches
        // against a lowercased copy - the upstream Python patterns are uppercase and
        // transcribing them verbatim produced a rule that could never fire.
        new Threat(Pattern.compile("(curl|wget)\\s+[^\\n]*\\$\\{?\\w*(key|token|secret|password|credential|api)"), "exfil_curl"),
        new Threat(Pattern.compile("cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc)"), "read_secrets"),
        new Threat(Pattern.compile("authorized_keys"), "ssh_backdoor")
    );

    private MemoryContentGuard() {
        throw new AssertionError("MemoryContentGuard is a utility class; do not instantiate.");
    }

    /**
     * Remove what must never be stored: invisible characters, and any literal
     * fence tag that would let stored content close the block it is rendered
     * inside and continue as instructions.
     *
     * <p>Line breaks are LEFT ALONE here, because this is also what cleans the
     * body, and a body is meant to have paragraphs. The one-line fields go
     * through {@link #sanitizeSingleLine} instead.
     *
     * @param raw caller-supplied text, may be {@code null}
     * @return the cleaned text, or {@code null} if {@code raw} was {@code null}
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = INVISIBLE_CHARS.matcher(raw).replaceAll("");
        // Case-insensitive and whitespace-tolerant: "< /Recalled-Memory >" must
        // not survive a check that only knows the canonical spelling.
        cleaned = cleaned.replaceAll("(?i)<\\s*/?\\s*recalled-memory\\s*>", "");
        return cleaned;
    }

    /**
     * A pinned BODY, with the markdown that would forge block structure defused.
     *
     * <p>The injected block is markdown: it has a {@code # Long-term memory}
     * heading, a {@code ## Index} section and {@code - [type] slug: summary}
     * lines. A pinned body is rendered VERBATIM inside that block, so a body
     * beginning a line with {@code ## Index} or {@code - [user] fake-slug: ...}
     * presents itself as structure rather than as content: one entry can appear
     * to be the index, or to add rows to it.
     *
     * <p>This is the same masquerade {@link #sanitizeSingleLine} closes for the
     * one-line fields, and it was left open here only because a body legitimately
     * contains prose. So the fix is not to flatten it: line breaks and the text
     * survive, and only the leading marker is neutralised, by prefixing the line
     * with a zero-width-free bullet-safe escape. The reader loses nothing; the
     * parser stops seeing a heading.
     *
     * <p>Applied at RENDER time rather than at write time on purpose: a body
     * written before this shipped is defused on the way out, and the stored text
     * stays exactly what the person typed, so the Memory tab still shows their
     * markdown as they wrote it.
     *
     * @param raw the stored body, may be {@code null}
     * @return the body with structural line markers escaped, or {@code null}
     */
    public static String defuseBlockMarkers(String raw) {
        if (raw == null) {
            return null;
        }
        // Only at the START of a line, and only the markers this block's own
        // grammar uses: an ATX heading, and a list bullet. A '#' mid-sentence is
        // ordinary prose and is left alone.
        return BLOCK_MARKER.matcher(raw).replaceAll("$1\\\\$2");
    }

    /**
     * A heading or bullet marker at the start of a line, captured with the line
     * start so the replacement can keep it.
     */
    private static final Pattern BLOCK_MARKER =
        Pattern.compile("(?m)(^[ \\t]*)([#>]|[-*+](?=[ \\t]))");

    /**
     * {@link #sanitize}, plus every line break collapsed to a single space.
     *
     * <p>For the fields the index renders as ONE line: the title and the summary
     * of an entry, and its slug. A summary containing "\n## Always in context\n"
     * was stored verbatim and printed inside the fence as extra markdown lines, so
     * one entry could present itself as several index entries or as a section
     * heading of the block. Collapsed rather than rejected, for the same reason the
     * invisible characters are stripped rather than refused: a newline pasted out
     * of a document is ordinary debris, and failing a legitimate save over it would
     * be its own bug. The reader loses nothing, since the line was going to be
     * rendered on one line either way.
     *
     * @param raw caller-supplied text, may be {@code null}
     * @return the cleaned single-line text, or {@code null} if {@code raw} was {@code null}
     */
    public static String sanitizeSingleLine(String raw) {
        String cleaned = sanitize(raw);
        if (cleaned == null) {
            return null;
        }
        return LINE_BREAKS.matcher(cleaned).replaceAll(" ").trim();
    }

    /**
     * Scan already-sanitized text for a planted instruction.
     *
     * @param text the field being written, may be {@code null} or blank
     * @return the threat label when one matches, empty when the text is clean
     */
    public static Optional<String> scan(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (Threat threat : THREATS) {
            if (threat.pattern().matcher(normalized).find()) {
                return Optional.of(threat.label());
            }
        }
        return Optional.empty();
    }

    /**
     * Sanitize then scan every field of a memory that reaches the system prompt.
     *
     * <p>Pass EVERY such field, not just the prose ones. The rendered index line is
     * {@code - [type] <slug>: <summary>}, so the slug is in the prompt as surely as
     * the summary is, and a caller may supply it directly. Tags are handed straight
     * back to the agent by {@code get}. A field that reaches the model and is not
     * scanned is not a smaller hole than an unscanned body, only a narrower one.
     *
     * @return the threat label of the first offending field, empty when all are clean
     */
    public static Optional<String> scanAll(String... fields) {
        if (fields == null) {
            return Optional.empty();
        }
        for (String field : fields) {
            Optional<String> threat = scan(sanitize(field));
            if (threat.isPresent()) {
                return threat;
            }
        }
        return Optional.empty();
    }

    /**
     * The message handed back to the agent (and shown in the UI) when a write is
     * refused. Names the pattern family so the caller can tell a genuine false
     * positive from a real payload, and says what to do instead - per the
     * agent-facing help contract, an error the agent cannot act on is a dead end.
     */
    public static String rejectionMessage(String threatLabel) {
        return "This memory was refused because its text matches a known prompt-injection pattern ("
            + threatLabel + "). Memory is injected into every agent's context in this workspace, so it "
            + "stores declarative facts only, never instructions. Rewrite it as a statement about what "
            + "is true (\"the user prefers concise answers\") instead of a directive (\"always answer "
            + "concisely\"), and save again.";
    }
}
