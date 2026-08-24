package com.kyntus.gringotts_sync.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "actions_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionLog {

    @Id
    @JsonProperty("id")
    private Long id;

    @JsonProperty("user_id")
    @Column(name = "user_id")
    private Long userId;

    @JsonProperty("type_action")
    @Column(name = "type_action")
    private String typeAction;

    @JsonProperty("environment")
    private String environment;

    @JsonProperty("details")
    @Column(columnDefinition = "TEXT")
    private String details;

    @JsonProperty("ip_address")
    @Column(name = "ip_address")
    private String ipAddress;

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}