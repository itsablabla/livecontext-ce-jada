-- Store the generation prices a CE-applied bundle carried, so they can be
-- re-offered without re-downloading the bundle.
--
-- Why: ApiCatalogBundleApplier re-offers generation prices on every sync tick,
-- because a price hangs off a platform credential and an operator may paste the
-- provider key weeks after the bundle landed. Until now the only source for
-- those prices was the freshly downloaded payload, so the 15-minute poll had to
-- pull ~32 MB just to re-read a few kilobytes. CE rows keep payload_gz NULL
-- (the catalog content lives in catalog.*), so there was nothing local to read.
--
-- With the prices stored here the poll can answer 304 and still re-offer, which
-- is what turns a recurring multi-second transfer into a no-op.
ALTER TABLE catalog.api_catalog_bundles
    ADD COLUMN IF NOT EXISTS generation_prices TEXT;

-- TEXT rather than JSONB: nothing queries inside this value, it is written and
-- read back whole by Jackson. JSONB would require the entity to carry
-- @JdbcTypeCode(SqlTypes.JSON) or every write would fail on Postgres with 42804,
-- and H2 accepts the unannotated mapping, so no test here could catch it.
COMMENT ON COLUMN catalog.api_catalog_bundles.generation_prices IS
    'The generationPrices array this bundle carried, as JSON text, kept so a CE can re-offer prices without re-downloading the payload. NULL means not captured yet; an empty array means the bundle said nothing about prices.';
