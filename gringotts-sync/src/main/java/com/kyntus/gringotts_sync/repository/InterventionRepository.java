package com.kyntus.gringotts_sync.repository;

import com.kyntus.gringotts_sync.domain.Intervention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InterventionRepository extends JpaRepository<Intervention, Long> {
}