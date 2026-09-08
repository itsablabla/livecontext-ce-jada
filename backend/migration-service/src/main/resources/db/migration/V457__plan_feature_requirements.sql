-- ============================================================================
-- V457: Per-plan availability for workflow nodes and catalog ENDPOINTS.
--
-- One flat table of "feature key -> minimum plan", edited at runtime by a
-- platform admin. A key with no row is available on every plan, so the table
-- stays small: it lists the exceptions, not the catalogue.
--
-- Key namespaces (the only three the runtime resolves):
--   node:<node_type>   one workflow node type, matching
--                      orchestrator.node_type_documentation.type (e.g. node:media)
--   tool:<tool_slug>   ONE catalog endpoint
--   api:<api_slug>     every endpoint of one catalog API; a tool: key wins over it
--
-- min_plan is compared through common-lib PlanTier, so 'PRO' means "PRO and
-- above" and 'FREE' means no requirement at all. Gating is CLOUD-ONLY: a
-- self-hosted install reports the plan code CE, which clears every bar.
--
-- SEED: the PUBLISHING endpoints of the social integrations become PRO -
-- endpoint by endpoint, NOT whole APIs. Gating api:youtube-data-api would also
-- hold back reading a channel, listing videos or fetching comments, which is
-- not what is being sold; only the act of publishing is. So YouTube keeps 50 of
-- its 52 endpoints free and pays for insert_video and set_thumbnail.
--
-- The rows are resolved by JOINING the catalogue on (icon_slug, tool name)
-- rather than by writing tool_slug literals, because tool_slug is DERIVED at
-- import as apiSlug + "-" + slugified-name (plus a uniqueness suffix on
-- collision). No value an author can write matches it by hand: YouTube's
-- insert_video is stored 'youtube-data-api-insert-video' and X's create_tweet
-- is 'twitter-x-create-tweet'. icon_slug is unique per API by catalogue rule
-- (scripts/api-migrations/SCHEMA.md, "one icon = one API") and the endpoint's
-- NAME is the stable handle for a sibling lookup.
--
-- Verified against production 2026-08-29: these 51 pairs resolve to exactly 51
-- endpoints, none missing and none ambiguous.
--
-- Deliberately NOT seeded: the pure advertising APIs (Snapchat, TikTok
-- Business). Creating a campaign is not publishing to an audience, and lumping
-- it in here would gate a different product on a decision made about another.
-- An admin can add them from the Integrations screen in two clicks.
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth.plan_feature_requirement (
    feature_key VARCHAR(200) PRIMARY KEY,
    min_plan    VARCHAR(32)  NOT NULL,
    -- Human name captured when the row is written, so the admin list can name a
    -- key whose catalogue entry has since been renamed or removed.
    label       VARCHAR(200),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(128)
);

COMMENT ON TABLE auth.plan_feature_requirement IS
    'Minimum subscription plan required to use a workflow node type, a catalog endpoint, or a whole catalog API. No row = available on every plan.';

INSERT INTO auth.plan_feature_requirement (feature_key, min_plan, label, updated_by)
SELECT 'tool:' || t.tool_slug, 'PRO', a.api_name || ' - ' || tn.name, 'seed:V457'
FROM catalog.api_tools t
JOIN catalog.apis a ON a.id = t.api_id
JOIN catalog.tool_names tn ON tn.id::text = t.tool_name_id
WHERE t.tool_slug IS NOT NULL
  AND (a.icon_slug, tn.name) IN (
    -- youtube
    ('youtube', 'insert_video'),
    ('youtube', 'set_thumbnail'),
    -- instagram
    ('instagram', 'create_media_container'),
    ('instagram', 'publish_media'),
    -- instagramlogin
    ('instagramlogin', 'create_media_container'),
    ('instagramlogin', 'publish_media'),
    -- tiktok
    ('tiktok', 'init_video_upload'),
    ('tiktok', 'upload_video_bytes'),
    ('tiktok', 'upload_video_to_inbox'),
    -- facebook
    ('facebook', 'create_page_post'),
    ('facebook', 'create_scheduled_post'),
    ('facebook', 'create_live_video'),
    ('facebook', 'upload_page_photo'),
    -- twitter
    ('twitter', 'create_tweet'),
    ('twitter', 'retweet'),
    ('twitter', 'init_media_upload'),
    ('twitter', 'append_media_upload'),
    ('twitter', 'finalize_media_upload'),
    -- threads
    ('threads', 'create_thread_container'),
    ('threads', 'create_carousel_item'),
    ('threads', 'publish_thread'),
    -- linkedin
    ('linkedin', 'create_post'),
    ('linkedin', 'create_share'),
    ('linkedin', 'create_ugc_post'),
    ('linkedin', 'create_company_share'),
    ('linkedin', 'initialize_image_upload'),
    ('linkedin', 'initialize_video_upload'),
    ('linkedin', 'initialize_document_upload'),
    ('linkedin', 'finalize_video_upload'),
    -- pinterest
    ('pinterest', 'create_pin'),
    ('pinterest', 'save_pin'),
    ('pinterest', 'register_media_upload'),
    -- bluesky
    ('bluesky', 'create_post'),
    ('bluesky', 'create_repost'),
    ('bluesky', 'upload_blob'),
    -- mastodon
    ('mastodon', 'create_status'),
    ('mastodon', 'reblog_status'),
    ('mastodon', 'upload_media'),
    -- reddit
    ('reddit', 'submit_post'),
    ('reddit', 'upload_media'),
    -- medium
    ('medium', 'create_post'),
    ('medium', 'create_publication_post'),
    ('medium', 'upload_image'),
    -- wordpress
    ('wordpress', 'create_post'),
    ('wordpress', 'upload_media'),
    -- vimeo
    ('vimeo', 'upload_video'),
    ('vimeo', 'set_video_thumbnail'),
    -- buffer
    ('buffer', 'create_update'),
    ('buffer', 'share_update'),
    -- hootsuite
    ('hootsuite', 'schedule_message'),
    ('hootsuite', 'upload_media')
)
ON CONFLICT (feature_key) DO NOTHING;
