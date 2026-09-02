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

@Repository
public interface InterventionRepository extends JpaRepository<Intervention, Long> {

    Page<Intervention> findByIdInterventionContainingIgnoreCaseOrderByCreatedAtDesc(String idIntervention, Pageable pageable);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM interventions a USING interventions b WHERE a.id > b.id AND a.id_intervention = b.id_intervention", nativeQuery = true)
    int deleteDuplicates();

    // 🚀 1. Njbdou l'ID dyal l'intervention li bghina n-7ebsou 3endha
    @Query(value = "SELECT id FROM interventions ORDER BY id ASC OFFSET :offset LIMIT 1", nativeQuery = true)
    Long findCutoffId(@Param("offset") int offset);

    // 🚀 2. N-ms7ou les logs li zaydin (Bach ma y-dirouch mouchkil dyal Foreign Key)
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM actions_log WHERE intervention_id > :cutoffId", nativeQuery = true)
    int deleteExcessLogs(@Param("cutoffId") Long cutoffId);

    // 🚀 3. N-ms7ou les interventions li zaydin
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM interventions WHERE id > :cutoffId", nativeQuery = true)
    int deleteExcessInterventions(@Param("cutoffId") Long cutoffId);
}