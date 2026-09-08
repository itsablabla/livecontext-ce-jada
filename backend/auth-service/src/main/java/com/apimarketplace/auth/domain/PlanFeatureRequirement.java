package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The minimum plan required to use one workflow node type or one catalog
 * integration. Absence of a row means "available on every plan", so this table
 * holds the exceptions rather than the catalogue.
 *
 * <p>See {@code V457__plan_feature_requirements.sql} for the key namespaces
 * ({@code node:}, {@code api:}, {@code tool:}) and why the seed resolves API
 * slugs through {@code icon_slug} instead of writing them literally.
 */
@Entity
@Table(name = "plan_feature_requirement")
public class PlanFeatureRequirement {

    @Id
    @Column(name = "feature_key", nullable = false, length = 200)
    private String featureKey;

    @Column(name = "min_plan", nullable = false, length = 32)
    private String minPlan;

    @Column(name = "label", length = 200)
    private String label;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    protected PlanFeatureRequirement() {
    }

    public PlanFeatureRequirement(String featureKey, String minPlan, String label, String updatedBy) {
        this.featureKey = featureKey;
        this.minPlan = minPlan;
        this.label = label;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getFeatureKey() {
        return featureKey;
    }

    public String getMinPlan() {
        return minPlan;
    }

    public void setMinPlan(String minPlan) {
        this.minPlan = minPlan;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
