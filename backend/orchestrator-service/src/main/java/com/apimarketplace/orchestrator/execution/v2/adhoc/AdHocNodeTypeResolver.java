package com.apimarketplace.orchestrator.execution.v2.adhoc;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderModifier;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the agent-facing {@code type} of a {@code run_node} call onto the canonical core type,
 * and says whether that type can mean anything on its own.
 *
 * <p><b>The ordering matters.</b> {@code add_node} checks "is this node type enabled?" against the
 * type the agent typed, BEFORE its alias table runs. So {@code type='remote_command'} sails past a
 * gate that an administrator set on {@code ssh}. Reproducing that order here would reproduce the
 * bypass, this time on a surface that executes rather than one that edits a draft, so this resolves
 * the alias FIRST and gates the canonical name. The pre-existing behaviour on {@code add_node} is
 * left alone: changing it is a separate change with its own blast radius.
 */
public final class AdHocNodeTypeResolver {

    /** Prefixes an agent may put in front of a type ({@code core:code}). */
    private static final String[] PREFIXES = {"core:", "trigger:", "agent:", "mcp:", "table:", "interface:", "note:"};

    /**
     * Alias to canonical type. Mirrors the corresponding arms of {@code add_node}'s switch, so a
     * config validated here is accepted verbatim by {@code add_node}.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("imap", "email_inbox"),
            Map.entry("inbox", "email_inbox"),
            Map.entry("read_email", "email_inbox"),
            Map.entry("receive_email", "email_inbox"),
            Map.entry("smtp", "send_email"),
            Map.entry("mail", "send_email"),
            Map.entry("email", "send_email"),
            Map.entry("remote_command", "ssh"),
            Map.entry("shell", "ssh"),
            Map.entry("sql", "database"),
            Map.entry("db", "database"),
            Map.entry("script", "code"),
            Map.entry("javascript", "code"),
            Map.entry("js", "code"),
            Map.entry("python", "code"),
            Map.entry("http", "http_request"),
            Map.entry("request", "http_request"),
            Map.entry("fetch", "http_request"),
            Map.entry("download", "download_file"),
            Map.entry("datetime", "date_time"),
            Map.entry("jwt", "crypto_jwt"),
            Map.entry("dedupe", "remove_duplicates"),
            Map.entry("html", "html_extract")
    );

    /**
     * Types that suspend on a signal only a run can resolve.
     *
     * <p>They are refused rather than executed. Left to run they return {@code AWAITING_SIGNAL}
     * immediately, which reads as neither success nor failure, and {@code interface} additionally
     * registers a signal row keyed on a run id that will never exist.
     */
    private static final Set<String> SIGNAL_TYPES = Set.of(
            "approval", "user_approval", "interface", "wait");

    /**
     * Types whose meaning is the DAG around them, not the node.
     *
     * <p>Refused with an explanation rather than run: standalone they return something
     * technically true and practically misleading. {@code merge} skips itself for want of
     * predecessors, {@code aggregate} finalises on a batch of one or never, {@code split} leaves
     * an in-memory split context keyed on a run that ends with the call.
     */
    private static final Set<String> DAG_TYPES = Set.of(
            "merge", "aggregate", "split", "fork", "loop", "end");

    /**
     * Types that would make the "nothing is persisted" contract false.
     *
     * <p>{@code sub_workflow} does not compute anything itself: it FIRES a trigger on the child's
     * live run and the whole child DAG executes, writing workflow_step_data and storage rows. It
     * also carries an agent-supplied, uncapped timeout, so it parks a thread well past the ad-hoc
     * ceiling while the caller is told the probe timed out. Whatever this surface is, it is not a
     * probe when it runs that.
     */
    private static final Set<String> PERSISTING_TYPES = Set.of("sub_workflow");

    /**
     * Types the plan files under {@code agents} rather than {@code cores}.
     *
     * <p>Only {@code generate} is here, and only because it is the one AI node
     * that computes something on its own: it takes a model and a prompt and
     * hands back a file, with no conversation, no tools and no entity to
     * resolve. The other AI nodes are refused for their own reasons elsewhere.
     *
     * <p>What it is FOR: the synthetic plan has to put the node in the list its
     * builder reads. Filed among the cores, {@code generate} parses (the type is
     * still accepted there so a plan saved before the move still opens) but no
     * builder produces a node for it, and the caller is told it cannot run
     * standalone, which is false.
     */
    public static final Set<String> AGENT_TYPES = Set.of("generate");

    private AdHocNodeTypeResolver() {
    }

    /** Strip any prefix and resolve the alias. Returns the canonical type, lowercased. */
    public static String canonicalType(String rawType) {
        if (rawType == null) return null;
        String type = rawType.trim().toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (type.startsWith(prefix)) {
                type = type.substring(prefix.length());
                break;
            }
        }
        return ALIASES.getOrDefault(type, type);
    }

    /**
     * The nested key this type's config is stored under, or null when it sits at the core's top
     * level. Read from {@link WorkflowBuilderModifier#NESTED_CONFIG_KEYS} rather than re-listed,
     * because that map is kept exhaustive against the plan parser: a private copy here would go
     * stale and deposit config where nothing reads it.
     */
    public static String configKey(String canonicalType) {
        return WorkflowBuilderModifier.NESTED_CONFIG_KEYS.get(canonicalType);
    }

    /** Null when the type may run standalone, else the reason it may not. */
    public static String refusalReason(String canonicalType) {
        if (canonicalType == null || canonicalType.isBlank()) {
            return "A node type is required.";
        }
        if (SIGNAL_TYPES.contains(canonicalType)) {
            return "Type '" + canonicalType + "' pauses on a signal (a timer, an approval, an "
                    + "interface interaction) that only a real run can deliver, so running it here "
                    + "would suspend on something nothing can resolve. Build it into a workflow and "
                    + "use workflow(action='execute'), then resolve_approval or continue_interface.";
        }
        if (PERSISTING_TYPES.contains(canonicalType)) {
            return "Type '" + canonicalType + "' does not compute anything on its own: it fires a trigger on "
                    + "another workflow's run, so the whole child executes and persists its steps. That is a "
                    + "real execution, not a probe. Use workflow(action='execute') on the child directly.";
        }
        if (DAG_TYPES.contains(canonicalType)) {
            return "Type '" + canonicalType + "' has no meaning on its own: what it does is decided by "
                    + "the nodes around it. Standalone it would return something technically true and "
                    + "misleading. Build it into a workflow and use workflow(action='execute').";
        }
        return null;
    }
}
