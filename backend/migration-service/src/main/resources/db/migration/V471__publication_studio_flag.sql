-- Studio is a SECOND AXIS on a publication, not another category.
--
-- It was first modelled as a marketplace category, and that was wrong: `category_slug` is
-- single-valued, so an application marked studio would have LOST its real category. A video studio
-- is a Content app and a studio app at the same time, and the marketplace has to keep being able to
-- say the first while the studio surface asks the second.
--
-- The flag is a decision the PUBLISHER makes about their own application. It is deliberately not
-- derived from the snapshot: "the workflow contains a generation step" admits an app that merely
-- resizes an upload, and "the interface renders a video" admits every app that lists search results
-- with thumbnails - wrong in both directions, and silently.
--
-- SAFETY: additive, NOT NULL with a default, so every existing row keeps its category and is simply
-- not a studio app until somebody says otherwise. No existing read changes meaning.

ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS studio BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN publication.workflow_publications.studio IS
    'True when this publication belongs in the Studio: it PRODUCES a media asset (image, video, audio, voice, music) rather than finding, publishing or reading one. Independent of category_slug, which keeps saying what the application is about.';
