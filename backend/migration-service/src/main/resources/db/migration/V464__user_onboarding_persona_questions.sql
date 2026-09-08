-- Onboarding persona questions (2026-09-02).
--
-- The form now asks what the user wants to automate FIRST, which tools they
-- already use, what they automate with today, and how they heard about us.
-- Additive: the previous columns (profession, company_size, interests,
-- use_cases, experience_level) stay, so older rows and the app-suggestion
-- mapper keep working; the new answers are optional.

ALTER TABLE auth.user_onboarding
    ADD COLUMN IF NOT EXISTS primary_goal    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS tools_used      JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS previous_tool   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS referral_source VARCHAR(50);

COMMENT ON COLUMN auth.user_onboarding.primary_goal    IS 'What the user wants to automate first (bounded option value).';
COMMENT ON COLUMN auth.user_onboarding.tools_used      IS 'Tools the user already uses, as option slugs (jsonb array of text).';
COMMENT ON COLUMN auth.user_onboarding.previous_tool   IS 'What they automate with today: none, zapier-make, n8n, custom-code, other-platform.';
COMMENT ON COLUMN auth.user_onboarding.referral_source IS 'How they heard about LiveContext (bounded option value).';
