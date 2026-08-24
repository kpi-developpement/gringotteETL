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

    private boolean done;
}