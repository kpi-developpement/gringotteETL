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

    // 🛡️ L'FIX HNA : On instancie l'ObjectMapper manuellement au lieu d'attendre que Spring le fasse.
    // Ça évite le crash "No qualifying bean of type ObjectMapper".
    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] generateExcelExport(String source, String period) {
        log.info("Démarrage de l'export Excel pour Source: {}, Période: {}", source, period);

        String cleanPeriod = null;
        if (period != null && !period.trim().isEmpty()) {
            cleanPeriod = period.replace("_", "-").replace("-M", "-M");
        }

        String cleanSource = (source == null || source.trim().isEmpty()) ? "ALL" : source;

        List<Intervention> interventions = interventionRepository.findAllForExport(cleanSource, cleanPeriod);

        if (interventions.isEmpty()) {
            throw new RuntimeException("Aucune donnée trouvée pour ces filtres.");
        }

        // 🚀 PASSE 1 : Découverte dynamique des colonnes (Propriétés + Facturation)
        Set<String> propertyKeys = new LinkedHashSet<>();
        Set<String> facturationKeys = new LinkedHashSet<>();

        for (Intervention inv : interventions) {
            if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) continue;
            try {
                JsonNode root = objectMapper.readTree(inv.getDetailIntervention());

                if (root.has("proprietes") && root.get("proprietes").isArray()) {
                    for (JsonNode prop : root.get("proprietes")) {
                        propertyKeys.add(prop.get("nom").asText());
                    }
                }

                if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                    for (JsonNode qualif : root.get("qualificationInterventions")) {
                        if (qualif.has("elementsFacturationCalcule") && qualif.get("elementsFacturationCalcule").isArray()) {
                            for (JsonNode elem : qualif.get("elementsFacturationCalcule")) {
                                facturationKeys.add(elem.get("designationElementFacturation").asText());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Erreur parsing JSON pour intervention ID {}", inv.getIdIntervention());
            }
        }

        // 🚀 PASSE 2 : Génération du Fichier Excel
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
                    "ID Local", "ID EPS", "Source Ingestion", "Période", "Date Intervention",
                    "Technicien", "Sous-Traitant", "Code Clôture", "Code Insee", "Département", "FYT", "ID WKF"
            ));

            headers.addAll(propertyKeys); // Colonnes dynamiques des propriétés

            headers.addAll(Arrays.asList(
                    "N° Version", "Date Version", "État Version", "Type Intervention",
                    "Type Prestation", "Acteur", "Avis", "Commentaire", "Montant Total (€)"
            ));

            for (String factKey : facturationKeys) {
                headers.add("Frais " + factKey + " (€)");
            }

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
                    // Si pas de détails, on écrit juste la base
                    Row row = sheet.createRow(rowIdx++);
                    row.createCell(0).setCellValue(inv.getId());
                    row.createCell(1).setCellValue(inv.getIdIntervention());
                    row.createCell(2).setCellValue(inv.getSourceIngestion() != null ? inv.getSourceIngestion() : "INCONNUE");
                    continue;
                }

                try {
                    JsonNode root = objectMapper.readTree(inv.getDetailIntervention());

                    // Extraction des versions
                    List<JsonNode> versions = new ArrayList<>();
                    if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                        for (JsonNode v : root.get("qualificationInterventions")) {
                            versions.add(v);
                        }
                    }

                    // On inverse l'array pour que V1 soit en premier
                    Collections.reverse(versions);

                    if (versions.isEmpty()) {
                        versions.add(objectMapper.createObjectNode()); // Ligne vide si pas de version
                    }

                    int versionNumber = 1;
                    for (JsonNode version : versions) {
                        Row row = sheet.createRow(rowIdx++);
                        int colIdx = 0;

                        // Base Infos
                        row.createCell(colIdx++).setCellValue(inv.getId());
                        row.createCell(colIdx++).setCellValue(inv.getIdIntervention());
                        row.createCell(colIdx++).setCellValue(inv.getSourceIngestion() != null ? inv.getSourceIngestion() : "INCONNUE");
                        row.createCell(colIdx++).setCellValue(root.path("periode").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("dateIntervention").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("identifiantTechnicien").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("sousTraitant").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("codeCloture").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("codeInsee").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("departement").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("fyt").asText(""));
                        row.createCell(colIdx++).setCellValue(root.path("idWkf").asText(""));

                        // Propriétés
                        Map<String, String> propMap = new HashMap<>();
                        if (root.has("proprietes")) {
                            for (JsonNode p : root.get("proprietes")) {
                                propMap.put(p.path("nom").asText(""), p.path("valeur").asText(""));
                            }
                        }
                        for (String key : propertyKeys) {
                            row.createCell(colIdx++).setCellValue(propMap.getOrDefault(key, ""));
                        }

                        // Version Infos
                        if (!version.isEmpty()) {
                            row.createCell(colIdx++).setCellValue("V" + versionNumber);
                            row.createCell(colIdx++).setCellValue(version.path("date").asText(""));
                            row.createCell(colIdx++).setCellValue(version.path("etat").asText(""));
                            row.createCell(colIdx++).setCellValue(version.path("typeIntervention").asText(""));
                            row.createCell(colIdx++).setCellValue(version.path("typePrestation").asText(""));

                            JsonNode etape = version.path("etapeTraitementFacturation");
                            row.createCell(colIdx++).setCellValue(etape.path("acteur").path("login").asText(""));
                            row.createCell(colIdx++).setCellValue(etape.path("avis").asText(""));
                            row.createCell(colIdx++).setCellValue(etape.path("commentaire").asText(""));

                            row.createCell(colIdx++).setCellValue(version.path("coutIntervention").path("montant").asDouble(0.0));

                            // Facturation
                            Map<String, Double> factMap = new HashMap<>();
                            if (version.has("elementsFacturationCalcule")) {
                                for (JsonNode e : version.get("elementsFacturationCalcule")) {
                                    factMap.put(e.path("designationElementFacturation").asText(""), e.path("montant").asDouble(0.0));
                                }
                            }
                            for (String fKey : facturationKeys) {
                                row.createCell(colIdx++).setCellValue(factMap.getOrDefault(fKey, 0.0));
                            }
                        } else {
                            // Si pas de version, on laisse les colonnes vides
                            row.createCell(colIdx++).setCellValue("V1");
                            colIdx += 8 + facturationKeys.size();
                        }
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