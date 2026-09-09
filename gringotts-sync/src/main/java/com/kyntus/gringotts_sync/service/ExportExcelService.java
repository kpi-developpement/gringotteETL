package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyntus.gringotts_sync.domain.Intervention;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportExcelService {

    private final InterventionRepository interventionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] generateExcelExport(String source, String period, String type) {
        log.info("Démarrage de l'export Excel | Source: {} | Période: {} | Type: {}", source, period, type);

        String cleanPeriod = null;
        if (period != null && !period.trim().isEmpty()) {
            cleanPeriod = period.replace("_", "-").replace("-M", "-M");
        }

        String cleanSource = (source == null || source.trim().isEmpty()) ? "ALL" : source;
        String cleanType = (type == null || type.trim().isEmpty()) ? "ALL" : type;

        List<Intervention> interventions = interventionRepository.findAllForExport(cleanSource, cleanPeriod, cleanType);

        if (interventions.isEmpty()) {
            throw new RuntimeException("Aucune donnée trouvée pour ces filtres.");
        }

        // 🚀 PASSE 1 : DÉCOUVERTE DYNAMIQUE DES COLONNES
        Set<String> propertyKeys = new LinkedHashSet<>();
        Set<String> facturationKeys = new LinkedHashSet<>();
        Set<String> qualifKeys = new LinkedHashSet<>(); // Pour les estBranchement, nombreSoudures, etc.

        for (Intervention inv : interventions) {
            if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) continue;
            try {
                JsonNode root = objectMapper.readTree(inv.getDetailIntervention());

                // Propriétés
                if (root.has("proprietes") && root.get("proprietes").isArray()) {
                    for (JsonNode prop : root.get("proprietes")) {
                        propertyKeys.add(prop.get("nom").asText());
                    }
                }

                // Qualifications (Facturation + Champs dynamiques)
                if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                    for (JsonNode qualif : root.get("qualificationInterventions")) {

                        // Facturation
                        if (qualif.has("elementsFacturationCalcule") && qualif.get("elementsFacturationCalcule").isArray()) {
                            for (JsonNode elem : qualif.get("elementsFacturationCalcule")) {
                                facturationKeys.add(elem.get("designationElementFacturation").asText());
                            }
                        }

                        // Champs dynamiques (estGarantie, nbSoudures, etc.)
                        Iterator<String> fieldNames = qualif.fieldNames();
                        while (fieldNames.hasNext()) {
                            String fieldName = fieldNames.next();
                            // On ignore les objets complexes et les champs de base qu'on gère manuellement
                            if (!Arrays.asList("_type", "coutIntervention", "date", "elementsFacturationCalcule", "etapeTraitementFacturation", "etat", "identifiant", "typeIntervention", "typePrestation").contains(fieldName)) {
                                qualifKeys.add(fieldName);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Erreur parsing JSON pour intervention ID {}", inv.getIdIntervention());
            }
        }

        // 🚀 PASSE 2 : GÉNÉRATION DU FICHIER EXCEL
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Interventions");

            // Styles
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Construction de la ligne d'en-tête
            List<String> headers = new ArrayList<>(Arrays.asList(
                    "idIntervention", "typeIntervention", "etat", "commentaire", "acteur", "avis"
            ));

            headers.addAll(propertyKeys); // Ex: REF_PBO_OI, C1_NOK_POSE...

            headers.addAll(Arrays.asList(
                    "codeCloture", "codeInsee", "dateIntervention", "departement", "fyt", "idWkf", "idTicket", "referencePm", "idInterventionReseau", "identifiantTechnicien", "oi", "periode", "sousTraitant"
            ));

            // Ajout des champs dynamiques (Normal + BRUT)
            for (String qKey : qualifKeys) {
                headers.add(qKey);
                headers.add(qKey + "_BRUT");
            }

            // Ajout de la facturation (Normal + BRUT)
            headers.add("TOTAL");
            headers.add("TOTAL_BRUT");
            for (String fKey : facturationKeys) {
                headers.add(fKey);
                headers.add(fKey + "_BRUT");
            }

            headers.addAll(Arrays.asList("N_Version", "Date_Version", "Source_Ingestion"));

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            // Remplissage des données
            int rowIdx = 1;
            for (Intervention inv : interventions) {
                if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) {
                    continue; // On ignore les lignes vides
                }

                try {
                    JsonNode root = objectMapper.readTree(inv.getDetailIntervention());

                    List<JsonNode> versions = new ArrayList<>();
                    if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                        for (JsonNode v : root.get("qualificationInterventions")) {
                            versions.add(v);
                        }
                    }

                    // 🛡️ INVERSION : V1 en premier, V2, V3...
                    Collections.reverse(versions);

                    if (versions.isEmpty()) continue;

                    // 🛡️ SAUVEGARDE DE LA V1 POUR LES COLONNES _BRUT
                    JsonNode v1 = versions.get(0);
                    double v1Total = v1.path("coutIntervention").path("montant").asDouble(0.0);

                    Map<String, Double> v1FactMap = new HashMap<>();
                    if (v1.has("elementsFacturationCalcule")) {
                        for (JsonNode e : v1.get("elementsFacturationCalcule")) {
                            v1FactMap.put(e.path("designationElementFacturation").asText(""), e.path("montant").asDouble(0.0));
                        }
                    }

                    Map<String, String> v1QualifMap = new HashMap<>();
                    for (String qKey : qualifKeys) {
                        v1QualifMap.put(qKey, v1.path(qKey).asText(""));
                    }

                    // 🛡️ BOUCLE SUR TOUTES LES VERSIONS (1 Ligne Excel par Version)
                    int versionNumber = 1;
                    for (JsonNode version : versions) {
                        Row row = sheet.createRow(rowIdx++);
                        int colIdx = 0;

                        // 1. Base Infos
                        row.createCell(colIdx++).setCellValue(inv.getIdIntervention());
                        row.createCell(colIdx++).setCellValue(version.path("typeIntervention").asText(inv.getTypeIntervention()));
                        row.createCell(colIdx++).setCellValue(version.path("etat").asText(inv.getEtat()));

                        JsonNode etape = version.path("etapeTraitementFacturation");
                        row.createCell(colIdx++).setCellValue(etape.path("commentaire").asText(""));
                        row.createCell(colIdx++).setCellValue(etape.path("acteur").path("login").asText(""));
                        row.createCell(colIdx++).setCellValue(etape.path("avis").asText(""));

                        // 2. Propriétés
                        Map<String, String> propMap = new HashMap<>();
                        if (root.has("proprietes")) {
                            for (JsonNode p : root.get("proprietes")) {
                                propMap.put(p.path("nom").asText(""), p.path("valeur").asText(""));
                            }
                        }
                        for (String key : propertyKeys) {
                            row.createCell(colIdx++).setCellValue(propMap.getOrDefault(key, ""));
                        }

                        // 3. Infos Racines
                        row.createCell(colIdx++).setCellValue(root.path("codeCloture").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("codeInsee").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("dateIntervention").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("departement").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("fyt").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("idWkf").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("idTicket").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("referencePm").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("idInterventionReseau").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("identifiantTechnicien").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("oi").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("periode").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("sousTraitant").asText(""));

                        // 4. Champs Dynamiques (Normal + BRUT)
                        for (String qKey : qualifKeys) {
                            row.createCell(colIdx++).setCellValue(version.path(qKey).asText(""));
                            row.createCell(colIdx++).setCellValue(v1QualifMap.getOrDefault(qKey, "")); // Valeur V1
                        }

                        // 5. Facturation (Normal + BRUT)
                        row.createCell(colIdx++).setCellValue(version.path("coutIntervention").path("montant").asDouble(0.0));
                        row.createCell(colIdx++).setCellValue(v1Total); // Valeur V1

                        Map<String, Double> currentFactMap = new HashMap<>();
                        if (version.has("elementsFacturationCalcule")) {
                            for (JsonNode e : version.get("elementsFacturationCalcule")) {
                                currentFactMap.put(e.path("designationElementFacturation").asText(""), e.path("montant").asDouble(0.0));
                            }
                        }
                        for (String fKey : facturationKeys) {
                            row.createCell(colIdx++).setCellValue(currentFactMap.getOrDefault(fKey, 0.0));
                            row.createCell(colIdx++).setCellValue(v1FactMap.getOrDefault(fKey, 0.0)); // Valeur V1
                        }

                        // 6. Méta-données
                        row.createCell(colIdx++).setCellValue("V" + versionNumber);
                        row.createCell(colIdx++).setCellValue(version.path("date").asText(""));
                        row.createCell(colIdx++).setCellValue(inv.getSourceIngestion() != null ? inv.getSourceIngestion() : "INCONNUE");

                        versionNumber++;
                    }

                } catch (Exception e) {
                    log.warn("Erreur écriture Excel pour ID {}", inv.getIdIntervention());
                }
            }

            workbook.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("Erreur lors de la génération du fichier Excel", e);
            throw new RuntimeException("Erreur de génération Excel");
        }
    }
}