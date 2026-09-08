-- V480: per-user acknowledgement of the in-app "What's new" entry.
--
-- The changelog itself is NOT stored here. The entry (key, date, media, copy) ships with the
-- build - one entry at a time, the latest, translated in every locale file - so cloud and CE
-- both show exactly the change the build they are running introduces, with no feed to poll and no
-- CDN to reach (an air-gapped install renders the same thing as a cloud tenant).
--
-- What the server owns is the only piece that cannot live in the bundle: WHICH entry each user
-- has already acknowledged, so the announcement is shown once per USER rather than once per
-- browser. One row per user, rewritten in place: there is deliberately no history of past
-- acknowledgements, because there is deliberately no catch-up - a user who missed three entries
-- sees the latest one only, and the archive lives on the public /changelog page.
CREATE TABLE IF NOT EXISTS auth.user_changelog_seen (
    user_id   bigint       PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    entry_key varchar(120) NOT NULL,
    seen_at   timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE auth.user_changelog_seen IS
    'Last in-app changelog entry acknowledged by each user. One row per user, overwritten on each '
    'new entry: no history, because the feature never replays a backlog.';
COMMENT ON COLUMN auth.user_changelog_seen.entry_key IS
    'Key of the acknowledged entry, matching LATEST_CHANGELOG_ENTRY.key in the frontend bundle. '
    'Compared by equality, never by ordering: a downgraded install must not claim its older entry '
    'was already seen.';

-- The FK cascades, like every other user-keyed side table in this schema (user_roles,
-- user_onboarding, email_verification_codes, user_profile). Deleting an account therefore cannot
-- leave the row behind, whichever path performs the deletion.


-- When THIS install first announced a given entry.
--
-- It exists for one decision: an account created after the install started announcing an entry
-- never lacked what that entry describes, so it is acknowledged silently rather than greeted with
-- a panel on its first minute. The entry's own publication date cannot answer that question,
-- because it is written by the developer, not by the install: a self-hosted box that adopts a
-- release six months later would otherwise seal every user who signed up in those six months -
-- they would never see the announcement, and nothing would say why.
--
-- Stamped on first sight rather than at deploy time, so it needs no deploy hook and is correct for
-- both editions: in cloud the first authenticated page load after a rollout sets it (minutes after
-- the deploy), and in CE the first page load after the upgrade does.
CREATE TABLE IF NOT EXISTS auth.changelog_entry_first_seen (
    entry_key     varchar(120) PRIMARY KEY,
    first_seen_at timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE auth.changelog_entry_first_seen IS
    'When this install first served a given in-app changelog entry. Write-once per entry: the row '
    'is what tells a later signup it never lacked the announced change.';
