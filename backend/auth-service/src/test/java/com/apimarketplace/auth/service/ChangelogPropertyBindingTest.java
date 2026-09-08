package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.ChangelogEntryFirstSeenRepository;
import com.apimarketplace.auth.repository.UserChangelogSeenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the property KEY the kill switch depends on, in every spelling it is actually written in.
 *
 * <p>Every other test in this feature constructs the service with an explicit boolean, so the
 * {@code @Value} key itself had no coverage: renaming {@code changelog.enabled}, or misspelling it
 * in a deploy file, would leave the whole suite green while the switch silently stopped working.
 * That failure is worse than most because the default is {@code true} - the feature fails OPEN, so
 * a broken switch looks exactly like a working one until someone needs it to silence a bad entry.
 */
class ChangelogPropertyBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(UserChangelogSeenRepository.class, () -> mock(UserChangelogSeenRepository.class))
            .withBean(ChangelogEntryFirstSeenRepository.class, () -> mock(ChangelogEntryFirstSeenRepository.class))
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(ChangelogSeenService.class);

    /** Locates a file from the module directory Maven runs the test in. */
    private static Path repoFile(String relative) throws Exception {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new IllegalStateException("could not locate " + relative + " from " + here);
    }

    @Test
    @DisplayName("changelog.enabled=false reaches the service and turns it off")
    void dottedKeyDisablesTheFeature() {
        runner.withPropertyValues("changelog.enabled=false")
                .run(context -> assertThat(context.getBean(ChangelogSeenService.class).isEnabled()).isFalse());
    }

    @Test
    @DisplayName("the switch defaults to ON when nothing sets it")
    void defaultsToEnabled() {
        // Deliberate: a deployment that never heard of this feature still shows the entry. It also
        // means the switch fails open, which is exactly why the spellings below are pinned.
        runner.run(context -> assertThat(context.getBean(ChangelogSeenService.class).isEnabled()).isTrue());
    }

    @Test
    @DisplayName("CHANGELOG_ENABLED, the spelling the deploy files use, reaches the same key")
    void environmentSpellingDisablesTheFeature() {
        // Registered as a SystemEnvironmentPropertySource on purpose: withPropertyValues adds an
        // ordinary source, for which the relaxed uppercase mapping does NOT apply - so that
        // spelling would resolve to nothing and this test would prove the opposite of its name.
        runner.withInitializer(context -> {
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.of("CHANGELOG_ENABLED", "false")));
            // Boot attaches this in a real application; a bare test context does not, and it is
            // what performs the uppercase mapping.
            ConfigurationPropertySources.attach(context.getEnvironment());
        }).run(context -> assertThat(context.getBean(ChangelogSeenService.class).isEnabled()).isFalse());
    }

    @Test
    @DisplayName("both editions wire the env var onto that key in their own config file")
    void bothEditionsDeclareTheSwitch() throws Exception {
        // The chain is env -> yaml -> @Value. The two tests above cover the second half; this one
        // covers the first, which lives in files no Java test would otherwise read. Cloud serves
        // /api/changelog from auth-service, CE from the monolith, so a switch declared in only one
        // of them silently does nothing for the other edition.
        String cloud = Files.readString(repoFile("auth-service/src/main/resources/application.yml"));
        String ce = Files.readString(repoFile("monolith-service/src/main/resources/application-ce.yml"));

        for (String config : new String[]{cloud, ce}) {
            // Matched on the key and the env var, not on the surrounding layout: an exact
            // whitespace match would break the day a sibling key is added under `changelog:`,
            // for no behavioural reason.
            assertThat(config)
                    .as("the deployment must be able to flip this without a rebuild")
                    .containsPattern("(?s)changelog:\\s*enabled: \\$\\{CHANGELOG_ENABLED:true}");
        }
    }

    @Test
    @DisplayName("the prod values file ships the switch, so an operator can flip it in place")
    void prodValuesDeclareTheSwitch() throws Exception {
        // Where the operator actually edits it during an incident. A key that exists only in the
        // service default is not an emergency exit, it is a rebuild.
        assertThat(Files.readString(repoFile("deploy/helm/livecontext/values-prod.yaml")))
                .contains("CHANGELOG_ENABLED:");
    }

    @Test
    @DisplayName("self-hosters can find the switch without reading the compose file")
    void selfHostersGetTheSwitchDocumented() throws Exception {
        // The comparable prior feature (the update check) is documented in both places; a switch
        // discoverable only by reading docker-compose is one nobody knows exists.
        assertThat(Files.readString(repoFile("docker/.env.ce.example"))).contains("CHANGELOG_ENABLED");
        assertThat(Files.readString(repoFile("docker/README-CE.md"))).contains("CHANGELOG_ENABLED");
    }
}
