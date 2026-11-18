package com.kyntus.gringotts_sync.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@DynamicUpdate // UPDATE uniquement des colonnes modifiées : la Time Machine n'écrase plus les détails écrits par le Healer
@Table(name = "interventions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Intervention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty("id")
    private Long id;

    @JsonAlias({"id_intervention", "identifiant"})
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

    @JsonProperty("source_ingestion")
    @Column(name = "source_ingestion")
    private String sourceIngestion;

    @JsonProperty("periode")
    @Column(name = "periode")
    private String periode;

    /** Dernière fois que Bytel a renvoyé cet EPS dans un scan (contrôle "orphelins"). */
    @JsonProperty(value = "last_seen_at", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** Nombre d'essais du Healer sans obtenir de détail. */
    @JsonProperty(value = "heal_attempts", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "heal_attempts")
    private Integer healAttempts;

    /** Prochain essai programmé du Healer (null = pas de ré-essai prévu). */
    @JsonProperty(value = "heal_next_at", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "heal_next_at")
    private LocalDateTime healNextAt;


    // ---------- Champs extraits du détail Bytel (colonnes indexées pour les filtres) ----------
    /** RACC / SAV / RZO / AUTRE (extrait du détail) */
    @JsonProperty(value = "categorie", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "categorie", length = 16)
    private String categorie;

    /** typeIntervention de la qualification (PLP, NOK, SAV_STD…) */
    @JsonProperty(value = "type_presta", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "type_presta", length = 40)
    private String typePresta;

    @JsonProperty(value = "type_raccordement", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "type_raccordement", length = 40)
    private String typeRaccordement;

    /** VALIDEE, EN_ATTENTE_VALIDATION_PARTENAIRE… */
    @JsonProperty(value = "etat_qualif", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "etat_qualif", length = 60)
    private String etatQualif;

    @JsonProperty(value = "code_cloture", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "code_cloture", length = 40)
    private String codeCloture;

    @JsonProperty(value = "sous_traitant", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "sous_traitant", length = 120)
    private String sousTraitant;

    @JsonProperty(value = "departement", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "departement", length = 8)
    private String departement;

    @JsonProperty(value = "oi", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "oi", length = 16)
    private String oi;

    @JsonProperty(value = "fyt", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "fyt", length = 40)
    private String fyt;

    @JsonProperty(value = "date_intervention", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "date_intervention")
    private LocalDateTime dateIntervention;

    /** Version d'extraction (null = pas encore extrait). */
    @JsonProperty(value = "extract_v", access = JsonProperty.Access.READ_ONLY)
    @Column(name = "extract_v")
    private Integer extractV;

    // WRITE_ONLY : lu depuis le payload PHP, jamais renvoyé à l'UI (collection LAZY hors session = erreur 500).
    @JsonProperty(value = "actions_log", access = JsonProperty.Access.WRITE_ONLY)
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "intervention_id")
    private List<ActionLog> actionsLog;
}