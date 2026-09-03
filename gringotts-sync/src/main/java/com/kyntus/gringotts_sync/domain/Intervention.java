package com.kyntus.gringotts_sync.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "interventions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Intervention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 🚀 L'FIX HNA : Auto-Increment
    @JsonProperty("id")
    private Long id;

    @JsonProperty("id_intervention")
    @Column(name = "id_intervention", nullable = false)
    private String idIntervention;

    @JsonProperty("environment")
    private String environment;

    @JsonProperty("etat")
    private String etat;

    @JsonProperty("date_modification_etat")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "date_modification_etat")
    private LocalDateTime dateModificationEtat;

    @JsonProperty("type_intervention")
    @Column(name = "type_intervention")
    private String typeIntervention;

    @JsonProperty("mainteneur")
    private String mainteneur;

    @JsonProperty("action_kyntus")
    @Column(name = "action_kyntus")
    private String actionKyntus;

    @JsonProperty("action_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "action_at")
    private LocalDateTime actionAt;

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @JsonProperty("payload_recu")
    @Column(name = "payload_recu", columnDefinition = "TEXT")
    private String payloadRecu;

    @JsonProperty("detail_intervention")
    @Column(name = "detail_intervention", columnDefinition = "TEXT")
    private String detailIntervention;

    @JsonProperty("actions_log")
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "intervention_id")
    private List<ActionLog> actionsLog;
}