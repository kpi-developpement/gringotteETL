package com.kyntus.gringotts_sync.repository;

import com.kyntus.gringotts_sync.domain.Intervention;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface InterventionRepository extends JpaRepository<Intervention, Long> {

    @Query(value = "SELECT i FROM Intervention i WHERE " +
            "(:search IS NULL OR :search = '' OR LOWER(i.idIntervention) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))) AND " +
            "(:source IS NULL OR :source = 'ALL' OR i.sourceIngestion = :source OR (:source = 'INCONNUE' AND i.sourceIngestion IS NULL)) AND " +
            "(:period IS NULL OR :period = '' OR i.periode = :dbPeriod OR COALESCE(i.detailIntervention, '') LIKE CONCAT('%', CAST(:period AS text), '%') OR COALESCE(i.payloadRecu, '') LIKE CONCAT('%', CAST(:period AS text), '%')) " +
            "ORDER BY i.id DESC",
            countQuery = "SELECT count(i) FROM Intervention i WHERE " +
                    "(:search IS NULL OR :search = '' OR LOWER(i.idIntervention) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))) AND " +
                    "(:source IS NULL OR :source = 'ALL' OR i.sourceIngestion = :source OR (:source = 'INCONNUE' AND i.sourceIngestion IS NULL)) AND " +
                    "(:period IS NULL OR :period = '' OR i.periode = :dbPeriod OR COALESCE(i.detailIntervention, '') LIKE CONCAT('%', CAST(:period AS text), '%') OR COALESCE(i.payloadRecu, '') LIKE CONCAT('%', CAST(:period AS text), '%'))")
    Page<Intervention> findFilteredInterventions(
            @Param("search") String search,
            @Param("source") String source,
            @Param("period") String period,
            @Param("dbPeriod") String dbPeriod,
            Pageable pageable
    );

    @Query("SELECT i.id FROM Intervention i WHERE " +
            "(:source IS NULL OR :source = 'ALL' OR i.sourceIngestion = :source OR (:source = 'INCONNUE' AND i.sourceIngestion IS NULL)) AND " +
            "(:period IS NULL OR :period = '' OR i.periode = :dbPeriod OR COALESCE(i.detailIntervention, '') LIKE CONCAT('%', CAST(:period AS text), '%') OR COALESCE(i.payloadRecu, '') LIKE CONCAT('%', CAST(:period AS text), '%')) AND " +
            "(:type IS NULL OR :type = 'ALL' OR i.typeIntervention = :type OR " +
            "(:type = 'RACC' AND COALESCE(i.detailIntervention, '') LIKE '%QualificationRaccordement%') OR " +
            "(:type = 'SAV' AND COALESCE(i.detailIntervention, '') LIKE '%QualificationSAV%') OR " +
            "(:type = 'RZO' AND COALESCE(i.detailIntervention, '') LIKE '%QualificationReseau%')) " +
            "ORDER BY i.id DESC")
    List<Long> findIdsForExport(
            @Param("source") String source,
            @Param("period") String period,
            @Param("dbPeriod") String dbPeriod,
            @Param("type") String type
    );

    List<Intervention> findByIdInterventionIn(List<String> idInterventions);

    @Modifying
    @Transactional
    @Query(value = "TRUNCATE TABLE interventions CASCADE", nativeQuery = true)
    void truncateInterventions();

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM interventions a USING interventions b WHERE a.id > b.id AND a.id_intervention = b.id_intervention", nativeQuery = true)
    int deleteDuplicates();

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM interventions WHERE id > (SELECT MAX(id) FROM (SELECT id FROM interventions ORDER BY id ASC LIMIT :keepCount) AS temp)", nativeQuery = true)
    int deleteExcessRecords(@Param("keepCount") int keepCount);

    @Query(value = "SELECT id FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY id_intervention ORDER BY CASE WHEN detail_intervention IS NOT NULL AND detail_intervention != '[]' THEN 1 ELSE 2 END, id ASC) as rn FROM interventions) t WHERE t.rn > 1 LIMIT 1000", nativeQuery = true)
    List<Long> findDuplicateIds();

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM actions_log WHERE intervention_id IN :ids", nativeQuery = true)
    void deleteLogsByIds(@Param("ids") List<Long> ids);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM interventions WHERE id IN :ids", nativeQuery = true)
    int deleteInterventionsByIds(@Param("ids") List<Long> ids);

    // 🚀 L'FIX HNA : LIMIT 20 au lieu de 15. Le Sweet Spot parfait pour la vitesse et la stabilité.
    @Query(value = "SELECT * FROM interventions WHERE detail_intervention IS NULL OR detail_intervention = '[]' OR detail_intervention = '' ORDER BY id DESC LIMIT 20", nativeQuery = true)
    List<Intervention> findInterventionsWithMissingDetailsDesc();

    // 🚀 L'FIX HNA : LIMIT 20 au lieu de 15.
    @Query(value = "SELECT * FROM interventions WHERE detail_intervention IS NULL OR detail_intervention = '[]' OR detail_intervention = '' ORDER BY id ASC LIMIT 20", nativeQuery = true)
    List<Intervention> findInterventionsWithMissingDetailsAsc();

    @Query(value = "SELECT COUNT(*) FROM interventions WHERE detail_intervention IS NULL OR detail_intervention = '[]' OR detail_intervention = ''", nativeQuery = true)
    long countInterventionsWithMissingDetails();
}