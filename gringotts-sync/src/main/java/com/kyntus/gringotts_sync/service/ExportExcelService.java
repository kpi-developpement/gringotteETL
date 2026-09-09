package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyntus.gringotts_sync.domain.Intervention;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
        log.info("🚀 Démarrage de l'export Excel | Source: {} | Période: {} | Type: {}", source, period, type);

        String cleanPeriod = (period != null && !period.trim().isEmpty()) ? period.replace("_", "-").replace("-M", "-M") : "";
        String cleanSource = (source == null || source.trim().isEmpty()) ? "ALL" : source;
        String cleanType = (type == null || type.trim().isEmpty()) ? "ALL" : type;

        Set<String> propertyKeys = new LinkedHashSet<>();
        Set<String> facturationKeys = new LinkedHashSet<>();
        Set<String> qualifKeys = new LinkedHashSet<>();

        int page = 0;
        int size = 500; // 🛡️ L'FIX HNA : On traite 500 lignes par 500 lignes pour ne jamais saturer la RAM
        Page<Intervention> interventionPage;

        log.info("🔍 PASSE 1 : Découverte dynamique des colonnes...");
        do {
            interventionPage = interventionRepository.findAllForExport(cleanSource, cleanPeriod, cleanType, PageRequest.of(page, size));

            for (Intervention inv : interventionPage.getContent()) {
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
                            Iterator<String> fieldNames = qualif.fieldNames();
                            while (fieldNames.hasNext()) {
                                String fieldName = fieldNames.next();
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
            page++;
        } while (interventionPage.hasNext());

        if (propertyKeys.isEmpty() && facturationKeys.isEmpty() && qualifKeys.isEmpty() && page == 1 && interventionPage.getContent().isEmpty()) {
            throw new RuntimeException("Aucune donnée trouvée pour ces filtres.");
        }

        log.info("📝 PASSE 2 : Génération du fichier Excel en cours...");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Interventions");

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            List<String> headers = new ArrayList<>(Arrays.asList(
                    "ID Local", "ID EPS", "Source Ingestion", "Période", "Date Intervention",
                    "Technicien", "Sous-Traitant", "Code Clôture", "Code Insee", "Département", "FYT", "ID WKF", "ID Ticket", "Ref PM", "ID Reseau"
            ));

            headers.addAll(propertyKeys);

            headers.addAll(Arrays.asList(
                    "N° Version", "Date Version", "État Version", "Type Intervention",
                    "Type Prestation", "Acteur", "Avis", "Commentaire", "Montant Total (€)", "Montant Total BRUT (€)"
            ));

            for (String fKey : facturationKeys) {
                headers.add("Frais " + fKey + " (€)");
                headers.add("Frais " + fKey + " BRUT (€)");
            }

            for (String qKey : qualifKeys) {
                headers.add(qKey);
                headers.add(qKey + "_BRUT");
            }

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            page = 0;
            int rowIdx = 1;

            do {
                interventionPage = interventionRepository.findAllForExport(cleanSource, cleanPeriod, cleanType, PageRequest.of(page, size));

                for (Intervention inv : interventionPage.getContent()) {
                    if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) {
                        Row row = sheet.createRow(rowIdx++);
                        row.createCell(0).setCellValue(inv.getId());
                        row.createCell(1).setCellValue(inv.getIdIntervention());
                        row.createCell(2).setCellValue(inv.getSourceIngestion() != null ? inv.getSourceIngestion() : "INCONNUE");
                        continue;
                    }

                    try {
                        JsonNode root = objectMapper.readTree(inv.getDetailIntervention());

                        List<JsonNode> versions = new ArrayList<>();
                        if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                            for (JsonNode v : root.get("qualificationInterventions")) {
                                versions.add(v);
                            }
                        }

                        Collections.reverse(versions);

                        if (versions.isEmpty()) {
                            versions.add(objectMapper.createObjectNode());
                        }

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

                        int versionNumber = 1;
                        for (JsonNode version : versions) {
                            Row row = sheet.createRow(rowIdx++);
                            int colIdx = 0;

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
                            row.createCell(colIdx++).setCellValue(root.path("idTicket").asText(""));
                            row.createCell(colIdx++).setCellValue(root.path("referencePm").asText(""));
                            row.createCell(colIdx++).setCellValue(root.path("idInterventionReseau").asText(""));

                            Map<String, String> propMap = new HashMap<>();
                            if (root.has("proprietes")) {
                                for (JsonNode p : root.get("proprietes")) {
                                    propMap.put(p.path("nom").asText(""), p.path("valeur").asText(""));
                                }
                            }
                            for (String key : propertyKeys) {
                                row.createCell(colIdx++).setCellValue(propMap.getOrDefault(key, ""));
                            }

                            if (!version.isEmpty()) {
                                row.createCell(colIdx++).setCellValue("V" + versionNumber);
                                row.createCell(colIdx++).setCellValue(version.path("date").asText(""));
                                row.createCell(colIdx++).setCellValue(version.path("etat").asText(inv.getEtat()));
                                row.createCell(colIdx++).setCellValue(version.path("typeIntervention").asText(inv.getTypeIntervention()));
                                row.createCell(colIdx++).setCellValue(version.path("typePrestation").asText(""));

                                JsonNode etape = version.path("etapeTraitementFacturation");
                                row.createCell(colIdx++).setCellValue(etape.path("acteur").path("login").asText(""));
                                row.createCell(colIdx++).setCellValue(etape.path("avis").asText(""));
                                row.createCell(colIdx++).setCellValue(etape.path("commentaire").asText(""));

                                row.createCell(colIdx++).setCellValue(version.path("coutIntervention").path("montant").asDouble(0.0));
                                row.createCell(colIdx++).setCellValue(v1Total);

                                Map<String, Double> currentFactMap = new HashMap<>();
                                if (version.has("elementsFacturationCalcule")) {
                                    for (JsonNode e : version.get("elementsFacturationCalcule")) {
                                        currentFactMap.put(e.path("designationElementFacturation").asText(""), e.path("montant").asDouble(0.0));
                                    }
                                }
                                for (String fKey : facturationKeys) {
                                    row.createCell(colIdx++).setCellValue(currentFactMap.getOrDefault(fKey, 0.0));
                                    row.createCell(colIdx++).setCellValue(v1FactMap.getOrDefault(fKey, 0.0));
                                }

                                for (String qKey : qualifKeys) {
                                    row.createCell(colIdx++).setCellValue(version.path(qKey).asText(""));
                                    row.createCell(colIdx++).setCellValue(v1QualifMap.getOrDefault(qKey, ""));
                                }
                            } else {
                                row.createCell(colIdx++).setCellValue("V1");
                            }
                            versionNumber++;
                        }
                    } catch (Exception e) {
                        log.warn("Erreur écriture Excel pour ID {}", inv.getIdIntervention());
                    }
                }
                page++;
            } while (interventionPage.hasNext());

            workbook.write(out);
            log.info("✅ Export Excel généré avec succès !");
            return out.toByteArray();

        } catch (Throwable t) {
            log.error("❌ ERREUR FATALE LORS DE LA GÉNÉRATION EXCEL : ", t);
            throw new RuntimeException("Erreur fatale de génération Excel", t);
        }
    }
}