package com.apimarketplace.auth.client.purge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code auth.purge_log}, as served by
 * {@code GET /api/internal/auth/purges}.
 *
 * @param seq         monotonic position in the log; followers store the last one they
 *                    finished
 * @param subjectType {@link #ORG} for a workspace (organization id), {@link #USER} for an
 *                    account (internal user id, as a string)
 * @param subjectId   the id of that subject, as the journal tables carry it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PurgeRecord(long seq, String subjectType, String subjectId) {

    public static final String ORG = "ORG";
    public static final String USER = "USER";

    public boolean isOrganization() {
        return ORG.equals(subjectType);
    }

    public boolean isUser() {
        return USER.equals(subjectType);
    }
}
