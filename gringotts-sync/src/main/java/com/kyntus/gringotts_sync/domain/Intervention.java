package com.kyntus.gringotts_sync.domain;

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
    private Long id; // On garde l'ID du PHP pour la traçabilité

    @Column(name = "id_intervention", nullable = false)
    private String idIntervention;

    private String environment;
    private String etat;

    @Column(name = "date_modification_etat")
    private LocalDateTime dateModificationEtat;

    @Column(name = "type_intervention")
    private String typeIntervention;

    private String mainteneur;

    @Column(name = "action_kyntus")
    private String actionKyntus;

    @Column(name = "action_at")
    private LocalDateTime actionAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "payload_recu", columnDefinition = "TEXT")
    private String payloadRecu;

    @Column(name = "detail_intervention", columnDefinition = "TEXT")
    private String detailIntervention;

    // Cascade ALL : Quand on sauvegarde l'intervention, ça sauvegarde ses logs
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "intervention_id")
    private List<ActionLog> actionsLog;
}