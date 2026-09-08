package com.apimarketplace.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two properties the public bundle download depends on, both of which fail
 * SILENTLY if a merge ever drops them:
 *
 * <ul>
 *   <li>{@code spring.mvc.async.request-timeout} - the download returns a
 *       {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody},
 *       so the container's async deadline applies. Tomcat defaults to 30s and
 *       real CE polls were measured at 198s and 252s: losing this property does
 *       not raise an error, it truncates the body and the client reports a
 *       network failure.</li>
 *   <li>{@code spring.jpa.open-in-view} - left at Spring's default of true, a
 *       query outside a transaction would hold a pooled connection for the whole
 *       multi-minute write.</li>
 * </ul>
 *
 * <p>Neither is observable from a unit test of the endpoint itself, so they are
 * pinned here against the shipped configuration file.
 */
@DisplayName("catalog-service serving properties the bundle download depends on")
class ApiCatalogBundleServingPropertiesTest {

    private static Properties applicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertThat(props).as("application.yml must be on the test classpath").isNotNull();
        return props;
    }

    @Test
    @DisplayName("The async request timeout is set well above the slowest observed download")
    void asyncRequestTimeoutIsSet() {
        String value = applicationYml().getProperty("spring.mvc.async.request-timeout");

        assertThat(value)
                .as("spring.mvc.async.request-timeout must stay set: without it Tomcat's 30s "
                        + "default truncates a bundle download that legitimately takes minutes")
                .isNotNull();
        assertThat(Long.parseLong(value))
                .as("must leave room above the 252s worst case observed in production")
                .isGreaterThanOrEqualTo(600_000L);
    }

    @Test
    @DisplayName("Open-in-view stays off so a slow response can never pin a pooled connection")
    void openInViewIsDisabled() {
        assertThat(applicationYml().getProperty("spring.jpa.open-in-view"))
                .as("spring.jpa.open-in-view must stay false")
                .isEqualTo("false");
    }
}
