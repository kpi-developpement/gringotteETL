package com.kyntus.gringotts_sync.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sync_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncState {
    @Id
    private String stateKey; // ex: "bt_api_offset"
    private Integer stateValue;
}