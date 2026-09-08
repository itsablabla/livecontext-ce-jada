package com.apimarketplace.orchestrator.controllers.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PlanSecretRedactor} - scrubbing raw inline secrets from a workflow plan
 * before it is handed to an anonymous APPLICATION share-link viewer.
 */
@DisplayName("PlanSecretRedactor")
class PlanSecretRedactorTest {

    private Map<String, Object> core(String type, Map<String, Object> body) {
        Map<String, Object> core = new HashMap<>();
        core.put("label", "n1");
        core.put(type, body);
        return core;
    }

    private Map<String, Object> planWith(Map<String, Object>... cores) {
        Map<String, Object> plan = new HashMap<>();
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> c : cores) {
            list.add(c);
        }
        plan.put("cores", list);
        return plan;
    }

    @Test
    @DisplayName("removes httpRequest.authConfig (raw bearer/apiKey/password) but keeps the rest of the node")
    void redactsHttpAuthConfig() {
        Map<String, Object> authConfig = new HashMap<>();
        authConfig.put("bearerToken", "secret-bearer");
        authConfig.put("apiKeyValue", "sk_live_123");
        Map<String, Object> http = new HashMap<>();
        http.put("url", "https://example.com");
        http.put("method", "GET");
        http.put("authConfig", authConfig);
        Map<String, Object> plan = planWith(core("httpRequest", http));

        PlanSecretRedactor.redact(plan);

        Map<String, Object> httpAfter = firstCoreChild(plan, "httpRequest");
        assertThat(httpAfter).doesNotContainKey("authConfig");
        assertThat(httpAfter).containsEntry("url", "https://example.com").containsEntry("method", "GET");
    }

    @Test
    @DisplayName("removes cryptoJwt key/secret/token")
    void redactsCryptoJwt() {
        Map<String, Object> crypto = new HashMap<>();
        crypto.put("key", "raw-key");
        crypto.put("secret", "raw-secret");
        crypto.put("token", "raw-token");
        crypto.put("algorithm", "HS256");
        Map<String, Object> plan = planWith(core("cryptoJwt", crypto));

        PlanSecretRedactor.redact(plan);

        Map<String, Object> after = firstCoreChild(plan, "cryptoJwt");
        assertThat(after).doesNotContainKeys("key", "secret", "token");
        assertThat(after).containsEntry("algorithm", "HS256");
    }

    @Test
    @DisplayName("removes ssh/sftp/database inline password & privateKey (the strip-gap fields)")
    void redactsSshSftpDatabaseInlineSecrets() {
        Map<String, Object> ssh = new HashMap<>(Map.of("host", "h", "password", "p", "privateKey", "pk"));
        Map<String, Object> sftp = new HashMap<>(Map.of("host", "h2", "password", "p2", "privateKey", "pk2"));
        Map<String, Object> db = new HashMap<>(Map.of("host", "h3", "password", "p3"));
        Map<String, Object> plan = planWith(core("ssh", ssh), core("sftp", sftp), core("database", db));

        PlanSecretRedactor.redact(plan);

        assertThat(childOf(plan, 0, "ssh")).doesNotContainKeys("password", "privateKey").containsEntry("host", "h");
        assertThat(childOf(plan, 1, "sftp")).doesNotContainKeys("password", "privateKey").containsEntry("host", "h2");
        assertThat(childOf(plan, 2, "database")).doesNotContainKey("password").containsEntry("host", "h3");
    }

    @Test
    @DisplayName("removes sendEmail inline smtpPassword (raw SMTP secret) and its credentialId")
    void redactsSendEmailSmtpPassword() {
        Map<String, Object> email = new HashMap<>();
        email.put("smtpHost", "smtp.example.com");
        email.put("smtpPassword", "raw-smtp-pw");
        email.put("credentialId", 77);
        Map<String, Object> plan = planWith(core("sendEmail", email));

        PlanSecretRedactor.redact(plan);

        Map<String, Object> after = firstCoreChild(plan, "sendEmail");
        assertThat(after).doesNotContainKeys("smtpPassword", "credentialId");
        assertThat(after).containsEntry("smtpHost", "smtp.example.com");
    }

    @Test
    @DisplayName("removes credential references on mcp/agent step buckets")
    void redactsStepCredentialRefs() {
        Map<String, Object> plan = new HashMap<>();
        Map<String, Object> mcp = new HashMap<>();
        mcp.put("selectedCredentialId", "1");
        mcp.put("platformCredentialId", "2");
        mcp.put("credentialSource", "PLATFORM");
        // A NAME is not a secret, but it is still a credential choice that resolves
        // in the READER scope: the identity lookup is workspace-wide, so an exported
        // plan naming "Acme IG" would authenticate with whichever credential the
        // reader happens to have under that name. Dropped like the ids, so an
        // acquirer states their own account instead of inheriting one by collision.
        mcp.put("credentialSelector", "Acme IG");
        // Both spellings: the plan parser reads either, so leaving the snake one would
        // show a share-link visitor the author's expression.
        mcp.put("credential_selector", "Acme IG");
        mcp.put("toolName", "search");
        plan.put("mcps", new ArrayList<>(List.of(mcp)));

        PlanSecretRedactor.redact(plan);

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((List<?>) plan.get("mcps")).get(0);
        assertThat(after).doesNotContainKeys("selectedCredentialId", "platformCredentialId", "credentialSource", "credentialId");
        // REMOVED here, unlike publish and clone which blank it. This redactor only
        // runs on a deep COPY served to a share-link visitor; execution reads the
        // stored plan, so nothing here changes what a run does. Blanking would buy no
        // safety and would cost the visitor a canvas error on a workflow that runs
        // perfectly, because the blank state is a build-time error by design.
        assertThat(after).doesNotContainKeys("credentialSelector", "credential_selector");
        assertThat(after).containsEntry("toolName", "search");
    }

    @Test
    @DisplayName("removes a generate node's pinned credential id, which lives in the GENERIC params map")
    void redactsGenerateCredentialId() {
        // Every other removal here targets a NAMED child (httpRequest, ssh,
        // sendEmail). A generate node has none: its whole config sits in
        // `params`, so the pinned key rode straight through to the share-link
        // viewer, naming one of the author's own provider credentials.
        Map<String, Object> params = new HashMap<>();
        params.put("model", "seedance-2.0-fast");
        params.put("prompt", "a paper boat");
        params.put("credential_source", "user");
        params.put("credential_id", 42);
        Map<String, Object> generate = core("params", params);
        generate.put("type", "generate");
        // Filed with the AI family, which is where the redactor has to look for
        // it: a scrub that only walked the cores would hand every share-link
        // visitor, and every marketplace acquirer, the id of the author's own
        // provider key.
        Map<String, Object> plan = planWithAgents(generate);

        PlanSecretRedactor.redact(plan);

        Map<String, Object> after = firstAgentChild(plan, "params");
        assertThat(after).doesNotContainKey("credential_id");
        // The pool is not a credential: it says whether the run buys on the
        // platform's key or uses the reader's own, which the reader is entitled
        // to see and which the node needs to keep working.
        assertThat(after)
                .containsEntry("credential_source", "user")
                .containsEntry("model", "seedance-2.0-fast")
                .containsEntry("prompt", "a paper boat");
    }

    /**
     * The bucket every EXISTING generate node is in.
     *
     * <p>The node moved to the AI family, but a plan saved before that move
     * still carries it among the cores, and a share link serves that plan
     * exactly as it was stored. A scrub that only walked the new home would
     * leak the author's key id out of every workflow written until now.
     */
    @Test
    @DisplayName("redacts the pinned key of a generate node still filed under cores, which is where every saved one is")
    void redactsGenerateCredentialIdInTheLegacyCoresBucket() {
        Map<String, Object> params = new HashMap<>();
        params.put("model", "seedance-2.0-fast");
        params.put("credential_source", "user");
        params.put("credential_id", 42);
        Map<String, Object> generate = core("params", params);
        generate.put("type", "generate");
        Map<String, Object> plan = planWith(generate);

        PlanSecretRedactor.redact(plan);

        Map<String, Object> after = firstCoreChild(plan, "params");
        assertThat(after).doesNotContainKey("credential_id");
        assertThat(after)
                .containsEntry("credential_source", "user")
                .containsEntry("model", "seedance-2.0-fast");
    }

    @Test
    @DisplayName("another node type keeps its params untouched: `params` is every core's config, not a secret bucket")
    void leavesOtherCoreParamsAlone() {
        // The removal is scoped to the node that actually pins a provider key.
        // `params` is the generic map EVERY core keeps its config in, so an
        // unconditional removal would silently delete a field of the same name
        // from a future node that means something else by it.
        Map<String, Object> params = new HashMap<>();
        params.put("code", "return 1;");
        params.put("credential_id", 42);
        Map<String, Object> codeNode = core("params", params);
        codeNode.put("type", "code");
        Map<String, Object> plan = planWith(codeNode);

        PlanSecretRedactor.redact(plan);

        assertThat(firstCoreChild(plan, "params")).containsEntry("credential_id", 42);
    }

    /** A plan holding one AI node, in the bucket the node lives in today. */
    private Map<String, Object> planWithAgent(Map<String, Object> agent) {
        Map<String, Object> plan = new HashMap<>();
        List<Object> agents = new ArrayList<>();
        agents.add(agent);
        plan.put("agents", agents);
        return plan;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstAgentParams(Map<String, Object> plan) {
        List<Object> agents = (List<Object>) plan.get("agents");
        return (Map<String, Object>) ((Map<String, Object>) agents.get(0)).get("params");
    }

    @Test
    @DisplayName("redacts the pinned key of a generate node in the agents bucket, where new ones are written")
    void redactsGenerateCredentialIdInTheAgentsBucket() {
        Map<String, Object> params = new HashMap<>();
        params.put("model", "seedance-2.0-fast");
        params.put("credential_source", "user");
        params.put("credential_id", 42);
        Map<String, Object> agent = core("params", params);
        agent.put("type", "generate");

        Map<String, Object> plan = planWithAgent(agent);
        PlanSecretRedactor.redact(plan);

        assertThat(firstAgentParams(plan)).doesNotContainKey("credential_id");
    }

    /**
     * The negative the cores side already had and this side did not.
     *
     * <p>The scrub walks a bucket that holds every AI node and every MCP step,
     * so an unconditional removal here would strip a field of the same name
     * from a node that means something else by it. That is not a leak, which
     * is why nothing would catch it: the share link would simply serve a
     * workflow missing a value, and the reader would see a step configured
     * differently from the one that runs.
     */
    @Test
    @DisplayName("an ordinary AI node in the same bucket keeps its params untouched")
    void leavesOtherAgentParamsAlone() {
        Map<String, Object> params = new HashMap<>();
        params.put("prompt", "summarise this");
        params.put("credential_id", 7);
        Map<String, Object> agent = core("params", params);
        agent.put("type", "agent");

        Map<String, Object> plan = planWithAgent(agent);
        PlanSecretRedactor.redact(plan);

        assertThat(firstAgentParams(plan))
                .as("the removal is scoped to the node that actually pins a provider key")
                .containsEntry("credential_id", 7)
                .containsEntry("prompt", "summarise this");
    }

    @Test
    @DisplayName("null plan and plan without cores are handled without error")
    void handlesNullAndEmpty() {
        PlanSecretRedactor.redact(null);
        Map<String, Object> empty = new HashMap<>();
        PlanSecretRedactor.redact(empty);
        assertThat(empty).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @SafeVarargs
    private Map<String, Object> planWithAgents(Map<String, Object>... agents) {
        Map<String, Object> plan = new HashMap<>();
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> a : agents) {
            list.add(a);
        }
        plan.put("agents", list);
        return plan;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstAgentChild(Map<String, Object> plan, String child) {
        List<?> agents = (List<?>) plan.get("agents");
        return (Map<String, Object>) ((Map<String, Object>) agents.get(0)).get(child);
    }

    private Map<String, Object> firstCoreChild(Map<String, Object> plan, String child) {
        List<?> cores = (List<?>) plan.get("cores");
        return (Map<String, Object>) ((Map<String, Object>) cores.get(0)).get(child);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childOf(Map<String, Object> plan, int coreIndex, String child) {
        List<?> cores = (List<?>) plan.get("cores");
        return (Map<String, Object>) ((Map<String, Object>) cores.get(coreIndex)).get(child);
    }
}
