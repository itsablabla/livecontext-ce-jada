-- ============================================================================
-- Real-Postgres DDL for PublicIntegrationSqlIntegrationTest.
--
-- Mirrors the production state of the four tables the public integration
-- directory reads, consolidated from the migrations:
--   V12  (catalog.apis, catalog.api_tools, catalog.tool_names)
--   V82  (apis.icon_url, apis.source)
--   V87  (apis.documentation)
--   V331 (deprecated_at on apis / api_tools)
--   V461 (catalog.api_usage_stats, the node-usage ledger the ranking reads)
--
-- Same approach as schema-catalog-bundle-postgres.sql beside it: the full Flyway
-- chain is not run (it fails on the pgvector V12/V74 ordering), so only the
-- relevant DDL is restated.
--
-- TWO DELIBERATE RELAXATIONS, both in columns no query here touches:
-- `category_id`/`subcategory_id` are nullable (production has them NOT NULL with
-- FKs to two category tables) and the FK from `api_tools.tool_name_id` is absent
-- (production has none either: the column is VARCHAR while tool_names.id is
-- UUID, which is exactly the mismatch the join cast under test exists for).
-- Every column a query in PublicIntegrationService reads keeps its production
-- TYPE and nullability, because the point of this file is to make Postgres, not
-- a mock, decide whether that SQL is valid.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS catalog;

CREATE TABLE IF NOT EXISTS catalog.apis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255),
    api_name VARCHAR(255) NOT NULL,
    api_slug VARCHAR(255),
    description TEXT NOT NULL,
    category_id UUID,
    subcategory_id UUID,
    base_url VARCHAR(1000) NOT NULL,
    visibility VARCHAR(50) NOT NULL DEFAULT 'public',
    auth_type VARCHAR(50) DEFAULT 'none',
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    is_public BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    icon_slug VARCHAR(100),
    icon_url VARCHAR(512),
    source VARCHAR(50) NOT NULL DEFAULT 'import',
    documentation VARCHAR(1000),
    deprecated_at TIMESTAMPTZ,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
);

CREATE TABLE IF NOT EXISTS catalog.tool_names (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    tool_category_id UUID,
    method VARCHAR(10) NOT NULL DEFAULT 'GET',
    is_active BOOLEAN NOT NULL DEFAULT false,
    slug VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
);

CREATE TABLE IF NOT EXISTS catalog.api_tools (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_id UUID NOT NULL REFERENCES catalog.apis(id) ON DELETE CASCADE,
    tool_slug VARCHAR(255),
    description TEXT NOT NULL,
    -- VARCHAR, while tool_names.id is UUID. The production mismatch, kept.
    tool_name_id VARCHAR(255),
    method VARCHAR(10) NOT NULL,
    endpoint VARCHAR(1000) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    is_active BOOLEAN NOT NULL DEFAULT true,
    deprecated_at TIMESTAMPTZ,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
);

CREATE TABLE IF NOT EXISTS catalog.api_usage_stats (
    api_slug   VARCHAR(255) NOT NULL PRIMARY KEY,
    run_count  BIGINT       NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
