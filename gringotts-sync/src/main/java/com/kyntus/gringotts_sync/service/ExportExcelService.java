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

        // 🛡️ L'FIX HNA : La liste exhaustive des colonnes Bouygues pour forcer leur affichage
        Set<String> allColumns = new HashSet<>(Arrays.asList(
                "codeCloture", "codeInsee", "dateIntervention", "departement", "fyt", "idWkf", "idTicket",
                "referencePm", "idInterventionReseau", "identifiantTechnicien", "oi", "periode", "sousTraitant",
                "mainteneurIdentifiant", "A1_CLES_CTRL", "A2_DOUBLONS", "A2_DOUBLON", "A3_FIN_CMD", "ANALYSE_PHOTO",
                "B1_FORMAT", "B1_FORMAT_CHAMPS", "C01_TICKET", "C08_GARANTIE", "C1_DEPLACEMENT", "C1_NOK_POSE",
                "C1_PHOTO", "C2_DOUTE_POS", "C3_DEPORT", "CXX_GLOBAL", "CHAMP_1", "CHAMP_2", "CHAMP_3", "CHAMP_4",
                "CHAMP_5", "CHAMP_6", "CODE_DECHARGE_TECH", "CODE_FACTURE", "CODE_CLOTURE_PREC", "CONTROLE_GLOBAL",
                "DATE_ACTIVATION", "DATE_CLOTURE_TICKET", "DATE_OUVERTURE_TICKET", "DATE_SAV_PREC_DER_CR", "E1_MES",
                "FLAG_1", "FLAG_2", "FLAG_3", "FLAG_4", "FLAG_5", "FLAG_6", "FLAG_DEVIS_TRAVAUX", "FLAG_ECRASEMENT",
                "GX", "HX", "IX", "JX", "KX", "ID_RACC", "ID_TICKET_PREC", "NB_CLIENTS_ACTV", "NB_COUPL_1", "NB_COUPL_2",
                "NB_COUPL_3", "PRESTATION_OI", "REF_PBO_CR", "REF_PBO_OI", "REF_PTO_CR", "REF_PTO_OI",
                "SOUS_TRAITANT_ID_SAV_PREC", "SOUS_TRAITANT_RACC", "STATUT_INTERVENTION_PREC", "STATUT_INTERVENTION_SUIV",
                "TYPE_DESSERTE_CR", "TYPE_DESSERTE_OI", "TYPE_INSTALLATION", "TYPE_OFFRE", "TYPE_ZONE", "categorie",
                "categorieRaccordementLogementAnalyse", "commentaireFinalBytel", "commentairePhotos",
                "TOTAL", "TOTAL_BRUT"
        ));

        // Ajout des champs de qualification et facturation avec leur version _BRUT
        String[] predefinedQualifs = {
                "estBranchement", "estDiagnosticInternet", "estDiagnosticTelephone", "estDiagnosticTv", "estDiagnosticWifi",
                "estFournisseurBytel", "estInterventionComplexe", "estInterventionDimanche", "estMiseEnRelation", "estMiseEnService",
                "estPreAppel", "estZoneComplexe", "longueurGoulottes", "montantDevis", "nombreRepeteursPoses", "presenceNacelle",
                "estDeplacementFacturable", "estGarantieRaccordement", "estGarantieSav", "estGti4h", "estGti8h", "estInterventionJPlus1",
                "estKroe", "estSAVGroupe", "nombreClientsRetablis", "nombreJarretiere", "estPresenceOi",
                "nb145a256clientsSoudures", "nb17a32clientsAudit", "nb1a32clientsConformite", "nb1a48clientsSoudures", "nb1a4clientsAudit",
                "nb33a64clientsAudit", "nb33a64clientsConformite", "nb49a96clientsSoudures", "nb5a8clientsAudit", "nb65a128clientsAudit",
                "nb65a128clientsConformite", "nb97a144clientsSoudures", "nb9a16clientsAudit", "nombreChangementFibreAlimentation",
                "nombreChangementModules", "nombreCheckEtatGlobalPbo", "nombreCheckEtatGlobalPm", "nombreDesaturations", "nombreFixModules",
                "nombreJarretieresReprises", "nombreMesuresSignalCoupleurs", "nombrePositionsReleves", "nombreRelevesPortsCollectes",
                "nombreSoudures", "nombreSouduresFibreAlimentation", "nombreTestContinuitePmPbo", "nombreTestsSynchroOnt",
                "responsabiliteFinale", "tarifMaterielUtilise", "LOGISTIQUE", "SUPPORT", "DEPLACEMENT", "GOULOTTE", "MATERIEL", "MES", "INSTALLATION", "JARRETIERES"
        };

        for(String q : predefinedQualifs) {
            allColumns.add(q);
            allColumns.add(q + "_BRUT");
        }

        log.info("✅ {} interventions trouvées. Découpage en lots de 500...", interventionIds.size());
        List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < interventionIds.size(); i += 500) {
            chunks.add(interventionIds.subList(i, Math.min(i + 500, interventionIds.size())));
        }

        // 🚀 PASSE 1 : Découverte dynamique (Au cas où Bouygues ajoute un nouveau champ non listé)
        int currentChunk = 1;
        for (List<Long> chunk : chunks) {
            List<Intervention> interventions = interventionRepository.findAllById(chunk);
            for (Intervention inv : interventions) {
                if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) continue;
                try {
                    JsonNode root = objectMapper.readTree(inv.getDetailIntervention());

                    if (root.has("proprietes") && root.get("proprietes").isArray()) {
                        for (JsonNode prop : root.get("proprietes")) {
                            allColumns.add(prop.get("nom").asText());
                        }
                    }

                    if (root.has("qualificationInterventions") && root.get("qualificationInterventions").isArray()) {
                        JsonNode firstQualif = root.get("qualificationInterventions").get(0);

                        Iterator<String> fieldNames = firstQualif.fieldNames();
                        while (fieldNames.hasNext()) {
                            String fieldName = fieldNames.next();
                            if (!Arrays.asList("_type", "coutIntervention", "date", "elementsFacturationCalcule", "etapeTraitementFacturation", "etat", "identifiant", "typeIntervention", "typePrestation").contains(fieldName)) {
                                allColumns.add(fieldName);
                                allColumns.add(fieldName + "_BRUT");
                            }
                        }

                        if (firstQualif.has("elementsFacturationCalcule") && firstQualif.get("elementsFacturationCalcule").isArray()) {
                            for (JsonNode elem : firstQualif.get("elementsFacturationCalcule")) {
                                String factName = elem.get("designationElementFacturation").asText();
                                allColumns.add(factName);
                                allColumns.add(factName + "_BRUT");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Erreur parsing JSON pour intervention ID {}", inv.getIdIntervention());
                }
            }
        }

        // 🚀 TRI DES COLONNES (L'Ordre Exact de Bouygues)
        List<String> finalHeaders = new ArrayList<>(Arrays.asList(
                "idIntervention", "typeIntervention", "etat", "commentaire", "loginAnalysteQu"
        ));

        List<String> sortedColumns = new ArrayList<>(allColumns);
        sortedColumns.sort(String.CASE_INSENSITIVE_ORDER); // Tri alphabétique

        for (String col : sortedColumns) {
            if (!finalHeaders.contains(col)) {
                finalHeaders.add(col);
            }
        }

        // On ajoute les métadonnées Kyntus à la toute fin
        finalHeaders.addAll(Arrays.asList("N_Version", "Date_Version", "Source_Ingestion"));

        log.info("📝 PASSE 2 : Génération du fichier Excel ({} colonnes)...", finalHeaders.size());

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Interventions");

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setColor(IndexedColors.BLACK.getIndex());
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < finalHeaders.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(finalHeaders.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            currentChunk = 1;

            for (List<Long> chunk : chunks) {
                log.info("   -> Écriture du lot {}/{}", currentChunk++, chunks.size());
                List<Intervention> interventions = interventionRepository.findAllById(chunk);
                interventions.sort((a, b) -> b.getId().compareTo(a.getId()));

                for (Intervention inv : interventions) {
                    if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) {
                        Row row = sheet.createRow(rowIdx++);
                        row.createCell(finalHeaders.indexOf("idIntervention")).setCellValue(inv.getIdIntervention());
                        row.createCell(finalHeaders.indexOf("Source_Ingestion")).setCellValue(inv.getSourceIngestion() != null ? inv.getSourceIngestion() : "INCONNUE");
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

                        // 🛡️ INVERSION : V1 en premier
                        Collections.reverse(versions);

                        if (versions.isEmpty()) {
                            versions.add(objectMapper.createObjectNode());
                        }

                        // 🛡️ SAUVEGARDE DE LA V1 POUR LES COLONNES _BRUT
                        JsonNode v1 = versions.get(0);
                        String v1Total = v1.path("coutIntervention").path("montant").asText("0");

                        Map<String, String> v1FactMap = new HashMap<>();
                        if (v1.has("elementsFacturationCalcule")) {
                            for (JsonNode e : v1.get("elementsFacturationCalcule")) {
                                v1FactMap.put(e.path("designationElementFacturation").asText(""), e.path("montant").asText("0"));
                            }
                        }

                        Map<String, String> v1QualifMap = new HashMap<>();
                        Iterator<String> v1FieldNames = v1.fieldNames();
                        while (v1FieldNames.hasNext()) {
                            String fieldName = v1FieldNames.next();
                            v1QualifMap.put(fieldName, v1.path(fieldName).asText(""));
                        }

                        int versionNumber = 1;
                        for (JsonNode version : versions) {
                            Row row = sheet.createRow(rowIdx++);
                            Map<String, String> rowData = new HashMap<>();

                            // 1. Base Infos
                            rowData.put("idIntervention", root.path("identifiant").asText(inv.getIdIntervention()));
                            rowData.put("identifiant", root.path("identifiant").asText(inv.getIdIntervention()));

                            // 🛡️ L'FIX HNA : Extraction du mainteneurIdentifiant
                            rowData.put("mainteneurIdentifiant", root.path("mainteneur").path("identifiant").asText(""));

                            // Root fields
                            rowData.put("codeCloture", root.path("codeCloture").asText(""));
                            rowData.put("codeInsee", root.path("codeInsee").asText(""));
                            rowData.put("dateIntervention", root.path("dateIntervention").asText(""));
                            rowData.put("departement", root.path("departement").asText(""));
                            rowData.put("fyt", root.path("fyt").asText(""));
                            rowData.put("idWkf", root.path("idWkf").asText(""));
                            rowData.put("idTicket", root.path("idTicket").asText(""));
                            rowData.put("referencePm", root.path("referencePm").asText(""));
                            rowData.put("idInterventionReseau", root.path("idInterventionReseau").asText(""));
                            rowData.put("identifiantTechnicien", root.path("identifiantTechnicien").asText(""));
                            rowData.put("oi", root.path("oi").asText(""));
                            rowData.put("periode", root.path("periode").asText(""));
                            rowData.put("sousTraitant", root.path("sousTraitant").asText(""));

                            // Propriétés
                            if (root.has("proprietes")) {
                                for (JsonNode p : root.get("proprietes")) {
                                    rowData.put(p.path("nom").asText(""), p.path("valeur").asText(""));
                                }
                            }

                            if (!version.isEmpty()) {
                                rowData.put("typeIntervention", version.path("typeIntervention").asText(inv.getTypeIntervention()));
                                rowData.put("etat", version.path("etat").asText(inv.getEtat()));

                                JsonNode etape = version.path("etapeTraitementFacturation");
                                rowData.put("commentaire", etape.path("commentaire").asText(""));
                                rowData.put("loginAnalysteQu", etape.path("acteur").path("login").asText(""));

                                // Champs dynamiques (Normal + BRUT)
                                Iterator<String> fieldNames = version.fieldNames();
                                while (fieldNames.hasNext()) {
                                    String fieldName = fieldNames.next();
                                    if (!Arrays.asList("_type", "coutIntervention", "date", "elementsFacturationCalcule", "etapeTraitementFacturation", "etat", "identifiant", "typeIntervention", "typePrestation").contains(fieldName)) {
                                        rowData.put(fieldName, version.path(fieldName).asText(""));
                                        rowData.put(fieldName + "_BRUT", v1QualifMap.getOrDefault(fieldName, ""));
                                    }
                                }

                                // Facturation (Normal + BRUT)
                                rowData.put("TOTAL", version.path("coutIntervention").path("montant").asText("0"));
                                rowData.put("TOTAL_BRUT", v1Total);

                                if (version.has("elementsFacturationCalcule")) {
                                    for (JsonNode e : version.get("elementsFacturationCalcule")) {
                                        String fName = e.path("designationElementFacturation").asText("");
                                        rowData.put(fName, e.path("montant").asText("0"));
                                        rowData.put(fName + "_BRUT", v1FactMap.getOrDefault(fName, "0"));
                                    }
                                }

                                rowData.put("N_Version", "V" + versionNumber);
                                rowData.put("Date_Version", version.path("date").asText(""));
                            } else {
                                rowData.put("N_Version", "V1");
                            }

                            rowData.put("Source_Ingestion", inv.getSourceIngestion() != null ? inv.getSourceIngestion() : "INCONNUE");

                            // 5. Écriture dans les cellules Excel selon l'ordre exact de finalHeaders
                            for (int i = 0; i < finalHeaders.size(); i++) {
                                String colName = finalHeaders.get(i);
                                String value = rowData.getOrDefault(colName, "");

                                Cell cell = row.createCell(i);

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
                            versionNumber++;
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