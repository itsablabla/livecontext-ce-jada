package com.apimarketplace.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Signed API-catalog bundle (cloud → CE distribution). Table
 * {@code catalog.api_catalog_bundles} (V331), mirroring
 * {@code agent.catalog_bundles} (the LLM model bundle).
 *
 * <p>Key difference from the model bundle: the signed payload IS stored here
 * ({@code payload_gz} = the gzipped canonical JSON the signature covers). The
 * API catalog is too large to re-derive on every {@code /latest} call, and
 * storing the exact signed bytes removes the live-table-drift problem (an
 * admin edit after a build can never invalidate the served bundle).
 *
 * <p>Cloud: rows created by {@code ApiCatalogBundleService.buildBundle()}
 * ({@code isActive=false}); {@code activateBundle(id)} flips the active flag -
 * at most one active row (partial unique index
 * {@code idx_api_catalog_bundles_one_active}). CE: a row records each applied
 * bundle with {@code payload_gz} left NULL (content already merged into
 * {@code catalog.*}).
 */
@Entity
@Table(name = "api_catalog_bundles")
public class ApiCatalogBundleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version", nullable = false, unique = true)
    private Long version;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 1;

    /** SHA-256 hex of the GZIPPED canonical payload (64 chars). */
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    /** Base64 Ed25519 signature of the GZIPPED canonical payload bytes. */
    @Column(name = "signature", nullable = false, columnDefinition = "text")
    private String signature;

    @Column(name = "signing_key_id", nullable = false, length = 50)
    private String signingKeyId;

    @Column(name = "issuer", nullable = false, length = 100)
    private String issuer;

    @Column(name = "api_count", nullable = false)
    private Integer apiCount;

    @Column(name = "tool_count", nullable = false)
    private Integer toolCount;

    /** Size of the UNCOMPRESSED canonical JSON in bytes. */
    @Column(name = "raw_bytes_size", nullable = false)
    private Integer rawBytesSize;

    /** The signed gzipped payload (cloud side); NULL on CE after apply. */
    @Column(name = "payload_gz")
    private byte[] payloadGz;

    /**
     * The {@code generationPrices} array this bundle carried, as canonical JSON.
     *
     * <p>Kept so a CE can re-offer prices on a tick where the cloud answered 304
     * and there is no payload to re-parse.
     *
     * <p><b>NULL and {@code []} are different statements, and the conditional
     * fetch depends on telling them apart.</b> NULL means "never captured": the
     * install must fetch in full, because a 304 would leave it nothing to
     * re-offer. {@code []} means "captured, and this bundle declares no prices",
     * which the price applier reads as a no-op (never as "unprice everything").
     * Collapsing the two would either stop price re-offering for good or pin an
     * install to a permanent full download.
     *
     * <p>Stored as {@code text}, not {@code jsonb}, on purpose: nothing ever
     * queries inside it, it is written and read back whole by Jackson. A jsonb
     * column would need {@code @JdbcTypeCode(SqlTypes.JSON)} or every write
     * would fail on Postgres with 42804 (Hibernate binds a String as varchar,
     * and the varchar->jsonb cast is explicit-only) - a failure H2 accepts
     * silently, so no test in this module could see it.
     */
    @Column(name = "generation_prices", columnDefinition = "text")
    private String generationPrices;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = false;

    @PrePersist
    protected void onCreate() {
        if (importedAt == null) importedAt = Instant.now();
        if (schemaVersion == null) schemaVersion = 1;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getSigningKeyId() { return signingKeyId; }
    public void setSigningKeyId(String signingKeyId) { this.signingKeyId = signingKeyId; }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public Integer getApiCount() { return apiCount; }
    public void setApiCount(Integer apiCount) { this.apiCount = apiCount; }

    public Integer getToolCount() { return toolCount; }
    public void setToolCount(Integer toolCount) { this.toolCount = toolCount; }

    public Integer getRawBytesSize() { return rawBytesSize; }
    public void setRawBytesSize(Integer rawBytesSize) { this.rawBytesSize = rawBytesSize; }

    public byte[] getPayloadGz() { return payloadGz; }
    public void setPayloadGz(byte[] payloadGz) { this.payloadGz = payloadGz; }

    public String getGenerationPrices() { return generationPrices; }
    public void setGenerationPrices(String generationPrices) { this.generationPrices = generationPrices; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant importedAt) { this.importedAt = importedAt; }

    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
