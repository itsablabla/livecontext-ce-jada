package com.apimarketplace.catalog.dto;

import java.util.List;

/**
 * One integration plus its endpoint list, for the public /integrations/{slug} page.
 *
 * @param integration   the summary, identical to the one the directory lists
 * @param documentation the provider's own documentation URL, or null
 * @param tools         active endpoints, alphabetical, capped by the service
 * @param toolsTruncated true when {@code tools} is a prefix of a longer list, so the
 *                       page can say so instead of implying the integration stops there
 */
public record PublicIntegrationDetailDTO(
    PublicIntegrationDTO integration,
    String documentation,
    List<PublicIntegrationToolDTO> tools,
    boolean toolsTruncated
) {}
