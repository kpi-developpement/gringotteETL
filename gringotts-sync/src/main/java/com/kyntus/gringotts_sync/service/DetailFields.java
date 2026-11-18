package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.OffsetDateTime;

/**
 * Extraction des champs utiles du détail Bytel vers des colonnes indexées (filtres rapides).
 *  - categorie : RACC / SAV / RZO / AUTRE  (qualificationInterventions[*]._type, la plus récente)
 *  - type_presta, type_raccordement, etat_qualif (même qualification)
 *  - code_cloture, sous_traitant, departement, oi, fyt, date_intervention, periode (racine du détail)
 */
public record DetailFields(String categorie, String typePresta, String typeRaccordement, String etatQualif,
                           String codeCloture, String sousTraitant, String departement, String oi, String fyt,
                           Timestamp dateIntervention, String periode) {

    public static final int VERSION = 1;

    public static DetailFields parse(ObjectMapper om, String json) {
        try {
            JsonNode d = om.readTree(json);
            if (d == null || !d.isObject()) return empty();

            JsonNode q = null;
            String bestDate = "";
            for (JsonNode n : d.path("qualificationInterventions")) {
                String dt = n.path("date").asText("");
                if (q == null || dt.compareTo(bestDate) > 0) { q = n; bestDate = dt; }
            }
            String type = q == null ? null : txt(q, "_type");
            String cat = type == null ? null : switch (type) {
                case "QualificationRaccordement" -> "RACC";
                case "QualificationSAV" -> "SAV";
                case "QualificationReseau" -> "RZO";
                default -> "AUTRE";
            };

            Timestamp date = null;
            String di = txt(d, "dateIntervention");
            if (di != null) {
                try { date = Timestamp.valueOf(OffsetDateTime.parse(di).toLocalDateTime()); } catch (Exception ignored) { }
            }
            String periode = txt(d, "periode");
            return new DetailFields(cat,
                    q == null ? null : txt(q, "typeIntervention"),
                    q == null ? null : txt(q, "typeRaccordement"),
                    q == null ? null : txt(q, "etat"),
                    txt(d, "codeCloture"), txt(d, "sousTraitant"), txt(d, "departement"),
                    txt(d, "oi") == null ? null : txt(d, "oi").trim(), txt(d, "fyt"), date,
                    periode == null ? null : periode.replace("-", "_"));
        } catch (Exception e) {
            return empty();
        }
    }

    private static DetailFields empty() {
        return new DetailFields(null, null, null, null, null, null, null, null, null, null, null);
    }

    private static String txt(JsonNode n, String k) {
        JsonNode v = n.get(k);
        if (v == null || v.isNull() || v.isContainerNode()) return null;
        String s = v.asText();
        if (s == null || s.isBlank()) return null;
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /** SQL d'UPDATE commun (healer, inspecteur, rattrapage). Paramètres : voir {@link #params(String, long)}. */
    public static final String UPDATE_SQL = "UPDATE interventions SET detail_intervention = ?, heal_next_at = NULL, " +
            "categorie = ?, type_presta = ?, type_raccordement = ?, etat_qualif = ?, code_cloture = ?, sous_traitant = ?, " +
            "departement = ?, oi = ?, fyt = ?, date_intervention = ?, periode = COALESCE(?, periode), extract_v = " + VERSION + " WHERE id = ?";

    public Object[] params(String detail, long id) {
        return new Object[]{detail, categorie, typePresta, typeRaccordement, etatQualif, codeCloture, sousTraitant,
                departement, oi, fyt, new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.TIMESTAMP, dateIntervention),
                periode, id};
    }
}
