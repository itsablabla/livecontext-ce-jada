package com.apimarketplace.catalog.dto;

/**
 * One integration as the PUBLIC (anonymous, crawlable) pages render it.
 *
 * <p>Deliberately narrower than {@link WorkflowApiDTO}: this record is what an
 * unauthenticated visitor receives, so it carries only what a marketing page shows.
 * Notably absent, and absent on purpose:
 *
 * <ul>
 *   <li><b>The run count.</b> The list is ORDERED by how much the platform runs each
 *       integration, but the number itself is never published: it is internal traffic
 *       data, and a visible "3 runs" next to an integration reads as a verdict on the
 *       integration rather than on our adoption. Same call the add-node palette made.</li>
 *   <li><b>Anything tenant-scoped</b> (credentials, ownership, base URL, auth header
 *       names). A public read must not depend on, or leak, a user context.</li>
 * </ul>
 *
 * @param slug        {@code apis.api_slug}, the URL segment of /integrations/{slug}
 * @param name        display name, e.g. "Slack"
 * @param description one-line summary of what the integration does
 * @param iconSlug    key into /icons/services/{iconSlug}.svg, never null (falls back to "mcp")
 * @param iconUrl     explicit icon URL when the API declares one, else null
 * @param toolCount   how many active endpoints the integration exposes as tools
 * @param authType    how it authenticates. The values the catalog actually stores, counted
 *                    from the seeds: api_key, bearer_token, oauth2, basic_auth, custom, none.
 *                    NOT "bearer"/"basic", which occur nowhere - the frontend badge was
 *                    written against those and silently rendered nothing for 40% of the
 *                    catalog.
 */
public record PublicIntegrationDTO(
    String slug,
    String name,
    String description,
    String iconSlug,
    String iconUrl,
    int toolCount,
    String authType
) {}
