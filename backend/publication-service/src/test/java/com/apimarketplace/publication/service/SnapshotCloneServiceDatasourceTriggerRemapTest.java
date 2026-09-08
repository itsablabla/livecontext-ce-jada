package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link SnapshotCloneService#remapDatasourceTriggerIds(Map, Map)}.
 *
 * <p>Background: {@code triggers[type=datasource].id} is the numeric primary key
 * of the source DataSource. Without this remap, an acquired application's
 * datasource trigger points at the source tenant's datasource (404 inert on
 * fire). The helper mirrors the existing {@code tableNode.dataSourceId} and
 * agent {@code dataSourceId} remaps via the {@code dsMapping}
 * old-numeric-id → new-numeric-id.
 */
@DisplayName("SnapshotCloneService - datasource trigger id remap")
class SnapshotCloneServiceDatasourceTriggerRemapTest {

    private SnapshotCloneService service;
    private Method remapDatasourceTriggerIds;
    private Method remapTriggerWorkflowIds;
    private Method stripSensitiveCredentials;

    @BeforeEach
    void setUp() throws Exception {
        // No collaborators are touched by remapDatasourceTriggerIds - pure
        // map mutation. Mocks are placeholders to satisfy the constructor.
        service = new SnapshotCloneService(
                mock(OrchestratorInternalClient.class),
                mock(AgentClient.class),
                mock(InterfaceClient.class),
                mock(DataSourceClient.class),
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class)
        );
        remapDatasourceTriggerIds = SnapshotCloneService.class.getDeclaredMethod(
                "remapDatasourceTriggerIds", Map.class, Map.class);
        remapDatasourceTriggerIds.setAccessible(true);
        remapTriggerWorkflowIds = SnapshotCloneService.class.getDeclaredMethod(
                "remapTriggerWorkflowIds", Map.class, Map.class);
        remapTriggerWorkflowIds.setAccessible(true);
        stripSensitiveCredentials = SnapshotCloneService.class.getDeclaredMethod(
                "stripSensitiveCredentials", Map.class);
        stripSensitiveCredentials.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private void invoke(Map<String, Object> plan, Map<String, String> dsMapping) throws Exception {
        remapDatasourceTriggerIds.invoke(service, plan, dsMapping);
    }

    private void invokeStrip(Map<String, Object> plan) throws Exception {
        stripSensitiveCredentials.invoke(service, plan);
    }

    private void invokeWorkflowRemap(Map<String, Object> plan, Map<String, String> mapping) throws Exception {
        remapTriggerWorkflowIds.invoke(service, plan, mapping);
    }

    private Map<String, Object> trigger(String type, String id) {
        Map<String, Object> t = new LinkedHashMap<>();
        if (type != null) t.put("type", type);
        if (id != null) t.put("id", id);
        return t;
    }

    private Map<String, Object> planWith(Map<String, Object>... triggers) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new java.util.ArrayList<>(List.of(triggers)));
        return plan;
    }

    @Test
    @DisplayName("Acquire-time credential scrub removes selectedCredentialId from MCP steps")
    void acquireTimeCredentialScrubRemovesSelectedCredentialIdFromMcpSteps() throws Exception {
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("id", "mcp:echo");
        mcp.put("selectedCredentialId", 42);
        mcp.put("credentialId", 43);
        mcp.put("platformCredentialId", 44);
        mcp.put("credentialSource", "user");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mcps", new java.util.ArrayList<>(List.of(mcp)));

        invokeStrip(plan);

        assertThat(mcp).doesNotContainKeys(
                "selectedCredentialId",
                "credentialId",
                "platformCredentialId",
                "credentialSource");
    }

    @Test
    @DisplayName("Acquire-time credential scrub strips httpRequest.authConfig, emailInbox/sendEmail credentialId, and cryptoJwt key/secret/token from core nodes")
    @SuppressWarnings("unchecked")
    void acquireTimeCredentialScrubRemovesCoreNodeSecrets() throws Exception {
        Map<String, Object> httpRequest = new LinkedHashMap<>();
        httpRequest.put("url", "https://example.com");
        httpRequest.put("authConfig", new LinkedHashMap<>(Map.of("type", "bearer", "token", "secret")));

        Map<String, Object> sendEmail = new LinkedHashMap<>();
        sendEmail.put("to", "user@example.com");
        sendEmail.put("credentialId", 77);

        Map<String, Object> emailInbox = new LinkedHashMap<>();
        emailInbox.put("folder", "INBOX");
        emailInbox.put("credentialId", 88);

        Map<String, Object> cryptoJwt = new LinkedHashMap<>();
        cryptoJwt.put("algorithm", "HS256");
        cryptoJwt.put("key", "private-key");
        cryptoJwt.put("secret", "shared-secret");
        cryptoJwt.put("token", "signed-token");

        Map<String, Object> core = new LinkedHashMap<>();
        core.put("httpRequest", httpRequest);
        core.put("sendEmail", sendEmail);
        core.put("emailInbox", emailInbox);
        core.put("cryptoJwt", cryptoJwt);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", new java.util.ArrayList<>(List.of(core)));

        invokeStrip(plan);

        // HTTP auth secret is gone but the non-secret url survives.
        assertThat(httpRequest).doesNotContainKey("authConfig");
        assertThat(httpRequest.get("url")).isEqualTo("https://example.com");
        // Both email node kinds lose only their credentialId back-reference.
        assertThat(sendEmail).doesNotContainKey("credentialId");
        assertThat(sendEmail.get("to")).isEqualTo("user@example.com");
        assertThat(emailInbox).doesNotContainKey("credentialId");
        assertThat(emailInbox.get("folder")).isEqualTo("INBOX");
        // All three crypto secret fields are stripped, the algorithm metadata stays.
        assertThat(cryptoJwt).doesNotContainKeys("key", "secret", "token");
        assertThat(cryptoJwt.get("algorithm")).isEqualTo("HS256");
    }

    @Test
    @DisplayName("Acquire-time credential scrub strips a generate node's pinned credential id from the GENERIC params map")
    @SuppressWarnings("unchecked")
    void acquireTimeCredentialScrubRemovesGeneratePinnedCredential() throws Exception {
        // Everything scrubbed above lives under a NAMED child. A generate node
        // has none: its whole config is `params`, so the publisher's pinned key
        // was copied verbatim into every acquirer's plan - a dangling id at
        // best, and one an acquirer inside the publisher's organization can
        // actually resolve at worst.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", "seedance-2.0-fast");
        params.put("prompt", "a paper boat");
        params.put("credential_source", "user");
        params.put("credential_id", 42);

        // Filed with the AI family, which is where the scrub has to look for it:
        // generate is keyed `agent:` and travels in `agents`, so a sweep that
        // only walked the cores would copy the publisher's key id into every
        // acquirer's plan and never touch it.
        Map<String, Object> generate = new LinkedHashMap<>();
        generate.put("type", "generate");
        generate.put("params", params);

        // A different node type keeps its params: `params` is the generic
        // config map EVERY core uses, so an unconditional removal would delete
        // a same-named field from a node that means something else by it.
        Map<String, Object> codeParams = new LinkedHashMap<>();
        codeParams.put("credential_id", 7);
        Map<String, Object> codeNode = new LinkedHashMap<>();
        codeNode.put("type", "code");
        codeNode.put("params", codeParams);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("agents", new java.util.ArrayList<>(List.of(generate)));
        plan.put("cores", new java.util.ArrayList<>(List.of(codeNode)));

        invokeStrip(plan);

        assertThat(params).doesNotContainKey("credential_id");
        assertThat(codeParams).containsEntry("credential_id", 7);
        // The pool stays: it names an arrangement, not a credential, and the
        // acquired node has to keep running the way it was published.
        assertThat(params.get("credential_source")).isEqualTo("user");
        assertThat(params.get("model")).isEqualTo("seedance-2.0-fast");
    }

    /**
     * The bucket every ALREADY PUBLISHED snapshot is in.
     *
     * <p>The node moved to the AI family, but a snapshot published before that
     * move still carries it among the cores and is still cloneable: this strip
     * walks raw JSON and parses nothing, so nothing rejects the old shape. A
     * scrub that only walked the new home would hand the publisher's key id to
     * every acquirer of every workflow published until now.
     */
    @Test
    @DisplayName("Acquire-time credential scrub also strips a generate node still filed under cores, which is where every published one is")
    @SuppressWarnings("unchecked")
    void acquireTimeCredentialScrubRemovesGeneratePinnedCredentialInLegacyCoresBucket() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", "seedance-2.0-fast");
        params.put("credential_source", "user");
        params.put("credential_id", 42);

        Map<String, Object> generate = new LinkedHashMap<>();
        generate.put("type", "generate");
        generate.put("params", params);

        Map<String, Object> codeParams = new LinkedHashMap<>();
        codeParams.put("credential_id", 7);
        Map<String, Object> codeNode = new LinkedHashMap<>();
        codeNode.put("type", "code");
        codeNode.put("params", codeParams);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", new java.util.ArrayList<>(List.of(generate, codeNode)));

        invokeStrip(plan);

        assertThat(params).doesNotContainKey("credential_id");
        assertThat(codeParams).containsEntry("credential_id", 7);
        assertThat(params.get("credential_source")).isEqualTo("user");
    }

    @Test
    @DisplayName("remapTriggerWorkflowIds remaps an 'error' trigger's workflow id via the sub-workflow mapping (the error counterpart of the workflow trigger)")
    void remapsErrorTriggerWorkflowIdViaMapping() throws Exception {
        Map<String, Object> errorTrigger = trigger("error", "old-root-wf-id");
        Map<String, Object> plan = planWith(errorTrigger);
        Map<String, String> mapping = Map.of("old-root-wf-id", "new-cloned-wf-id");

        invokeWorkflowRemap(plan, mapping);

        // The 'error' type is remapped exactly like 'workflow' - without this an acquired
        // application's error trigger points at the source tenant's workflow id.
        assertThat(errorTrigger.get("id")).isEqualTo("new-cloned-wf-id");
    }

    @Test
    @DisplayName("remapTriggerWorkflowIds leaves an 'error' trigger id untouched when the workflow id has no mapping entry (fail-soft)")
    void errorTriggerWithUnmappedWorkflowId_keptAsIs() throws Exception {
        Map<String, Object> errorTrigger = trigger("error", "orphan-wf-id");
        Map<String, Object> plan = planWith(errorTrigger);
        Map<String, String> mapping = Map.of("some-other-wf", "new-wf");

        invokeWorkflowRemap(plan, mapping);

        assertThat(errorTrigger.get("id")).isEqualTo("orphan-wf-id");
    }

    @Test
    @DisplayName("Happy path: remaps triggers[type=datasource].id from old PK to new PK via dsMapping")
    void remapsDatasourceTriggerIdViaDsMapping_happyPath() throws Exception {
        Map<String, Object> trig = trigger("datasource", "42");
        Map<String, Object> plan = planWith(trig);
        Map<String, String> dsMapping = Map.of("42", "99");

        invoke(plan, dsMapping);

        assertThat(trig.get("id")).isEqualTo("99");
    }

    @Test
    @DisplayName("Unmapped datasource trigger id is kept as-is and a warn is logged (orphan trigger surfaces at fire time)")
    void datasourceTriggerWithUnmappedDs_keepsOldId() throws Exception {
        Map<String, Object> trig = trigger("datasource", "42");
        Map<String, Object> plan = planWith(trig);
        Map<String, String> dsMapping = Map.of("17", "23"); // does not contain 42

        invoke(plan, dsMapping);

        assertThat(trig.get("id")).isEqualTo("42");
    }

    @Test
    @DisplayName("Non-datasource triggers are not remapped (chat/form/webhook/manual/schedule scope guard)")
    void nonDatasourceTriggers_unchanged() throws Exception {
        Map<String, Object> chat = trigger("chat", "trigger-uuid-abc");
        Map<String, Object> form = trigger("form", "trigger-uuid-def");
        Map<String, Object> webhook = trigger("webhook", "trigger-uuid-ghi");
        Map<String, Object> manual = trigger("manual", "trigger-uuid-jkl");
        Map<String, Object> schedule = trigger("schedule", "trigger-uuid-mno");
        Map<String, Object> plan = planWith(chat, form, webhook, manual, schedule);
        Map<String, String> dsMapping = Map.of("trigger-uuid-abc", "should-not-be-applied");

        invoke(plan, dsMapping);

        assertThat(chat.get("id")).isEqualTo("trigger-uuid-abc");
        assertThat(form.get("id")).isEqualTo("trigger-uuid-def");
        assertThat(webhook.get("id")).isEqualTo("trigger-uuid-ghi");
        assertThat(manual.get("id")).isEqualTo("trigger-uuid-jkl");
        assertThat(schedule.get("id")).isEqualTo("trigger-uuid-mno");
    }

    @Test
    @DisplayName("Empty dsMapping short-circuits without touching triggers (perf early-return)")
    void emptyDsMapping_skipsRemap() throws Exception {
        Map<String, Object> trig = trigger("datasource", "42");
        Map<String, Object> plan = planWith(trig);

        invoke(plan, new HashMap<>());

        assertThat(trig.get("id")).isEqualTo("42");
    }

    @Test
    @DisplayName("Null dsMapping short-circuits without NPE (defensive guard)")
    void nullDsMapping_skipsRemap() throws Exception {
        Map<String, Object> trig = trigger("datasource", "42");
        Map<String, Object> plan = planWith(trig);

        invoke(plan, null);

        assertThat(trig.get("id")).isEqualTo("42");
    }

    @Test
    @DisplayName("Type field comparison is case-insensitive (Datasource / DATASOURCE)")
    void caseInsensitiveType() throws Exception {
        Map<String, Object> capCase = trigger("Datasource", "42");
        Map<String, Object> upper = trigger("DATASOURCE", "43");
        Map<String, Object> plan = planWith(capCase, upper);
        Map<String, String> dsMapping = Map.of("42", "99", "43", "100");

        invoke(plan, dsMapping);

        assertThat(capCase.get("id")).isEqualTo("99");
        assertThat(upper.get("id")).isEqualTo("100");
    }

    @Test
    @DisplayName("Trigger with missing type field is skipped (no NPE on null type)")
    void triggerWithMissingTypeField_skipped() throws Exception {
        Map<String, Object> noType = trigger(null, "42"); // no type field at all
        Map<String, Object> plan = planWith(noType);
        Map<String, String> dsMapping = Map.of("42", "99");

        invoke(plan, dsMapping);

        // id untouched - entry doesn't qualify as a datasource trigger
        assertThat(noType.get("id")).isEqualTo("42");
        assertThat(noType).doesNotContainKey("type");
    }

    @Test
    @DisplayName("Trigger with non-numeric source id (legacy/corrupted data) keeps the value unchanged when no mapping matches")
    void nonNumericSourceTriggerId_keptAndWarned() throws Exception {
        // Pin the contract: helper does string-keyed lookup against dsMapping.
        // If a future refactor switches to Long-keyed mapping this test must
        // be revisited - non-numeric ids would silently match nothing only
        // because the keys are strings today.
        Map<String, Object> trig = trigger("datasource", "abc");
        Map<String, Object> plan = planWith(trig);
        Map<String, String> dsMapping = Map.of("42", "99");

        invoke(plan, dsMapping);

        assertThat(trig.get("id")).isEqualTo("abc");
    }

    @Test
    @DisplayName("Trigger with null id is skipped without NPE")
    void triggerWithNullId_skipped() throws Exception {
        Map<String, Object> trig = trigger("datasource", null); // id missing
        Map<String, Object> plan = planWith(trig);
        Map<String, String> dsMapping = Map.of("42", "99");

        invoke(plan, dsMapping);

        assertThat(trig).doesNotContainKey("id");
    }

    @Test
    @DisplayName("Plan with no triggers field is a no-op (defensive)")
    void planWithoutTriggersField_noop() throws Exception {
        Map<String, Object> plan = new LinkedHashMap<>();
        Map<String, String> dsMapping = Map.of("42", "99");

        invoke(plan, dsMapping);

        assertThat(plan).doesNotContainKey("triggers");
    }

    @Test
    @DisplayName("a cloned step keeps its run-time account MODE while losing the publisher's choice")
    void cloneBlanksTheAccountSelector() throws Exception {
        // Removing it instead would drop the step back to the ACQUIRER default account
        // and every run would be green: this feature's own headline failure, relocated
        // to the clone boundary. Blank keeps the step saying it chooses at run time,
        // so the acquirer gets a loud failure until they write their own expression.
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "m1");
        step.put("label", "Publish");
        step.put("selectedCredentialId", 42);
        step.put("credentialSelector", "Acme IG");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mcps", new java.util.ArrayList<>(java.util.List.of(step)));

        invokeStrip(plan);

        assertThat(step).doesNotContainKey("selectedCredentialId");
        assertThat(step).containsEntry("credentialSelector", "");
    }

    @Test
    @DisplayName("a cloned step that never chose at run time gains no selector")
    void cloneAddsNoSelectorToAStaticStep() throws Exception {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "m1");
        step.put("selectedCredentialId", 42);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mcps", new java.util.ArrayList<>(java.util.List.of(step)));

        invokeStrip(plan);

        assertThat(step).doesNotContainKey("credentialSelector");
    }

    @Test
    @DisplayName("an explicit null selector is left alone, not turned into a blank one")
    void explicitNullIsNotBlanked() throws Exception {
        // A plan can carry an explicit null here: set_plan imports a raw plan without
        // going through the merge that reads null as a delete. Blanking that would put
        // the step into the dynamic-but-unfilled state for a feature its author never
        // used, so the acquired app would refuse on every run and validate would report
        // a missing field, over a key that meant "no selector at all".
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "m1");
        step.put("credentialSelector", null);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mcps", new java.util.ArrayList<>(java.util.List.of(step)));

        invokeStrip(plan);

        assertThat(step.get("credentialSelector")).isNull();
    }
}
