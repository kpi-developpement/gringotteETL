package com.kyntus.gringotts_sync.domain;

import jakarta.persistence.Column;
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
    private String stateKey;

    private Integer stateValue;

    // 🛡️ L'FIX HNA : On ajoute un champ String pour sauvegarder le nom de la période (ex: "2026_M08")
    @Column(name = "state_value_str")
    private String stateValueStr;

    public SyncState(String stateKey, Integer stateValue) {
        this.stateKey = stateKey;
        this.stateValue = stateValue;
    }
}