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
 * API externe : extraction exclusive des EPS SAV dont l'ÉTAT FINAL (dernière version de qualification par date)
 * est EN_ATTENTE_VALIDATION_PARTENAIRE, avec dédoublonnage par id_intervention (on garde la ligne la plus récente).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartenaireApiService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile NamedParameterJdbcTemplate named;

    public static final String TARGET_ETAT = "EN_ATTENTE_VALIDATION_PARTENAIRE";
    public static final String TARGET_CATEGORIE = "SAV";

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
     * Récupère uniquement les EPS SAV dont la DERNIÈRE version (par date de qualification / date d'ajout)
     * est en état final EN_ATTENTE_VALIDATION_PARTENAIRE.
     */
    public Map<String, Object> fetchAttenteValidation(String periode, String categorie, String q,
                                                      Integer page, Integer size) {
        MapSqlParameterSource ps = new MapSqlParameterSource();
        List<String> where = new ArrayList<>();

        // 1) Pré-filtre SQL : SAV + EN_ATTENTE_VALIDATION_PARTENAIRE (sur colonnes indexées OU fallback dans le JSON brut si pas encore backfillé)
        where.add("(categorie = 'SAV' OR (categorie IS NULL AND (type_intervention ILIKE '%SAV%' OR detail_intervention LIKE '%QualificationSAV%')))");
        where.add("(etat_qualif = :etat OR (etat_qualif IS NULL AND (etat = :etat OR detail_intervention LIKE '%EN_ATTENTE_VALIDATION_PARTENAIRE%')))");
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

        if (q != null && !q.isBlank()) {
            where.add("(id_intervention ILIKE :q OR fyt ILIKE :q OR sous_traitant ILIKE :q)");
            ps.addValue("q", "%" + q.trim() + "%");
        }

        String whereSql = " WHERE " + String.join(" AND ", where);

        // DISTINCT ON (id_intervention) : s'il y a plusieurs lignes pour le même EPS en base,
        // on prend uniquement la dernière version ajoutée/modifiée (updated_at / date_modification_etat / id DESC).
        String sql = "SELECT DISTINCT ON (id_intervention) " +
                "id, id_intervention, etat, etat_qualif, periode, categorie, type_presta, sous_traitant, " +
                "departement, oi, fyt, code_cloture, date_intervention, date_modification_etat, updated_at, detail_intervention " +
                "FROM interventions" + whereSql +
                " ORDER BY id_intervention, COALESCE(date_modification_etat, updated_at, created_at) DESC NULLS LAST, id DESC";

        List<Map<String, Object>> rawRows = named().queryForList(sql, ps);

        // 2) Vérification stricte de la DERNIÈRE version de qualification dans le JSON Bytel :
        //    - Doit être un SAV (QualificationSAV)
        //    - L'état de la qualification ayant la date la plus récente doit être EN_ATTENTE_VALIDATION_PARTENAIRE
        List<Map<String, Object>> verified = new ArrayList<>(rawRows.size());
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> extracted = extractFinalSavAttenteRow(r);
            if (extracted != null) {
                verified.add(extracted);
            }
        }

        // Tri final : les plus récentes en premier (par date de dernière qualification / date intervention)
        verified.sort((a, b) -> str(b.get("dateDerniereQualification")).compareTo(str(a.get("dateDerniereQualification"))));

        int totalVerified = verified.size();
        List<Map<String, Object>> pagedData = verified;
        if (page != null && size != null && size > 0) {
            int p = Math.max(0, page);
            int fromIdx = Math.min(totalVerified, p * size);
            int toIdx = Math.min(totalVerified, fromIdx + size);
            pagedData = verified.subList(fromIdx, toIdx);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("categorie_filtre", TARGET_CATEGORIE);
        resp.put("etat_final_filtre", TARGET_ETAT);
        resp.put("total", totalVerified);
        resp.put("count", pagedData.size());
        if (page != null && size != null) {
            resp.put("page", Math.max(0, page));
            resp.put("size", size);
        }
        resp.put("columns", COLUMNS);
        resp.put("data", pagedData);
        return resp;
    }

    /**
     * Extrait la ligne uniquement si la DERNIÈRE version de qualification (date max) est SAV
     * et que son état final est EN_ATTENTE_VALIDATION_PARTENAIRE. Retourne null sinon.
     */
    private Map<String, Object> extractFinalSavAttenteRow(Map<String, Object> dbRow) {
        String idIntervention = str(dbRow.get("id_intervention"));
        String dbCat = str(dbRow.get("categorie"));
        String dbEtatQualif = str(dbRow.get("etat_qualif"));
        String dbEtat = str(dbRow.get("etat"));
        String detailJson = (String) dbRow.get("detail_intervention");

        // Cas où le détail JSON n'est pas encore enrichi mais la ligne en base est déjà taguée SAV + EN_ATTENTE_VALIDATION_PARTENAIRE
        if (detailJson == null || detailJson.isBlank() || "{}".equals(detailJson) || "[]".equals(detailJson)) {
            boolean isSav = "SAV".equalsIgnoreCase(dbCat);
            String finalSt = !dbEtatQualif.isEmpty() ? dbEtatQualif : dbEtat;
            if (isSav && TARGET_ETAT.equalsIgnoreCase(finalSt)) {
                Map<String, Object> item = emptyRow(idIntervention, TARGET_ETAT);
                item.put("periode", str(dbRow.get("periode")));
                item.put("categorie", "SAV");
                item.put("dateIntervention", dbRow.get("date_intervention") != null ? dbRow.get("date_intervention").toString() : "");
                item.put("dateDerniereQualification", dbRow.get("date_modification_etat") != null ? dbRow.get("date_modification_etat").toString() : "");
                item.put("sousTraitant", str(dbRow.get("sous_traitant")));
                return item;
            }
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(detailJson);

            // On cherche LA dernière version dans qualificationInterventions[] en comparant le champ "date"
            // (et à date égale, le dernier élément du tableau = dernier ajouté chez Bytel).
            JsonNode latestQualif = null;
            String bestDate = "";
            if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                for (JsonNode q : root.get("qualificationInterventions")) {
                    String dt = q.path("date").asText("");
                    if (latestQualif == null || dt.compareTo(bestDate) >= 0) {
                        latestQualif = q;
                        bestDate = dt;
                    }
                }
            }

            if (latestQualif == null || latestQualif.isEmpty()) {
                return null;
            }

            // 1) Vérification stricte que la dernière qualification est bien un SAV
            String qType = latestQualif.path("_type").asText("");
            boolean isSav = qType.contains("SAV") || "SAV".equalsIgnoreCase(dbCat);
            if (!isSav) {
                return null;
            }

            // 2) Vérification stricte que l'état de cette DERNIÈRE qualification (état final) est EN_ATTENTE_VALIDATION_PARTENAIRE
            String finalEtat = latestQualif.path("etat").asText("");
            if (!TARGET_ETAT.equalsIgnoreCase(finalEtat)) {
                return null;
            }

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

            // Éléments de facturation de la dernière qualification
            Map<String, String> factMap = new HashMap<>();
            if (latestQualif.has("elementsFacturationCalcule") && latestQualif.get("elementsFacturationCalcule").isArray()) {
                for (JsonNode e : latestQualif.get("elementsFacturationCalcule")) {
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
                    latestQualif.path("INSTALLATION").asText(""),
                    props.get("INSTALLATION"),
                    "0"
            );
            String deplacement = firstNonEmpty(
                    factMap.get("DEPLACEMENT"),
                    latestQualif.path("DEPLACEMENT").asText(""),
                    props.get("DEPLACEMENT"),
                    "0"
            );
            String total = firstNonEmpty(
                    latestQualif.path("coutIntervention").path("montant").asText(""),
                    factMap.get("TOTAL"),
                    props.get("TOTAL"),
                    "0"
            );

            String commentaire = firstNonEmpty(
                    latestQualif.path("etapeTraitementFacturation").path("commentaire").asText(""),
                    latestQualif.path("commentaire").asText(""),
                    root.path("commentaire").asText("")
            );

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("idIntervention", root.path("identifiant").asText(idIntervention));
            row.put("Etat", finalEtat);
            row.put("État", finalEtat);
            row.put("Commentaire", commentaire);
            row.put("Installation", installation);
            row.put("Deplacement", deplacement);
            row.put("Déplacement", deplacement);
            row.put("Total", total);

            row.put("C04_CCR", pick(props, latestQualif, root, "C04_CCR"));
            row.put("C09_KO_PRECEDENT_REPRISE", pick(props, latestQualif, root, "C09_KO_PRECEDENT_REPRISE"));
            row.put("C01_TICKET", pick(props, latestQualif, root, "C01_TICKET"));
            row.put("C02_ENCHAINEMENT", pick(props, latestQualif, root, "C02_ENCHAINEMENT"));
            row.put("C03_TYPE_PBO", pick(props, latestQualif, root, "C03_TYPE_PBO"));
            row.put("C05_DOUBLON", firstNonEmpty(
                    pick(props, latestQualif, root, "C05_DOUBLON"),
                    pick(props, latestQualif, root, "C05_DOUBLONS"),
                    pick(props, latestQualif, root, "A2_DOUBLON"),
                    pick(props, latestQualif, root, "A2_DOUBLONS")
            ));
            row.put("C06_TYPE_INTER", pick(props, latestQualif, root, "C06_TYPE_INTER"));
            row.put("C07_FRAIS", pick(props, latestQualif, root, "C07_FRAIS"));
            row.put("C08_GARANTIE", pick(props, latestQualif, root, "C08_GARANTIE"));
            row.put("C10_TIRAGES", pick(props, latestQualif, root, "C10_TIRAGES"));
            row.put("C11_SUPPORT", pick(props, latestQualif, root, "C11_SUPPORT"));
            row.put("commentairePhotos", pick(props, latestQualif, root, "commentairePhotos"));
            row.put("sousCategorieControlePhoto", pick(props, latestQualif, root, "sousCategorieControlePhoto"));
            row.put("KX", pick(props, latestQualif, root, "KX"));

            // Infos utiles sur la version finale
            row.put("periode", root.path("periode").asText(str(dbRow.get("periode"))));
            row.put("categorie", "SAV");
            row.put("dateIntervention", root.path("dateIntervention").asText(
                    dbRow.get("date_intervention") != null ? dbRow.get("date_intervention").toString() : ""));
            row.put("dateDerniereQualification", bestDate);
            row.put("sousTraitant", root.path("sousTraitant").asText(str(dbRow.get("sous_traitant"))));

            return row;
        } catch (Exception e) {
            log.warn("Erreur parsing detail_intervention pour {}: {}", idIntervention, e.getMessage());
            return null;
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

    /** Export Excel (.xlsx) avec exactement les colonnes demandées (SAV + État final EN_ATTENTE_VALIDATION_PARTENAIRE). */
    @SuppressWarnings("unchecked")
    public byte[] exportExcel(String periode, String categorie, String q) {
        Map<String, Object> res = fetchAttenteValidation(periode, categorie, q, null, null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.getOrDefault("data", List.of());

        List<String> headers = List.of(
                "idIntervention", "État", "Commentaire", "Installation", "Déplacement", "Total",
                "C04_CCR", "C09_KO_PRECEDENT_REPRISE", "C01_TICKET", "C02_ENCHAINEMENT", "C03_TYPE_PBO",
                "C05_DOUBLON", "C06_TYPE_INTER", "C07_FRAIS", "C08_GARANTIE", "C10_TIRAGES", "C11_SUPPORT",
                "commentairePhotos", "sousCategorieControlePhoto", "KX"
        );

        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("SAV Attente Validation");

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
            throw new RuntimeException("Erreur export Excel SAV Attente Validation Partenaire", e);
        }
    }
}
