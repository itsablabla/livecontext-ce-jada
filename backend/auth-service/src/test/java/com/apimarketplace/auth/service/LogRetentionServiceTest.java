package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LogRetentionService")
class LogRetentionServiceTest {

    private static final Long OWNER = 42L;
    private static final Long OTHER_OWNER = 43L;

    private OrganizationRepository organizationRepository;
    private PlanResolutionService planResolutionService;
    private AppEditionProvider editionProvider;
    private LogRetentionService service;

    /** The organization mock behind each workspace id handed out by {@link #workspaceOwnedBy}. */
    private final Map<String, Organization> workspaces = new HashMap<>();

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        planResolutionService = mock(PlanResolutionService.class);
        editionProvider = mock(AppEditionProvider.class);
        when(editionProvider.isSelfHosted()).thenReturn(false);
        when(organizationRepository.findById(any())).thenReturn(Optional.empty());
        service = new LogRetentionService(organizationRepository, planResolutionService, editionProvider);
    }

    /** A workspace row whose owner is {@code ownerId}; returns its id as the journal carries it. */
    private String workspaceOwnedBy(Long ownerId) {
        UUID id = UUID.randomUUID();
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        Organization organization = mock(Organization.class);
        when(organization.getOwner()).thenReturn(owner);
        when(organizationRepository.findById(id)).thenReturn(Optional.of(organization));
        workspaces.put(id.toString(), organization);
        return id.toString();
    }

    /** The owner of {@code workspaceId} is on {@code planCode}; {@code null} = no active subscription. */
    private void ownerPlanIs(String workspaceId, String planCode) {
        Plan plan = null;
        if (planCode != null) {
            plan = mock(Plan.class);
            when(plan.getCode()).thenReturn(planCode);
        }
        when(planResolutionService.resolveOrgOwnerPlan(workspaces.get(workspaceId))).thenReturn(plan);
    }

    /** A Plan row that exists but carries the given (blank or null) code. */
    private void ownerPlanHasCode(String workspaceId, String rawCode) {
        Plan plan = mock(Plan.class);
        when(plan.getCode()).thenReturn(rawCode);
        when(planResolutionService.resolveOrgOwnerPlan(workspaces.get(workspaceId))).thenReturn(plan);
    }

    @Nested
    @DisplayName("The owner's plan is the workspace's window")
    class OwnerPlanIsTheWindow {

        @Test
        @DisplayName("A paying owner's workspace gets the window that tier advertises")
        void payingOwnersGetTheirWindow() {
            String team = workspaceOwnedBy(1L);
            String pro = workspaceOwnedBy(2L);
            String starter = workspaceOwnedBy(3L);
            ownerPlanIs(team, "TEAM");
            ownerPlanIs(pro, "PRO");
            ownerPlanIs(starter, "STARTER");

            assertEquals(90, service.retentionDaysFor(team));
            assertEquals(30, service.retentionDaysFor(pro));
            assertEquals(30, service.retentionDaysFor(starter));
        }

        /**
         * resolveOrgOwnerPlan answers null for an owner with no active subscription.
         * That is FREE here, and only here, because the organization row's NOT NULL
         * owner FK proves the owner exists: "pays nothing" cannot be confused with
         * "nobody matched".
         */
        @Test
        @DisplayName("An owner with no active subscription has a genuinely free workspace: 7 days")
        void freeOwnerIsSevenDays() {
            String workspace = workspaceOwnedBy(OWNER);
            ownerPlanIs(workspace, null);

            assertEquals(7, service.retentionDaysFor(workspace));
        }

        @Test
        @DisplayName("An enterprise owner's workspace retains: its window is contractual, not tabulated")
        void enterpriseRetains() {
            String workspace = workspaceOwnedBy(OWNER);
            ownerPlanIs(workspace, "ENTERPRISE_PREMIUM");

            assertNull(service.retentionDaysFor(workspace));
        }

        /**
         * The regression this rewrite exists for. Production 2026-09-02: a STARTER
         * user held 6,852 journal rows inside a TEAM owner's workspace and 10 in
         * their own. Per-TENANT resolution, widened to the longest plan among the
         * user's memberships, gave every one of those rows 90 days. The window
         * belongs to the workspace: the TEAM owner's workspace keeps 90 whoever
         * wrote the rows, and the STARTER user's own workspace keeps 30 regardless
         * of what they are a member of elsewhere.
         */
        @Test
        @DisplayName("Two workspaces, two windows: a member's own STARTER workspace is not widened by the TEAM one they work in")
        void membershipNeverWidensTheMembersOwnWorkspace() {
            String teamOwnersWorkspace = workspaceOwnedBy(OWNER);
            String starterMembersOwnWorkspace = workspaceOwnedBy(OTHER_OWNER);
            ownerPlanIs(teamOwnersWorkspace, "TEAM");
            ownerPlanIs(starterMembersOwnWorkspace, "STARTER");

            Map<String, Integer> windows = service.retentionDaysFor(
                    List.of(teamOwnersWorkspace, starterMembersOwnWorkspace));

            assertEquals(90, windows.get(teamOwnersWorkspace),
                    "the paying owner's workspace keeps its window whoever produced the rows");
            assertEquals(30, windows.get(starterMembersOwnWorkspace),
                    "being a member of a TEAM workspace buys nothing for one's own workspace");
        }

        /**
         * The plan is read through the platform's ONE org-entitlement reader, and
         * for the workspace being asked about: never for another workspace, and
         * never through a per-user path that could disagree with it.
         */
        @Test
        @DisplayName("The plan is resolved through resolveOrgOwnerPlan, for that workspace only")
        void planIsResolvedThroughTheCanonicalReaderForThatWorkspace() {
            String asked = workspaceOwnedBy(OWNER);
            String other = workspaceOwnedBy(OTHER_OWNER);
            ownerPlanIs(asked, "TEAM");
            ownerPlanIs(other, "FREE");

            assertEquals(90, service.retentionDaysFor(asked));

            verify(planResolutionService).resolveOrgOwnerPlan(workspaces.get(asked));
            verify(planResolutionService, never()).resolveOrgOwnerPlan(workspaces.get(other));
        }

        /**
         * Deletion or pausing of a workspace does not change whose it is. Its rows
         * stay bounded by the owner's window until WorkspaceDataPurger removes them.
         */
        @Test
        @DisplayName("A soft-deleted workspace still resolves to its owner's window")
        void softDeletedWorkspaceStillResolves() {
            String workspace = workspaceOwnedBy(OWNER);
            when(workspaces.get(workspace).getDeletedAt()).thenReturn(LocalDateTime.now().minusDays(3));
            ownerPlanIs(workspace, "STARTER");

            assertEquals(30, service.retentionDaysFor(workspace));
        }
    }

    @Nested
    @DisplayName("Failing long")
    class FailingLong {

        @Test
        @DisplayName("A workspace id that matches no row retains")
        void unknownWorkspaceRetains() {
            assertNull(service.retentionDaysFor(UUID.randomUUID().toString()));
            verify(planResolutionService, never()).resolveOrgOwnerPlan(any());
        }

        /**
         * The journal carries the organization UUID as text. Anything else is a
         * caller handing over the wrong key (a tenant id, say), and the safe answer
         * to a question about the wrong key is no answer, not a lookup that happens
         * to match something.
         */
        @Test
        @DisplayName("A value that is not an organization id retains and is never looked up as anything else")
        void nonUuidRetainsWithoutLookup() {
            assertNull(service.retentionDaysFor("42"));
            assertNull(service.retentionDaysFor("tenant-under-test"));
            verify(organizationRepository, never()).findById(any());
            verify(planResolutionService, never()).resolveOrgOwnerPlan(any());
        }

        /**
         * resolveOrgOwnerPlan answers null for "no owner" exactly as it does for
         * "owner with no plan". Only the second may become the 7-day window, so the
         * owner is checked BEFORE the plan is asked for.
         */
        @Test
        @DisplayName("A workspace with no owner retains, and its plan is never even asked for")
        void ownerlessWorkspaceRetains() {
            UUID id = UUID.randomUUID();
            Organization organization = mock(Organization.class);
            when(organization.getOwner()).thenReturn(null);
            when(organizationRepository.findById(id)).thenReturn(Optional.of(organization));

            assertNull(service.retentionDaysFor(id.toString()));
            verify(planResolutionService, never()).resolveOrgOwnerPlan(any());
        }

        @Test
        @DisplayName("An owner that loaded without an id retains, for the same reason")
        void ownerWithoutIdRetains() {
            String workspace = workspaceOwnedBy(null);
            ownerPlanIs(workspace, "TEAM");

            assertNull(service.retentionDaysFor(workspace));
            verify(planResolutionService, never()).resolveOrgOwnerPlan(any());
        }

        @Test
        @DisplayName("A workspace lookup that throws retains and does not abort the rest of the batch")
        void throwingWorkspaceLookupRetainsAndBatchContinues() {
            UUID boom = UUID.randomUUID();
            when(organizationRepository.findById(boom)).thenThrow(new IllegalStateException("auth db down"));
            String team = workspaceOwnedBy(OWNER);
            ownerPlanIs(team, "TEAM");

            Map<String, Integer> windows = service.retentionDaysFor(List.of(boom.toString(), team));

            assertFalse(windows.containsKey(boom.toString()), "a failed lookup must not yield a window");
            assertEquals(90, windows.get(team), "one bad workspace must not stop the others");
        }

        @Test
        @DisplayName("A plan lookup that throws retains and does not abort the rest of the batch")
        void throwingPlanLookupRetainsAndBatchContinues() {
            String boom = workspaceOwnedBy(OTHER_OWNER);
            when(planResolutionService.resolveOrgOwnerPlan(workspaces.get(boom)))
                    .thenThrow(new IllegalStateException("plan table unreachable"));
            String team = workspaceOwnedBy(OWNER);
            ownerPlanIs(team, "TEAM");

            Map<String, Integer> windows = service.retentionDaysFor(List.of(boom, team));

            assertFalse(windows.containsKey(boom));
            assertEquals(90, windows.get(team));
        }

        @Test
        @DisplayName("A plan code the platform does not know retains")
        void unknownPlanCodeRetains() {
            String workspace = workspaceOwnedBy(OWNER);
            ownerPlanIs(workspace, "SOME_FUTURE_SKU");

            assertNull(service.retentionDaysFor(workspace));
        }

        /**
         * The side door: PlanTier ranks a blank code as FREE, so a subscription that
         * points at a Plan row with no code set would hand a paying owner's
         * workspace the 7-day window. A code-less plan is missing information, not
         * a statement that nothing is paid for. This is distinct from "no plan at
         * all", which IS free (see freeOwnerIsSevenDays).
         */
        @Test
        @DisplayName("A plan whose code is blank or null retains rather than ranking as FREE")
        void blankPlanCodeRetains() {
            String blank = workspaceOwnedBy(1L);
            String spaces = workspaceOwnedBy(2L);
            String nul = workspaceOwnedBy(3L);
            ownerPlanHasCode(blank, "");
            ownerPlanHasCode(spaces, "   ");
            ownerPlanHasCode(nul, null);

            assertNull(service.retentionDaysFor(blank));
            assertNull(service.retentionDaysFor(spaces));
            assertNull(service.retentionDaysFor(nul));
        }
    }

    @Nested
    @DisplayName("Self-hosted")
    class SelfHosted {

        /**
         * A CE owner holds no subscription row, so resolveOrgOwnerPlan answers
         * "no plan", which this service would otherwise read as FREE: seven days
         * for every workspace of an install that never bought a tier. A licensed
         * self-hosted enterprise is the same case, because the licence is not a
         * subscription either. Both must answer nothing, before any lookup, and
         * whatever the resolver would have said.
         */
        @Test
        @DisplayName("A self-hosted install answers no window for any workspace, even one whose owner 'has no plan'")
        void selfHostedRetainsEverything() {
            when(editionProvider.isSelfHosted()).thenReturn(true);
            String workspace = workspaceOwnedBy(OWNER);
            ownerPlanIs(workspace, null);

            assertNull(service.retentionDaysFor(workspace));
            assertTrue(service.retentionDaysFor(List.of(workspace)).isEmpty());
            verify(planResolutionService, never()).resolveOrgOwnerPlan(any());
        }

        @Test
        @DisplayName("A self-hosted install answers nothing even for an owner whose plan WOULD have a window")
        void selfHostedIgnoresAResolvablePlan() {
            when(editionProvider.isSelfHosted()).thenReturn(true);
            String workspace = workspaceOwnedBy(OWNER);
            ownerPlanIs(workspace, "STARTER");

            assertTrue(service.retentionDaysFor(List.of(workspace)).isEmpty(),
                    "self-hosted retention is configured, never derived from a plan row");
        }
    }

    @Nested
    @DisplayName("Batch shape")
    class BatchShape {

        @Test
        @DisplayName("Only workspaces with a finite window appear: absence is how 'retain' is expressed")
        void onlyFiniteWindowsAppear() {
            String team = workspaceOwnedBy(1L);
            String enterprise = workspaceOwnedBy(2L);
            String ghost = UUID.randomUUID().toString();
            ownerPlanIs(team, "TEAM");
            ownerPlanIs(enterprise, "ENTERPRISE");

            Map<String, Integer> windows = service.retentionDaysFor(List.of(team, enterprise, ghost));

            assertEquals(Map.of(team, 90), windows);
        }

        @Test
        @DisplayName("Null, empty and blank inputs yield an empty map rather than throwing")
        void degenerateInputsAreEmpty() {
            assertTrue(service.retentionDaysFor((List<String>) null).isEmpty());
            assertTrue(service.retentionDaysFor(List.of()).isEmpty());
            assertTrue(service.retentionDaysFor(java.util.Arrays.asList("", "   ")).isEmpty());
        }

        @Test
        @DisplayName("A blank id is skipped without ever reaching a lookup")
        void blankIdNeverHitsLookup() {
            assertTrue(service.retentionDaysFor(List.of("   ")).isEmpty());
            verify(organizationRepository, never()).findById(any());
            verify(planResolutionService, never()).resolveOrgOwnerPlan(any());
        }
    }
}
