package com.kyntus.gringotts_sync.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Journal persistant de tout ce qui se passe (Time Machine, Healer, AutoPilot, Bytel...). */
@Entity
@Table(name = "sync_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** TIME_MACHINE, HEALER, AUTOPILOT, BYTEL, MAINTENANCE, SYSTEM */
    @Column(length = 20, nullable = false)
    private String source;

    /** INFO, SUCCESS, WARN, ERROR */
    @Column(length = 10, nullable = false)
    private String level;

    @Column(length = 16)
    private String period;

    @Column(columnDefinition = "TEXT")
    private String message;
}
