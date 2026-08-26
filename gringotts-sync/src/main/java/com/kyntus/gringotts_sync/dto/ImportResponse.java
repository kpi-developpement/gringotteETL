package com.kyntus.gringotts_sync.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

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

    // 🛡️ L'FIX HNA : Zedna l'champ total_api bach Java y-fhem l'JSON
    @JsonProperty("total_api")
    private int totalApi;

    private boolean done;
}