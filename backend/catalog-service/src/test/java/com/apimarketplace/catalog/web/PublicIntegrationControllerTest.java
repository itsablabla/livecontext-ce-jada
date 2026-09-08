package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.dto.PublicIntegrationDTO;
import com.apimarketplace.catalog.dto.PublicIntegrationDetailDTO;
import com.apimarketplace.catalog.dto.PublicIntegrationToolDTO;
import com.apimarketplace.catalog.service.PublicIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicIntegrationController")
class PublicIntegrationControllerTest {

    @Mock
    private PublicIntegrationService publicIntegrationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new PublicIntegrationController(publicIntegrationService))
            .build();
    }

    private static PublicIntegrationDTO integration(String slug, String name) {
        // bearer_token, not "bearer": the value the catalog really stores.
        return new PublicIntegrationDTO(slug, name, name + " does things", slug, null, 12, "bearer_token");
    }

    @Test
    @DisplayName("serves the ranked page with the shared page envelope")
    void listReturnsPage() throws Exception {
        when(publicIntegrationService.list(0, 60, null))
            .thenReturn(List.of(integration("slack", "Slack"), integration("github", "GitHub")));
        when(publicIntegrationService.count(null)).thenReturn(731);

        mockMvc.perform(get("/api/public/integrations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.content[0].slug").value("slack"))
            .andExpect(jsonPath("$.content[0].toolCount").value(12))
            .andExpect(jsonPath("$.totalElements").value(731))
            .andExpect(jsonPath("$.totalPages").value(13))
            .andExpect(jsonPath("$.first").value(true))
            .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    @DisplayName("passes the search term through to the service")
    void listForwardsQuery() throws Exception {
        when(publicIntegrationService.list(0, 60, "slack")).thenReturn(List.of(integration("slack", "Slack")));
        when(publicIntegrationService.count("slack")).thenReturn(1);

        mockMvc.perform(get("/api/public/integrations").param("q", "slack"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].slug").value("slack"))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("reports the clamped page size, not the one that was asked for")
    void listReportsClampedSize() throws Exception {
        when(publicIntegrationService.list(anyInt(), anyInt(), any())).thenReturn(List.of());
        when(publicIntegrationService.count(any())).thenReturn(0);

        // Otherwise a caller asking for size=5000 is told its page holds 5000 entries
        // while the service capped the query at 200, and its pager is wrong forever.
        mockMvc.perform(get("/api/public/integrations").param("size", "5000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.size").value(200));
    }

    @Test
    @DisplayName("degrades to an empty page instead of 500 when the read fails")
    void listDegradesOnFailure() throws Exception {
        when(publicIntegrationService.list(anyInt(), anyInt(), any()))
            .thenThrow(new IllegalStateException("catalog unreachable"));

        // A marketing section must lose itself, never the page it sits on.
        mockMvc.perform(get("/api/public/integrations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(0)))
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("serves one integration with its endpoints")
    void bySlugReturnsDetail() throws Exception {
        when(publicIntegrationService.findBySlug("slack")).thenReturn(Optional.of(
            new PublicIntegrationDetailDTO(
                integration("slack", "Slack"),
                "https://api.slack.com",
                List.of(new PublicIntegrationToolDTO("send_message", "Post a message", "POST")),
                false)));

        mockMvc.perform(get("/api/public/integrations/slack"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.integration.name").value("Slack"))
            .andExpect(jsonPath("$.documentation").value("https://api.slack.com"))
            .andExpect(jsonPath("$.tools[0].name").value("send_message"))
            .andExpect(jsonPath("$.toolsTruncated").value(false));
    }

    @Test
    @DisplayName("404s an unknown or non-public slug")
    void bySlugNotFound() throws Exception {
        when(publicIntegrationService.findBySlug("someones-private-api")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/public/integrations/someones-private-api"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("500s a failed read rather than 404, so a blip does not deindex the page")
    void bySlugFailureIsNotANotFound() throws Exception {
        when(publicIntegrationService.findBySlug(eq("slack")))
            .thenThrow(new IllegalStateException("catalog unreachable"));

        // A 404 here would invite search engines to drop a page that still exists;
        // 500 is retried. This is the whole reason the two cases are distinguished.
        mockMvc.perform(get("/api/public/integrations/slack"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("degrades to an empty page when the COUNT is what fails")
    void listDegradesWhenTheCountFails() throws Exception {
        when(publicIntegrationService.list(anyInt(), anyInt(), any())).thenReturn(List.of());
        when(publicIntegrationService.count(any()))
            .thenThrow(new IllegalStateException("catalog unreachable"));

        // Two reads back the response; a test that only breaks the first proves the
        // guard for half of them.
        mockMvc.perform(get("/api/public/integrations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("answers 405 to every verb but GET, which is what makes the allow-list safe")
    void refusesEveryWriteVerb() throws Exception {
        // The gateway's public allow-list matches on PATH, not verb, so a route placed
        // on it is reachable by any method with no JWT. The entire security argument
        // for allow-listing this prefix is that the controller declares GET mappings
        // only; without this test, adding a @PostMapping here would silently open an
        // unauthenticated write and nothing would fail.
        mockMvc.perform(post("/api/public/integrations")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/public/integrations")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/public/integrations")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/public/integrations/slack")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/public/integrations/slack")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/public/integrations/slack")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/public/integrations/slack")).andExpect(status().isMethodNotAllowed());
    }
}
