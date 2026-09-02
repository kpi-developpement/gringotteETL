package com.kyntus.gringotts_sync.repository;

import com.kyntus.gringotts_sync.domain.Intervention;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface InterventionRepository extends JpaRepository<Intervention, Long> {

    // 🚀 NOUVEAU : Pagination et Recherche par ID
    Page<Intervention> findByIdInterventionContainingIgnoreCaseOrderByCreatedAtDesc(String idIntervention, Pageable pageable);

    // 🚀 NOUVEAU : Requête native Postgres pour supprimer les doublons (garde le plus ancien)
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM interventions a USING interventions b WHERE a.id > b.id AND a.id_intervention = b.id_intervention", nativeQuery = true)
    int deleteDuplicates();
}