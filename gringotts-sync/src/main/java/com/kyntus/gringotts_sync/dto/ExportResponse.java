package com.kyntus.gringotts_sync.dto;

import com.kyntus.gringotts_sync.domain.Intervention;
import lombok.Data;
import java.util.List;

@Data
public class ExportResponse {
    private boolean ok;
    private int count;
    private List<Intervention> data;
    private String error;
}