package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * API externe : extraction de tous les EPS en attente de validation partenaire
 * avec les colonnes métier exactes (Installation, Déplacement, Total, C01..C11, KX, photos, État, Commentaire).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartenaireApiService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile NamedParameterJdbcTemplate named;

    public static final String TARGET_ETAT = "EN_ATTENTE_VALIDATION_PARTENAIRE";

    /** Ordre exact des colonnes demandées. */
    public static final List<String> COLUMNS = List.of(
            "idIntervention",
            "Etat",
            "Commentaire",
            "Installation",
            "Deplacement",
            "Total",
            "C04_CCR",
            "C09_KO_PRECEDENT_REPRISE",
            "C01_TICKET",
            "C02_ENCHAINEMENT",
            "C03_TYPE_PBO",
            "C05_DOUBLON",
            "C06_TYPE_INTER",
            "C07_FRAIS",
            "C08_GARANTIE",
            "C10_TIRAGES",
            "C11_SUPPORT",
            "commentairePhotos",
            "sousCategorieControlePhoto",
            "KX"
    );

    private NamedParameterJdbcTemplate named() {
        if (named == null) named = new NamedParameterJdbcTemplate(jdbcTemplate);
        return named;
    }

    /**
     * Récupère toutes les interventions en attente de validation partenaire (ou filtrées par période/catégorie/recherche).
     */
    public Map<String, Object> fetchAttenteValidation(String periode, String categorie, String q,
                                                      Integer page, Integer size, boolean allVersions) {
        MapSqlParameterSource ps = new MapSqlParameterSource();
        List<String> where = new ArrayList<>();

        // On filtre sur etat_qualif OU etat = EN_ATTENTE_VALIDATION_PARTENAIRE (insensible à la casse / variante)
        where.add("(etat_qualif = :etat OR etat = :etat OR detail_intervention LIKE '%EN_ATTENTE_VALIDATION_PARTENAIRE%')");
        ps.addValue("etat", TARGET_ETAT);

        if (periode != null && !periode.isBlank()) {
            List<String> periods = Arrays.stream(periode.split(","))
                    .map(s -> s.trim().replace("-", "_"))
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!periods.isEmpty()) {
                where.add("periode IN (:periodes)");
                ps.addValue("periodes", periods);
            }
        }

        if (categorie != null && !categorie.isBlank()) {
            List<String> cats = Arrays.stream(categorie.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!cats.isEmpty()) {
                where.add("categorie IN (:cats)");
                ps.addValue("cats", cats);
            }
        }

        if (q != null && !q.isBlank()) {
            where.add("(id_intervention ILIKE :q OR fyt ILIKE :q OR sous_traitant ILIKE :q)");
            ps.addValue("q", "%" + q.trim() + "%");
        }

        String whereSql = " WHERE " + String.join(" AND ", where);

        Long totalInDb = named().queryForObject("SELECT COUNT(*) FROM interventions" + whereSql, ps, Long.class);

        String paginationSql = "";
        if (page != null && size != null && size > 0) {
            int p = Math.max(0, page);
            int s = Math.min(50_000, size);
            ps.addValue("limit", s);
            ps.addValue("offset", p * s);
            paginationSql = " LIMIT :limit OFFSET :offset";
        }

        List<Map<String, Object>> rawRows = named().queryForList(
                "SELECT id, id_intervention, etat, etat_qualif, periode, categorie, type_presta, sous_traitant, " +
                "departement, oi, fyt, code_cloture, date_intervention, detail_intervention " +
                "FROM interventions" + whereSql +
                " ORDER BY date_intervention DESC NULLS LAST, id DESC" + paginationSql,
                ps
        );

        List<Map<String, Object>> data = new ArrayList<>(rawRows.size());
        for (Map<String, Object> r : rawRows) {
            List<Map<String, Object>> extracted = extractRows(r, allVersions);
            data.addAll(extracted);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("etat_filtre", TARGET_ETAT);
        resp.put("total", totalInDb == null ? 0 : totalInDb);
        resp.put("count", data.size());
        if (page != null && size != null) {
            resp.put("page", Math.max(0, page));
            resp.put("size", size);
        }
        resp.put("columns", COLUMNS);
        resp.put("data", data);
        return resp;
    }

    private List<Map<String, Object>> extractRows(Map<String, Object> dbRow, boolean allVersions) {
        String idIntervention = str(dbRow.get("id_intervention"));
        String fallbackEtat = str(dbRow.get("etat_qualif")).isEmpty() ? str(dbRow.get("etat")) : str(dbRow.get("etat_qualif"));
        String detailJson = (String) dbRow.get("detail_intervention");

        if (detailJson == null || detailJson.isBlank() || "{}".equals(detailJson) || "[]".equals(detailJson)) {
            Map<String, Object> item = emptyRow(idIntervention, fallbackEtat.isEmpty() ? TARGET_ETAT : fallbackEtat);
            item.put("periode", dbRow.get("periode"));
            item.put("categorie", dbRow.get("categorie"));
            item.put("dateIntervention", dbRow.get("date_intervention") != null ? dbRow.get("date_intervention").toString() : "");
            item.put("sousTraitant", str(dbRow.get("sous_traitant")));
            return List.of(item);
        }

        try {
            JsonNode root = objectMapper.readTree(detailJson);

            // Propriétés racine (C01..C11, KX, etc.)
            Map<String, String> props = new HashMap<>();
            if (root.has("proprietes") && root.get("proprietes").isArray()) {
                for (JsonNode p : root.get("proprietes")) {
                    String nom = p.path("nom").asText("").trim();
                    String val = p.path("valeur").asText("");
                    if (!nom.isEmpty()) {
                        props.put(nom, val);
                        props.put(nom.toUpperCase(), val);
                    }
                }
            }

            // Qualifications triées de la plus récente à la plus ancienne
            List<JsonNode> qualifs = new ArrayList<>();
            if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                for (JsonNode q : root.get("qualificationInterventions")) {
                    qualifs.add(q);
                }
            }
            qualifs.sort((a, b) -> b.path("date").asText("").compareTo(a.path("date").asText("")));

            List<JsonNode> targets = new ArrayList<>();
            if (qualifs.isEmpty()) {
                targets.add(objectMapper.createObjectNode());
            } else if (allVersions) {
                for (JsonNode q : qualifs) {
                    if (TARGET_ETAT.equalsIgnoreCase(q.path("etat").asText(""))) {
                        targets.add(q);
                    }
                }
                if (targets.isEmpty()) targets.add(qualifs.get(0));
            } else {
                // La plus récente en EN_ATTENTE_VALIDATION_PARTENAIRE, sinon la plus récente tout court
                JsonNode chosen = qualifs.stream()
                        .filter(q -> TARGET_ETAT.equalsIgnoreCase(q.path("etat").asText("")))
                        .findFirst()
                        .orElse(qualifs.get(0));
                targets.add(chosen);
            }

            List<Map<String, Object>> out = new ArrayList<>(targets.size());
            for (JsonNode q : targets) {
                Map<String, String> factMap = new HashMap<>();
                if (q.has("elementsFacturationCalcule") && q.get("elementsFacturationCalcule").isArray()) {
                    for (JsonNode e : q.get("elementsFacturationCalcule")) {
                        String fName = e.path("designationElementFacturation").asText("").trim();
                        String montant = e.path("montant").asText("0");
                        if (!fName.isEmpty()) {
                            factMap.put(fName, montant);
                            factMap.put(fName.toUpperCase(), montant);
                        }
                    }
                }

                String installation = firstNonEmpty(
                        factMap.get("INSTALLATION"),
                        q.path("INSTALLATION").asText(""),
                        props.get("INSTALLATION"),
                        "0"
                );
                String deplacement = firstNonEmpty(
                        factMap.get("DEPLACEMENT"),
                        q.path("DEPLACEMENT").asText(""),
                        props.get("DEPLACEMENT"),
                        "0"
                );
                String total = firstNonEmpty(
                        q.path("coutIntervention").path("montant").asText(""),
                        factMap.get("TOTAL"),
                        props.get("TOTAL"),
                        "0"
                );

                String etat = firstNonEmpty(
                        q.path("etat").asText(""),
                        fallbackEtat,
                        TARGET_ETAT
                );
                String commentaire = firstNonEmpty(
                        q.path("etapeTraitementFacturation").path("commentaire").asText(""),
                        q.path("commentaire").asText(""),
                        root.path("commentaire").asText("")
                );

                Map<String, Object> row = new LinkedHashMap<>();
                // Colonnes principales demandées (avec clés normalisées + alias avec accents pour compatibilité directe)
                row.put("idIntervention", root.path("identifiant").asText(idIntervention));
                row.put("Etat", etat);
                row.put("État", etat);
                row.put("Commentaire", commentaire);
                row.put("Installation", installation);
                row.put("Deplacement", deplacement);
                row.put("Déplacement", deplacement);
                row.put("Total", total);

                row.put("C04_CCR", pick(props, q, root, "C04_CCR"));
                row.put("C09_KO_PRECEDENT_REPRISE", pick(props, q, root, "C09_KO_PRECEDENT_REPRISE"));
                row.put("C01_TICKET", pick(props, q, root, "C01_TICKET"));
                row.put("C02_ENCHAINEMENT", pick(props, q, root, "C02_ENCHAINEMENT"));
                row.put("C03_TYPE_PBO", pick(props, q, root, "C03_TYPE_PBO"));
                row.put("C05_DOUBLON", firstNonEmpty(
                        pick(props, q, root, "C05_DOUBLON"),
                        pick(props, q, root, "C05_DOUBLONS"),
                        pick(props, q, root, "A2_DOUBLON"),
                        pick(props, q, root, "A2_DOUBLONS")
                ));
                row.put("C06_TYPE_INTER", pick(props, q, root, "C06_TYPE_INTER"));
                row.put("C07_FRAIS", pick(props, q, root, "C07_FRAIS"));
                row.put("C08_GARANTIE", pick(props, q, root, "C08_GARANTIE"));
                row.put("C10_TIRAGES", pick(props, q, root, "C10_TIRAGES"));
                row.put("C11_SUPPORT", pick(props, q, root, "C11_SUPPORT"));
                row.put("commentairePhotos", pick(props, q, root, "commentairePhotos"));
                row.put("sousCategorieControlePhoto", pick(props, q, root, "sousCategorieControlePhoto"));
                row.put("KX", pick(props, q, root, "KX"));

                // Métadonnées utiles en bonus
                row.put("periode", root.path("periode").asText(str(dbRow.get("periode"))));
                row.put("categorie", str(dbRow.get("categorie")));
                row.put("dateIntervention", root.path("dateIntervention").asText(
                        dbRow.get("date_intervention") != null ? dbRow.get("date_intervention").toString() : ""));
                row.put("sousTraitant", root.path("sousTraitant").asText(str(dbRow.get("sous_traitant"))));

                out.add(row);
            }
            return out;
        } catch (Exception e) {
            log.warn("Erreur parsing detail_intervention pour {}: {}", idIntervention, e.getMessage());
            return List.of(emptyRow(idIntervention, fallbackEtat.isEmpty() ? TARGET_ETAT : fallbackEtat));
        }
    }

    private static Map<String, Object> emptyRow(String idIntervention, String etat) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("idIntervention", idIntervention);
        row.put("Etat", etat);
        row.put("État", etat);
        row.put("Commentaire", "");
        row.put("Installation", "0");
        row.put("Deplacement", "0");
        row.put("Déplacement", "0");
        row.put("Total", "0");
        row.put("C04_CCR", "");
        row.put("C09_KO_PRECEDENT_REPRISE", "");
        row.put("C01_TICKET", "");
        row.put("C02_ENCHAINEMENT", "");
        row.put("C03_TYPE_PBO", "");
        row.put("C05_DOUBLON", "");
        row.put("C06_TYPE_INTER", "");
        row.put("C07_FRAIS", "");
        row.put("C08_GARANTIE", "");
        row.put("C10_TIRAGES", "");
        row.put("C11_SUPPORT", "");
        row.put("commentairePhotos", "");
        row.put("sousCategorieControlePhoto", "");
        row.put("KX", "");
        return row;
    }

    private static String pick(Map<String, String> props, JsonNode q, JsonNode root, String key) {
        if (props.containsKey(key) && !props.get(key).isEmpty()) return props.get(key);
        String up = key.toUpperCase();
        if (props.containsKey(up) && !props.get(up).isEmpty()) return props.get(up);
        if (q != null && q.has(key) && !q.get(key).isNull()) return q.path(key).asText("");
        if (root != null && root.has(key) && !root.get(key).isNull()) return root.path(key).asText("");
        return "";
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Export Excel (.xlsx) avec exactement les colonnes demandées. */
    @SuppressWarnings("unchecked")
    public byte[] exportExcel(String periode, String categorie, String q, boolean allVersions) {
        Map<String, Object> res = fetchAttenteValidation(periode, categorie, q, null, null, allVersions);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.getOrDefault("data", List.of());

        List<String> headers = List.of(
                "idIntervention", "État", "Commentaire", "Installation", "Déplacement", "Total",
                "C04_CCR", "C09_KO_PRECEDENT_REPRISE", "C01_TICKET", "C02_ENCHAINEMENT", "C03_TYPE_PBO",
                "C05_DOUBLON", "C06_TYPE_INTER", "C07_FRAIS", "C08_GARANTIE", "C10_TIRAGES", "C11_SUPPORT",
                "commentairePhotos", "sousCategorieControlePhoto", "KX"
        );

        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Attente Validation Partenaire");

            CellStyle headStyle = wb.createCellStyle();
            headStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = wb.createFont();
            font.setBold(true);
            headStyle.setFont(font);

            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(headStyle);
            }

            int rIdx = 1;
            for (Map<String, Object> r : rows) {
                Row row = sheet.createRow(rIdx++);
                for (int i = 0; i < headers.size(); i++) {
                    String h = headers.get(i);
                    String val = str(r.getOrDefault(h, ""));
                    Cell cell = row.createCell(i);
                    if (!val.isEmpty() && val.matches("-?\\d+(\\.\\d+)?")) {
                        try { cell.setCellValue(Double.parseDouble(val)); }
                        catch (Exception e) { cell.setCellValue(val); }
                    } else {
                        cell.setCellValue(val);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Erreur export Excel Attente Validation Partenaire", e);
        }
    }
}
