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

    Page<Intervention> findByIdInterventionContainingIgnoreCaseOrderByCreatedAtDesc(String idIntervention, Pageable pageable);

    List<Intervention> findByIdInterventionIn(List<String> idInterventions);

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

    // 🚀 L'FIX HNA : L'Healer kay-jbed ghir 20 b 20 bach n-b9aw safe
    @Query(value = "SELECT * FROM interventions WHERE detail_intervention IS NULL OR detail_intervention = '[]' OR detail_intervention = '' LIMIT 20", nativeQuery = true)
    List<Intervention> findInterventionsWithMissingDetails();

    @Query(value = "SELECT COUNT(*) FROM interventions WHERE detail_intervention IS NULL OR detail_intervention = '[]' OR detail_intervention = ''", nativeQuery = true)
    long countInterventionsWithMissingDetails();
}