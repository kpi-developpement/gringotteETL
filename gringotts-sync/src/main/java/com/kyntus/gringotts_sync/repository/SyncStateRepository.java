package com.kyntus.gringotts_sync.repository;

import com.kyntus.gringotts_sync.domain.SyncState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncStateRepository extends JpaRepository<SyncState, String> {
}