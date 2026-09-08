package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PlanFeatureRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlanFeatureRequirementRepository extends JpaRepository<PlanFeatureRequirement, String> {
}
