package com.apimarketplace.orchestrator.controllers.workflow;

import java.util.List;
import java.util.Map;

/**
 * Removes raw inline secrets (and credential references) from a workflow plan map.
 *
 * <p>A workflow plan can carry RAW secret values inline in node params: an HTTP Request
 * node's {@code authConfig} (bearer token / API key / basic-auth password / header value),
 * a Crypto/JWT node's {@code key}/{@code secret}/{@code token}, and the inline
 * {@code password}/{@code privateKey} fallbacks of SSH/SFTP/Database nodes. When the
 * {@code GET /api/workflows/{id}} response is served to an anonymous APPLICATION share-link
 * visitor (owner-impersonation, {@code X-Share-Context: true}), the raw plan would otherwise
 * hand those secrets to the visitor. This scrubs them.
 *
 * <p>Mirrors the marketplace acquire-time strip in publication-service's
 * {@code SnapshotCloneService.stripSensitiveCredentials}, and additionally covers the
 * SSH/SFTP/Database inline fields that strip omits (they resolve against the caller's own
 * credentials at execution time, so removing them from a shared read is a no-op for the app).
 */
public final class PlanSecretRedactor {

    private PlanSecretRedactor() {
    }

    /**
     * Redacts secret-bearing fields from the given plan map IN PLACE. Callers that must not
     * mutate the source (e.g. a persisted entity's plan) must pass a deep copy.
     */
    @SuppressWarnings("unchecked")
    public static void redact(Map<String, Object> plan) {
        if (plan == null) {
            return;
        }
        Object coresRaw = plan.get("cores");
        if (coresRaw instanceof List<?> cores) {
            for (Object core : cores) {
                if (!(core instanceof Map<?, ?> coreMap)) {
                    continue;
                }
                // Raw inline secrets.
                removeChildKeys(coreMap, "httpRequest", "authConfig");
                removeChildKeys(coreMap, "cryptoJwt", "key", "secret", "token");
                // The credentialId goes with the raw fallback in each of
                // these, for the reason stated below: a reference to one of
                // the author's credentials is not the viewer's to see, and
                // the three connectors here were the only ones still handing
                // theirs out.
                removeChildKeys(coreMap, "ssh", "password", "privateKey", "credentialId");
                removeChildKeys(coreMap, "sftp", "password", "privateKey", "credentialId");
                removeChildKeys(coreMap, "database", "password", "credentialId");
                // sendEmail carries an inline SMTP password RAW fallback (alongside credentialId).
                removeChildKeys(coreMap, "sendEmail", "credentialId", "smtpPassword");
                // Credential references (numeric ids, low value but not the viewer's to see).
                removeChildKeys(coreMap, "emailInbox", "credentialId");
                removeChildKeys(coreMap, "approvalDelegation", "credentialId");
                // A generate node keeps its config in the GENERIC params map rather than
                // in a named child, so the removals above cannot reach it.
                // `credential_id` there names one of the AUTHOR's own provider keys:
                // handed to a share-link visitor or baked into a marketplace snapshot, it
                // tells an acquirer which key to pin, and an acquirer in the author's
                // organization can resolve it.
                //
                // Read here AS WELL AS in the agents loop below. The node is filed with the
                // AI family from now on, but every plan saved before that - and every
                // snapshot already published - still carries it among the cores, and those
                // are stripped as raw JSON with no parsing, so they stay cloneable.
                // Scrubbing only the new home would leak every existing one.
                //
                // Scoped to the node type that has one: `params` is the generic map EVERY
                // core keeps its config in, so an unconditional removal would silently
                // delete a field of the same name from another node that means something
                // else by it.
                if ("generate".equals(coreMap.get("type"))) {
                    removeChildKeys(coreMap, "params", "credential_id");
                }
            }
        }
        for (String stepBucket : List.of("mcps", "agents")) {
            Object bucket = plan.get(stepBucket);
            if (!(bucket instanceof List<?> steps)) {
                continue;
            }
            for (Object step : steps) {
                if (!(step instanceof Map<?, ?> stepMap)) {
                    continue;
                }
                Map<String, Object> mutableStep = (Map<String, Object>) stepMap;
                mutableStep.remove("selectedCredentialId");
                mutableStep.remove("credentialId");
                mutableStep.remove("platformCredentialId");
                mutableStep.remove("credentialSource");
                // REMOVED here, unlike the publish and clone scrubs which blank it.
                // This redactor only ever runs on a deep COPY served to a share-link
                // visitor (WorkflowControllerHelper.isShareContext); execution reads
                // the stored plan, so nothing here changes what a run does. Blanking
                // would therefore buy no safety and would cost a canvas error on the
                // visitor's screen for a workflow that runs perfectly. The choice is a
                // credential decision that is not the visitor's to see, so it goes.
                // Both spellings: the plan parser reads either, so a plan spelled
                // credential_selector is a live selector and removing only the camel
                // one would show a share-link visitor the author's expression.
                mutableStep.remove("credentialSelector");
                mutableStep.remove("credential_selector");
                // A generate node keeps its config in the GENERIC params map
                // rather than in a named field, so the removals above cannot
                // reach it. `credential_id` there names one of the AUTHOR's own
                // provider keys: handed to a share-link visitor or baked into a
                // marketplace snapshot, it tells an acquirer which key to pin,
                // and an acquirer in the author's organization can resolve it.
                //
                // Scoped to the node type that has one. `params` is a generic
                // map, so removing a key from it unconditionally would silently
                // delete a field of the same name from some other node that
                // means something else by it.
                if ("generate".equals(mutableStep.get("type"))) {
                    removeChildKeys(mutableStep, "params", "credential_id");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeChildKeys(Map<?, ?> coreMap, String childKey, String... keysToRemove) {
        Object node = coreMap.get(childKey);
        if (node instanceof Map<?, ?> childMap) {
            Map<String, Object> mutable = (Map<String, Object>) childMap;
            for (String k : keysToRemove) {
                mutable.remove(k);
            }
        }
    }

}
