package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.retention.ExecutionLogRowClasses;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code storage.storage} half of execution-log retention.
 *
 * <p><b>It lives in {@code .repository}, not beside the rest of retention, and
 * that is load-bearing.</b> Nine applications component-scan
 * {@code com.apimarketplace.common.storage} but every one of them registers only
 * {@code com.apimarketplace.common.storage.repository} with
 * {@code @EnableJpaRepositories}. A Spring Data interface in any other subpackage
 * is never instantiated, while the {@code @Service} that injects it IS discovered,
 * so the whole fleet fails to start on an UnsatisfiedDependencyException. Moving
 * this file is not a tidy-up; putting it back would break every service.
 *
 * <p><b>Addressed by id only, never by a predicate.</b> A payload row is deleted
 * because the journal row that pointed at it was deleted, not because it is old.
 * Sweeping this table independently by age would eventually delete a payload
 * whose step row survives (its epoch still being open), leaving a step visible in
 * the run view pointing at nothing. Taking ids from the referrer makes that
 * impossible to express.
 *
 * <p><b>The SQL is a prefilter, not the decision.</b> {@code s3_key IS NULL} is
 * stated here so an object-backed row cannot even enter a result set, and
 * {@link ExecutionLogRowClasses} then rules on every row returned. Writing the
 * allow-list twice, once in Java and once in SQL, would be two things to keep in
 * step; splitting them this way means drift can only make the query offer rows
 * that Java refuses. The failure direction is "retained too long", never
 * "deleted wrongly".
 */
@Repository
public interface ExecutionLogStorageRetentionRepository extends JpaRepository<StorageEntity, UUID> {

    /** The columns needed to rule on a row and to account for its bytes. */
    interface Candidate {
        UUID getId();

        String getSourceType();

        String getStorageType();

        String getFileName();

        String getS3Key();

        Integer getSizeBytes();

        String getStatus();

        String getTenantId();

        String getOrganizationId();
    }

    /**
     * Payload rows for {@code ids} that could be journal, scoped to one tenant.
     *
     * <p>The tenant predicate is not redundant with the id list: the ids come from
     * a step row's {@code output_storage_id}, a reference with no foreign key, so
     * nothing at the database level guarantees the target belongs to the same
     * tenant. A corrupted or mis-copied reference must not become a cross-tenant
     * delete.
     */
    @Query(nativeQuery = true, value = """
            SELECT s.id            AS id,
                   s.source_type   AS sourceType,
                   s.storage_type  AS storageType,
                   s.file_name     AS fileName,
                   s.s3_key        AS s3Key,
                   s.size_bytes    AS sizeBytes,
                   s.status        AS status,
                   s.tenant_id     AS tenantId,
                   s.organization_id AS organizationId
            FROM storage.storage s
            WHERE s.id IN (:ids)
              AND s.tenant_id = :tenantId
              AND s.s3_key IS NULL
              AND s.is_folder = false
            """)
    List<Candidate> findCandidatesByIds(@Param("tenantId") String tenantId,
                                        @Param("ids") Collection<UUID> ids);

    /**
     * Hard delete, by explicit id list only.
     *
     * <p>Deliberately not expressible as a predicate. Every id reaching here has
     * been read back and cleared by {@link ExecutionLogRowClasses}, so there is no
     * query shape that a later edit could widen.
     */
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM storage.storage WHERE id IN (:ids)")
    int deleteByIds(@Param("ids") Collection<UUID> ids);
}
