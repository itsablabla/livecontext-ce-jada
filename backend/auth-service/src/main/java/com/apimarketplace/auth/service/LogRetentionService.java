package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.common.plan.PlanTier;
import com.apimarketplace.common.retention.LogRetentionPolicy;
import com.apimarketplace.common.web.AppEditionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How many days of execution journal each WORKSPACE is entitled to keep.
 *
 * <p><b>The unit is the workspace (organization), and the workspace's window is
 * its OWNER's plan. Nothing else.</b> A plan is bought by a user and applies to
 * the workspaces that user owns: a STARTER account's workspaces keep 30 days, a
 * TEAM account's keep 90, whoever happens to be running things inside them. The
 * owner's plan is read through {@link PlanResolutionService#resolveOrgOwnerPlan},
 * the same reader every other org entitlement uses ({@code supportsTeam},
 * {@code maxMembers}, {@code MemberQuotaService}), so retention cannot drift
 * from what the rest of the platform believes a workspace is entitled to.
 *
 * <p>The first version of this service was keyed by TENANT (the acting user) and
 * widened a user's window to the longest plan among the organizations they were
 * a member of. That was wrong in both directions. Production, 2026-09-02: a
 * STARTER user had 6,852 journal rows inside a TEAM owner's workspace and 10 in
 * their own. Per-tenant resolution gave all of them 90 days (the 10 should get
 * 30), and a TEAM owner's rows inside a FREE-owned workspace would have kept 90
 * days where the workspace is entitled to 7. Worse, it hinged on CURRENT
 * membership: a member who left a paying workspace dropped to their own plan and
 * the workspace's journal shortened with them. Keying on the workspace removes
 * all three: the window follows the data's owner, not the person who produced it.
 *
 * <p><b>Absence means retain, and that is the whole safety design.</b> The
 * returned map carries an entry ONLY for a workspace that was found, that has an
 * owner, AND whose owner's plan has a finite window. Every other outcome -
 * unknown id, malformed id, ownerless row, lookup threw, blank plan code, plan
 * unknown to {@link LogRetentionPolicy}, enterprise - is simply absent, and the
 * purge job reads absence as "keep everything". A truncated, partial or empty
 * response can therefore only under-delete.
 *
 * <p><b>A self-hosted install answers nothing, for every workspace, before any
 * lookup.</b> Plans are a cloud concept: a CE owner holds no subscription row
 * (the licence, when there is one, lives elsewhere), so the resolver below would
 * answer "no plan" and the code would read that as FREE, handing seven days to
 * every workspace of an installation that never bought a tier. A self-hosted
 * window is configured explicitly ({@code retention.execution-logs.days}), and
 * when it is, the sweepers never call this service at all; when it is not, the
 * right answer is to keep everything, not to guess.
 *
 * <p><b>"No active subscription" is FREE only because the owner is known to
 * exist.</b> {@code resolveOrgOwnerPlan} answers {@code null} for an owner with no
 * paid plan, and that is read as the 7-day window. It is safe to do so here, and
 * would not be for an arbitrary id, because {@code organization.owner_id} is a
 * NOT NULL foreign key: an organization row proves its owner's account exists,
 * so the answer cannot be conflating "pays nothing" with "nobody matched". The
 * one thing that could still make it ambiguous, an owner that fails to load,
 * is checked before the plan is ever asked for.
 */
@Service
public class LogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionService.class);

    private final OrganizationRepository organizationRepository;
    private final PlanResolutionService planResolutionService;
    private final AppEditionProvider editionProvider;

    public LogRetentionService(OrganizationRepository organizationRepository,
                               PlanResolutionService planResolutionService,
                               AppEditionProvider editionProvider) {
        this.organizationRepository = organizationRepository;
        this.planResolutionService = planResolutionService;
        this.editionProvider = editionProvider;
    }

    /**
     * Finite retention windows for {@code organizationIds}, in days.
     *
     * @param organizationIds workspace ids as the journal tables carry them
     *                        (the organization UUID as a string)
     * @return organization id to days; a workspace absent from the map retains
     *         indefinitely
     */
    public Map<String, Integer> retentionDaysFor(Collection<String> organizationIds) {
        Map<String, Integer> windows = new LinkedHashMap<>();
        if (organizationIds == null || organizationIds.isEmpty()) {
            return windows;
        }
        if (editionProvider.isSelfHosted()) {
            // No plans to read here; see the class javadoc. Answered once per
            // batch rather than per workspace so a large page costs no lookups.
            log.debug("[LogRetention] Self-hosted edition: no plan-derived windows, retaining all");
            return windows;
        }
        for (String organizationId : organizationIds) {
            if (organizationId == null || organizationId.isBlank()) {
                continue;
            }
            Integer days = retentionDaysFor(organizationId);
            if (days != null) {
                windows.put(organizationId, days);
            }
        }
        return windows;
    }

    /**
     * The finite window for one workspace, or {@code null} to retain indefinitely.
     *
     * <p>Every failure path returns {@code null}. A workspace is only ever handed
     * a window that shortens its history when the platform is certain whose it is
     * and what they pay for.
     */
    public Integer retentionDaysFor(String organizationId) {
        if (editionProvider.isSelfHosted()) {
            return null;
        }
        Organization organization = ownedWorkspace(organizationId);
        if (organization == null) {
            return null;
        }
        Plan plan;
        try {
            plan = planResolutionService.resolveOrgOwnerPlan(organization);
        } catch (Exception e) {
            // Retain. One workspace whose lookup blew up must neither be purged on a
            // guess nor stop the rest of the batch from being purged correctly.
            log.warn("[LogRetention] Plan lookup failed for workspace {} - retaining: {}",
                    organizationId, e.getMessage());
            return null;
        }
        String planCode;
        if (plan == null) {
            // Owner exists (checked above) and holds no active subscription: a
            // genuinely free workspace, the platform's own reading of that state
            // (PlanResolutionService.resolveBillingPlan).
            planCode = PlanTier.FREE;
        } else if (plan.getCode() == null || plan.getCode().isBlank()) {
            // PlanTier ranks a blank code as FREE, so passing it through would hand
            // the 7-day window to a workspace whose owner's Plan row simply has no
            // code set. A subscription that points at a code-less plan is an
            // absence of information, not a statement that nothing is paid for.
            log.warn("[LogRetention] Blank plan code on workspace {}'s owner plan - retaining",
                    organizationId);
            return null;
        } else {
            planCode = plan.getCode();
        }
        Integer window = LogRetentionPolicy.retentionDays(planCode);
        if (window != null && window <= 0) {
            // Unreachable today: the policy never returns zero. Guarded anyway,
            // because the value that escapes here would mean "delete everything
            // older than zero days".
            log.warn("[LogRetention] Computed a non-positive window for workspace {} - retaining",
                    organizationId);
            return null;
        }
        return window;
    }

    /**
     * The workspace row, provided it exists and its owner loaded; {@code null}
     * otherwise, which the caller reads as retain.
     *
     * <p>A soft-deleted workspace is resolved like any other: its rows still
     * belong to its owner until {@code WorkspaceDataPurger} removes them, and the
     * owner's plan still bounds what is kept meanwhile. Nothing here shortens a
     * window because a workspace is paused or deleted.
     *
     * <p>Only the owner's ID is read off the (lazily loaded) association, which
     * Hibernate serves from the foreign key without a second query or an open
     * session. The check exists because {@code resolveOrgOwnerPlan} answers
     * {@code null} both for "owner has no plan" and for "no owner", and only the
     * first may become the 7-day window.
     */
    private Organization ownedWorkspace(String organizationId) {
        UUID id;
        try {
            id = UUID.fromString(organizationId.trim());
        } catch (IllegalArgumentException e) {
            // Not an organization id at all. Journal rows carry the UUID as text,
            // so anything else is a caller passing the wrong key, and the safe
            // answer to a question about the wrong key is no answer.
            log.warn("[LogRetention] '{}' is not an organization id - retaining", organizationId);
            return null;
        }
        Optional<Organization> organization;
        try {
            organization = organizationRepository.findById(id);
        } catch (Exception e) {
            log.warn("[LogRetention] Workspace lookup failed for {} - retaining: {}",
                    organizationId, e.getMessage());
            return null;
        }
        if (organization.isEmpty()) {
            log.debug("[LogRetention] No workspace {} - retaining", organizationId);
            return null;
        }
        User owner = organization.get().getOwner();
        if (owner == null || owner.getId() == null) {
            log.warn("[LogRetention] Workspace {} has no owner - retaining", organizationId);
            return null;
        }
        return organization.get();
    }
}
