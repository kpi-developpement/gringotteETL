package com.kyntus.gringotts_sync.domain;

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
    private Long id; // ID venant du PHP

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "type_action")
    private String typeAction;

    private String environment;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}