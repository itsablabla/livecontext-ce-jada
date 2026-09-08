-- Mark the applications already published to the marketplace that belong in the Studio.
--
-- The rule applied, and it is deliberately narrow: an application is a studio app when it PRODUCES
-- a media asset - an image, a video, an audio or voice track, a piece of music. Finding media,
-- publishing media somebody else supplied, or reading media is not producing it. That rule is what
-- keeps the Studio shelf worth opening: a marketplace view that also listed every app rendering a
-- thumbnail in its search results would be the marketplace again, under another name.
--
-- What each of the seven does, so the choice can be argued with rather than taken on trust:
--   xAI Video Sequence        expands a theme into a shot list, generates a first image, then a clip
--                             per shot, each starting on the previous last frame
--   Jelly Merge Studio        records an 18s vertical MP4 into Files
--   Blade Duel Studio         records a duel to an MP4 in Files
--   Polymarket Pulse          renders a market timelapse and a making-of clip with narration
--   AI Image Comparator       two models generate an image each, shown side by side
--   AI Visual Face-Off        four models each return an animated visual simulation, rendered together
--   Audio/Video Transcriber & Subtitle Studio
--                             transcribes a clip and produces its timestamped subtitles
--
-- The last two are the judgement calls. Face-Off produces animated SVG rather than a media FILE, and
-- the Transcriber transforms media it is given rather than inventing any. Both were included because
-- what the reader goes there to do is make something to look at or to lay over a video; both are one
-- UPDATE away from being excluded if that reads wrong.
--
-- Deliberately excluded, and worth naming because they are the near misses: Instagram Publisher and
-- Facebook Publisher (publish media the reader supplies), AI Viral Video Radar (finds videos),
-- Gallery Wall and Needle Drop (find artworks and music), Invoice PDF to Telegram (renders a PDF,
-- which is not a media asset), Interface Screenshot to Telegram (produces an image, but as plumbing
-- rather than as the thing the reader came for).
--
-- SAFETY: each id is paired with the TITLE it is claimed to be, so the two have to agree for a row
-- to change. An id that has been reused, or that names a different publication than the comment
-- beside it says, updates NOTHING rather than marking a stranger's application as a studio app - a
-- mistake nobody would see, on somebody else's listing. An install that does not host these
-- publications - every self-hosted one - matches nothing and this is a no-op. Idempotent: re-running sets the same rows
-- to the same value. It never sets a row back to false, so a later decision by the publisher, or by
-- an administrator, is not undone by a re-run.

UPDATE publication.workflow_publications
   SET studio = TRUE
 WHERE (id, title) IN (
        ('a58172e2-68c7-4a1b-8994-9ee1ff8c30d1'::uuid, 'xAI Video Sequence'),
        ('d14351ba-aa1d-4a97-a600-59194d44a2b3'::uuid, 'Jelly Merge Studio'),
        ('68271f10-9514-4cf3-82ba-f7b36ed63d83'::uuid, 'Blade Duel Studio'),
        ('058d09ed-6357-48af-b2db-3cc1eeac1b6e'::uuid, 'AI Visual Face-Off'),
        ('49921239-6e01-470e-8559-b9a260ed426d'::uuid, 'Audio/Video Transcriber & Subtitle Studio'),
        ('a8f56dfe-84c4-4912-ac8d-a3c6937f46e1'::uuid, 'Polymarket Pulse'),
        ('9078c1f0-f063-466b-9176-b91f980632c1'::uuid, 'AI Image Comparator')
       )
   AND studio = FALSE;
