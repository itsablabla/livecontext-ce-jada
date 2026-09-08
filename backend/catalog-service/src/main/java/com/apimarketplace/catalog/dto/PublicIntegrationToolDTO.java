package com.apimarketplace.catalog.dto;

/**
 * One endpoint of an integration, as the public integration page lists it.
 *
 * <p>This is the substance of an /integrations/{slug} page: the real operations the
 * catalog exposes, read straight from {@code catalog.api_tools}, not prose written
 * about them. Name and description are the ones an agent sees when it picks the tool,
 * so the public page and the product can never describe different things.
 *
 * @param name        the operation, e.g. "send_message"
 * @param description what it does, one line
 * @param method      HTTP verb of the underlying call, or null when the tool declares none
 */
public record PublicIntegrationToolDTO(
    String name,
    String description,
    String method
) {}
