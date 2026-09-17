package com.kyntus.gringotts_sync.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kyntus.gringotts_sync.domain.Intervention;
import lombok.Data;
import java.util.List;

@Data
public class ImportResponse {
    private boolean ok;
    private String error;

    @JsonProperty("batch_count")
    private int batchCount;

    private int inserted;
    private int updated;

    @JsonProperty("next_offset")
    private int nextOffset;

    @JsonProperty("total_api")
    private int totalApi;

    private boolean done;

    // 🚀 L'FIX HNA : Récupération directe des données depuis le Proxy PHP
    private List<Intervention> data;
}