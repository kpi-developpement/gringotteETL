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
        log.info("🚀 Démarrage de l'export Excel (Format Bouygues) | Source: {} | Période: {} | Type: {}", source, period, type);

        String cleanPeriod = (period != null && !period.trim().isEmpty()) ? period.replace("_", "-").replace("-M", "-M") : "";
        String cleanSource = (source == null || source.trim().isEmpty()) ? "ALL" : source;
        String cleanType = (type == null || type.trim().isEmpty()) ? "ALL" : type;

        log.info("🔍 Recherche des IDs correspondants...");
        List<Long> interventionIds = interventionRepository.findIdsForExport(cleanSource, cleanPeriod, cleanType);

        if (interventionIds.isEmpty()) {
            throw new RuntimeException("Aucune donnée trouvée pour ces filtres.");
        }

        log.info("✅ {} interventions trouvées. Découpage en lots de 500...", interventionIds.size());
        List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < interventionIds.size(); i += 500) {
            chunks.add(interventionIds.subList(i, Math.min(i + 500, interventionIds.size())));
        }

        // 🚀 PASSE 1 : DÉCOUVERTE DYNAMIQUE DE TOUTES LES COLONNES
        Set<String> dynamicColumns = new HashSet<>();

        // Liste des champs racines connus dans le JSON
        List<String> rootFields = Arrays.asList(
                "codeCloture", "codeInsee", "dateIntervention", "departement", "fyt",
                "identifiantTechnicien", "oi", "periode", "sousTraitant", "idWkf",
                "idTicket", "referencePm", "idInterventionReseau"
        );

        int currentChunk = 1;
        for (List<Long> chunk : chunks) {
            List<Intervention> interventions = interventionRepository.findAllById(chunk);
            for (Intervention inv : interventions) {
                if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) continue;
                try {
                    JsonNode root = objectMapper.readTree(inv.getDetailIntervention());

                    // 1. Propriétés
                    if (root.has("proprietes") && root.get("proprietes").isArray()) {
                        for (JsonNode prop : root.get("proprietes")) {
                            dynamicColumns.add(prop.get("nom").asText());
                        }
                    }

                    // 2. Qualifications (Champs dynamiques + Facturation)
                    if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                        JsonNode firstQualif = root.get("qualificationInterventions").get(0); // On prend juste la structure

                        // Champs dynamiques (estBranchement, nombreSoudures...)
                        Iterator<String> fieldNames = firstQualif.fieldNames();
                        while (fieldNames.hasNext()) {
                            String fieldName = fieldNames.next();
                            if (!Arrays.asList("_type", "coutIntervention", "date", "elementsFacturationCalcule", "etapeTraitementFacturation", "etat", "identifiant", "typeIntervention", "typePrestation").contains(fieldName)) {
                                dynamicColumns.add(fieldName);
                                dynamicColumns.add(fieldName + "_BRUT"); // On prépare la colonne BRUT
                            }
                        }

                        // Facturation (INSTALLATION, MATERIEL...)
                        if (firstQualif.has("elementsFacturationCalcule") && firstQualif.get("elementsFacturationCalcule").isArray()) {
                            for (JsonNode elem : firstQualif.get("elementsFacturationCalcule")) {
                                String factName = elem.get("designationElementFacturation").asText();
                                dynamicColumns.add(factName);
                                dynamicColumns.add(factName + "_BRUT");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Erreur parsing JSON pour intervention ID {}", inv.getIdIntervention());
                }
            }
        }

        // 🚀 TRI DES COLONNES (Format exact Bouygues)
        List<String> finalHeaders = new ArrayList<>(Arrays.asList(
                "idIntervention", "typeIntervention", "etat", "commentaire", "loginAnalysteQu"
        ));

        List<String> sortedDynamicColumns = new ArrayList<>(dynamicColumns);
        sortedDynamicColumns.addAll(rootFields);
        sortedDynamicColumns.add("TOTAL");
        sortedDynamicColumns.add("TOTAL_BRUT");
        sortedDynamicColumns.add("identifiant"); // Parfois Bouygues met l'identifiant à la fin

        // Tri alphabétique (ignorer la casse)
        sortedDynamicColumns.sort(String.CASE_INSENSITIVE_ORDER);

        // On enlève les doublons au cas où
        for (String col : sortedDynamicColumns) {
            if (!finalHeaders.contains(col)) {
                finalHeaders.add(col);
            }
        }

        log.info("📝 PASSE 2 : Génération du fichier Excel ({} colonnes)...", finalHeaders.size());

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Export_Gringotts");

            // Styles
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setColor(IndexedColors.BLACK.getIndex());
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Écriture de l'en-tête
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < finalHeaders.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(finalHeaders.get(i));
                cell.setCellStyle(headerStyle);
            }

            // Remplissage des données
            int rowIdx = 1;
            currentChunk = 1;

            for (List<Long> chunk : chunks) {
                log.info("   -> Écriture du lot {}/{}", currentChunk++, chunks.size());
                List<Intervention> interventions = interventionRepository.findAllById(chunk);
                interventions.sort((a, b) -> b.getId().compareTo(a.getId()));

                for (Intervention inv : interventions) {
                    Row row = sheet.createRow(rowIdx++);

                    if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) {
                        row.createCell(finalHeaders.indexOf("idIntervention")).setCellValue(inv.getIdIntervention());
                        continue;
                    }

                    try {
                        JsonNode root = objectMapper.readTree(inv.getDetailIntervention());
                        Map<String, String> rowData = new HashMap<>();

                        // 1. Extraction des versions (Current = index 0 | BRUT = dernier index)
                        JsonNode currentQualif = null;
                        JsonNode brutQualif = null;

                        if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray() && root.get("qualificationInterventions").size() > 0) {
                            currentQualif = root.get("qualificationInterventions").get(0);
                            brutQualif = root.get("qualificationInterventions").get(root.get("qualificationInterventions").size() - 1);
                        }

                        // 2. Remplissage du dictionnaire de la ligne
                        rowData.put("idIntervention", root.path("identifiant").asText(""));
                        rowData.put("identifiant", root.path("identifiant").asText("")); // Au cas où

                        if (currentQualif != null) {
                            rowData.put("typeIntervention", currentQualif.path("typeIntervention").asText(inv.getTypeIntervention()));
                            rowData.put("etat", currentQualif.path("etat").asText(inv.getEtat()));

                            JsonNode etape = currentQualif.path("etapeTraitementFacturation");
                            rowData.put("commentaire", etape.path("commentaire").asText(""));
                            rowData.put("loginAnalysteQu", etape.path("acteur").path("login").asText(""));

                            // Champs dynamiques (Normal)
                            Iterator<String> fieldNames = currentQualif.fieldNames();
                            while (fieldNames.hasNext()) {
                                String fieldName = fieldNames.next();
                                if (!Arrays.asList("_type", "coutIntervention", "date", "elementsFacturationCalcule", "etapeTraitementFacturation", "etat", "identifiant", "typeIntervention", "typePrestation").contains(fieldName)) {
                                    rowData.put(fieldName, currentQualif.path(fieldName).asText(""));
                                }
                            }

                            // Facturation (Normal)
                            rowData.put("TOTAL", currentQualif.path("coutIntervention").path("montant").asText("0"));
                            if (currentQualif.has("elementsFacturationCalcule")) {
                                for (JsonNode e : currentQualif.get("elementsFacturationCalcule")) {
                                    rowData.put(e.path("designationElementFacturation").asText(""), e.path("montant").asText("0"));
                                }
                            }
                        }

                        if (brutQualif != null) {
                            // Champs dynamiques (BRUT)
                            Iterator<String> fieldNames = brutQualif.fieldNames();
                            while (fieldNames.hasNext()) {
                                String fieldName = fieldNames.next();
                                if (!Arrays.asList("_type", "coutIntervention", "date", "elementsFacturationCalcule", "etapeTraitementFacturation", "etat", "identifiant", "typeIntervention", "typePrestation").contains(fieldName)) {
                                    rowData.put(fieldName + "_BRUT", brutQualif.path(fieldName).asText(""));
                                }
                            }

                            // Facturation (BRUT)
                            rowData.put("TOTAL_BRUT", brutQualif.path("coutIntervention").path("montant").asText("0"));
                            if (brutQualif.has("elementsFacturationCalcule")) {
                                for (JsonNode e : brutQualif.get("elementsFacturationCalcule")) {
                                    rowData.put(e.path("designationElementFacturation").asText("") + "_BRUT", e.path("montant").asText("0"));
                                }
                            }
                        }

                        // 3. Root fields
                        for (String rootField : rootFields) {
                            rowData.put(rootField, root.path(rootField).asText(""));
                        }

                        // 4. Propriétés
                        if (root.has("proprietes")) {
                            for (JsonNode p : root.get("proprietes")) {
                                rowData.put(p.path("nom").asText(""), p.path("valeur").asText(""));
                            }
                        }

                        // 5. Écriture dans les cellules Excel selon l'ordre exact de finalHeaders
                        for (int i = 0; i < finalHeaders.size(); i++) {
                            String colName = finalHeaders.get(i);
                            String value = rowData.getOrDefault(colName, "");

                            Cell cell = row.createCell(i);

                            // Essayer de convertir en nombre si possible pour un Excel propre
                            try {
                                if (!value.isEmpty() && value.matches("-?\\d+(\\.\\d+)?")) {
                                    cell.setCellValue(Double.parseDouble(value));
                                } else {
                                    cell.setCellValue(value);
                                }
                            } catch (Exception e) {
                                cell.setCellValue(value);
                            }
                        }

                    } catch (Exception e) {
                        log.warn("Erreur écriture Excel pour ID {}", inv.getIdIntervention());
                    }
                }
            }

            workbook.write(out);
            log.info("✅ Export Excel généré avec succès !");
            return out.toByteArray();

        } catch (Throwable t) {
            log.error("❌ ERREUR FATALE LORS DE LA GÉNÉRATION EXCEL : ", t);
            throw new RuntimeException("Erreur fatale de génération Excel", t);
        }
    }
}