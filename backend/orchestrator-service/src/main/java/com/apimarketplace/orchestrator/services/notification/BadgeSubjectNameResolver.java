package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for badge unlock notifications.
 *
 * <p>Reads {@code payload.subjectName}, which the badge emitter fills with the
 * badge CODE rather than a label: the frontend owns the translated trophy name
 * for that code, so the bell row reads in the user's language instead of
 * whatever locale the server happened to run in when the badge unlocked.
 */
@Component
public class BadgeSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.BADGE;
    }
}
