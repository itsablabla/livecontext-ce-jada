package com.apimarketplace.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The two identity facts the badge evaluator needs from auth-service.
 *
 * <p>Returned by {@code GET /api/internal/auth/users/{userId}/badge-profile}.
 * Kept separate from {@link PublisherProfileDto} because that one is frozen
 * onto publication rows at publish time and has no business carrying join
 * dates.
 *
 * @param userId      echoed back, numeric auth user id as a string
 * @param joinedAt    ISO-8601 local date-time in UTC ({@code users.created_at}),
 *                    null when the row has no creation timestamp
 * @param pageVisible false only when the owner set their profile to PRIVATE;
 *                    the public badge endpoint refuses to answer for those
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BadgeProfileDto(
        String userId,
        String joinedAt,
        boolean pageVisible
) {}
