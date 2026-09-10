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

    // 🛡️ Dictionnaire de traduction pour corriger les fautes du JSON Bouygues
    private String mapColumnName(String original) {
        if (original == null) return "";
        switch (original) {
            case "A2_DOUBLON": return "A2_DOUBLONS";
            case "C2_DOUTE_POS": return "C2_DOUTE_POSE";
            case "F2_RACC_CPL": return "F2_RACC_CPLX";
            case "FLAG_REPROV_CH": return "FLAG_REPROV_CHAUD";
            default: return original;
        }
    }

    // 🛡️ LISTES EXHAUSTIVES DES COLONNES PAR TYPE
    private static final List<String> RACC_COLUMNS = Arrays.asList(
            "A1_CLES_CTRL", "A2_DOUBLONS", "A3_FIN_CMD", "ANALYSE_PHOTO", "B1_FORMAT_CHAMPS", "C1_NOK_POSE", "C1_PHOTO", "C2_DOUTE_POSE", "C3_DEPORT",
            "categorie", "categorieRaccordementLogementAnalyse", "CHAMP_1", "CHAMP_2", "CHAMP_3", "CHAMP_4", "CHAMP_5", "CHAMP_6", "codeCloture", "CODE_DECHARGE_TECH",
            "CODE_FACTURE", "codeInsee", "commentaireFinalBytel", "commentairePhotos", "TYPE_DESSERTE_CR", "dateIntervention", "departement",
            "E1_MES", "estBranchement", "estBranchement_BRUT", "estDiagnosticInternet", "estDiagnosticInternet_BRUT", "estDiagnosticTelephone", "estDiagnosticTelephone_BRUT",
            "estDiagnosticTv", "estDiagnosticTv_BRUT", "estDiagnosticWifi", "estDiagnosticWifi_BRUT", "estFournisseurBytel", "estFournisseurBytel_BRUT", "estInterventionComplexe",
            "estInterventionComplexe_BRUT", "estInterventionDimanche", "estInterventionDimanche_BRUT", "estMiseEnRelation", "estMiseEnRelation_BRUT", "estMiseEnService",
            "estMiseEnService_BRUT", "estPreAppel", "estPreAppel_BRUT", "estRACCGroupe", "estRACCGroupe_BRUT", "estZoneComplexe", "estZoneComplexe_BRUT", "F1_GOUL_ETH",
            "F2_RACC_CPLX", "FLAG_1", "FLAG_2", "FLAG_3", "FLAG_4", "FLAG_5", "FLAG_6", "FLAG_DEVIS_TRAVAUX", "FLAG_ECRASEMENT", "FLAG_EMUTATION", "FLAG_REPROV_CHAUD", "fyt",
            "GOULOTTE", "GOULOTTE_BRUT", "GX", "HX", "identifiant", "identifiantTechnicien", "INSTALLATION", "INSTALLATION_BRUT", "IX", "JX", "KX", "LOGISTIQUE", "LOGISTIQUE_BRUT",
            "longueurGoulottes", "longueurGoulottes_BRUT", "mainteneurIdentifiant", "MATERIEL", "MATERIEL_BRUT", "MES", "MES_BRUT", "montantDevis", "montantDevis_BRUT",
            "nombreRepeteursPoses", "nombreRepeteursPoses_BRUT", "idWkf", "oi", "periode", "presenceNacelle", "presenceNacelle_BRUT", "presenceNacelleAnalysePhoto", "PRESTATION_OI",
            "REF_PBO_CR", "REF_PBO_OI", "REF_PTO_CR", "REF_PTO_OI", "sousCategorieControlePhoto", "sousTraitant", "statutAnalysePhoto", "SUPPORT", "SUPPORT_BRUT", "TARIF_CALCULE",
            "TARIF_RECALCULE", "TOTAL", "TOTAL_BRUT", "TYPE_DESSERTE_OI", "TYPE_INSTALLATION", "typeIntervention", "typeIntervention_BRUT", "typeMalfaconPbo", "typeMalfaconPM", "typeMalfaconPto",
            "TYPE_OFFRE", "D1_TYPE_RACC", "typeRaccordement", "typeRaccordement_BRUT", "TYPE_ZONE", "visionAnalysteQualite", "estDeplacementFacturable", "estDeplacementFacturable_BRUT",
            "DEPLACEMENT", "DEPLACEMENT_BRUT"
    );

    private static final List<String> SAV_COLUMNS = Arrays.asList(
            "C01_TICKET", "C08_GARANTIE", "CXX_GLOBAL", "DATE_ACTIVATION", "DATE_CLOTURE_TICKET", "DATE_OUVERTURE_TICKET", "DATE_SAV_PREC_DER_CR", "ID_RACC", "ID_TICKET_PREC",
            "SOUS_TRAITANT_ID_SAV_PREC", "SOUS_TRAITANT_RACC", "CODE_CLOTURE_PREC", "STATUT_INTERVENTION_PREC", "STATUT_INTERVENTION_SUIV", "estDeplacementFacturable",
            "estDeplacementFacturable_BRUT", "estGarantieRaccordement", "estGarantieRaccordement_BRUT", "estGarantieSav", "estGarantieSav_BRUT", "estGti4h", "estGti4h_BRUT",
            "estGti8h", "estGti8h_BRUT", "estInterventionJPlus1", "estInterventionJPlus1_BRUT", "estKroe", "estKroe_BRUT", "estSAVGroupe", "estSAVGroupe_BRUT", "nombreClientsRetablis",
            "nombreClientsRetablis_BRUT", "nombreJarretiere", "nombreJarretiere_BRUT", "idTicket", "codeCloture", "codeInsee", "dateIntervention", "departement", "fyt", "identifiant",
            "identifiantTechnicien", "mainteneurIdentifiant", "oi", "periode", "sousTraitant", "TOTAL", "TOTAL_BRUT", "INSTALLATION", "INSTALLATION_BRUT",
            "MATERIEL", "MATERIEL_BRUT", "SUPPORT", "SUPPORT_BRUT", "JARRETIERES", "JARRETIERES_BRUT", "CODE_FACTURE", "CHAMP_1", "CHAMP_2",
            "CHAMP_3", "CHAMP_4", "CHAMP_5", "CHAMP_6", "FLAG_1", "FLAG_2", "FLAG_3", "FLAG_4", "FLAG_5", "FLAG_6", "REF_PTO_OI", "TYPE_DESSERTE_CR", "TYPE_ZONE", "REF_PBO_OI",
            "typeIntervention", "typeIntervention_BRUT", "D1_TYPE_RACC", "presenceNacelle", "presenceNacelle_BRUT", "DEPLACEMENT", "DEPLACEMENT_BRUT"
    );

    private static final List<String> RZO_COLUMNS = Arrays.asList(
            "B1_FORMAT", "C1_DEPLACEMENT", "CONTROLE_GLOBAL", "NB_CLIENTS_ACTV", "NB_COUPL_1", "NB_COUPL_2", "NB_COUPL_3", "estPresenceOi", "estPresenceOi_BRUT",
            "nb145a256clientsSoudures", "nb145a256clientsSoudures_BRUT", "nb17a32clientsAudit", "nb17a32clientsAudit_BRUT", "nb1a32clientsConformite", "nb1a32clientsConformite_BRUT",
            "nb1a48clientsSoudures", "nb1a48clientsSoudures_BRUT", "nb1a4clientsAudit", "nb1a4clientsAudit_BRUT", "nb33a64clientsAudit", "nb33a64clientsAudit_BRUT",
            "nb33a64clientsConformite", "nb33a64clientsConformite_BRUT", "nb49a96clientsSoudures", "nb49a96clientsSoudures_BRUT", "nb5a8clientsAudit", "nb5a8clientsAudit_BRUT",
            "nb65a128clientsAudit", "nb65a128clientsAudit_BRUT", "nb65a128clientsConformite", "nb65a128clientsConformite_BRUT", "nb97a144clientsSoudures", "nb97a144clientsSoudures_BRUT",
            "nb9a16clientsAudit", "nb9a16clientsAudit_BRUT", "nombreChangementFibreAlimentation", "nombreChangementFibreAlimentation_BRUT", "nombreChangementModules",
            "nombreChangementModules_BRUT", "nombreCheckEtatGlobalPbo", "nombreCheckEtatGlobalPbo_BRUT", "nombreCheckEtatGlobalPm", "nombreCheckEtatGlobalPm_BRUT",
            "nombreDesaturations", "nombreDesaturations_BRUT", "nombreFixModules", "nombreFixModules_BRUT", "nombreJarretieresReprises", "nombreJarretieresReprises_BRUT",
            "nombreMesuresSignalCoupleurs", "nombreMesuresSignalCoupleurs_BRUT", "nombrePositionsReleves", "nombrePositionsReleves_BRUT", "nombreRelevesPortsCollectes",
            "nombreRelevesPortsCollectes_BRUT", "nombreSoudures", "nombreSoudures_BRUT", "nombreSouduresFibreAlimentation", "nombreSouduresFibreAlimentation_BRUT",
            "nombreTestContinuitePmPbo", "nombreTestContinuitePmPbo_BRUT", "nombreTestsSynchroOnt", "nombreTestsSynchroOnt_BRUT", "responsabiliteFinale", "responsabiliteFinale_BRUT",
            "tarifMaterielUtilise", "tarifMaterielUtilise_BRUT", "referencePm", "idInterventionReseau", "codeCloture", "codeInsee", "dateIntervention", "departement", "fyt",
            "identifiant", "identifiantTechnicien", "mainteneurIdentifiant", "oi", "periode", "sousTraitant", "TOTAL", "TOTAL_BRUT", "INSTALLATION", "INSTALLATION_BRUT",
            "MATERIEL", "MATERIEL_BRUT", "JARRETIERES", "JARRETIERES_BRUT", "CODE_FACTURE", "CHAMP_1", "CHAMP_2",
            "typeIntervention", "typeIntervention_BRUT", "D1_TYPE_RACC", "estDeplacementFacturable", "estDeplacementFacturable_BRUT", "DEPLACEMENT", "DEPLACEMENT_BRUT"
    );

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

        // 🚀 CRÉATION DE L'EN-TÊTE EXACT
        Set<String> columnsToUse = new HashSet<>();
        if (cleanType.equals("RACC")) columnsToUse.addAll(RACC_COLUMNS);
        else if (cleanType.equals("SAV")) columnsToUse.addAll(SAV_COLUMNS);
        else if (cleanType.equals("RZO") || cleanType.equals("AUDITS")) columnsToUse.addAll(RZO_COLUMNS);
        else {
            columnsToUse.addAll(RACC_COLUMNS);
            columnsToUse.addAll(SAV_COLUMNS);
            columnsToUse.addAll(RZO_COLUMNS);
        }

        // 1. On trie tout par ordre alphabétique
        List<String> sortedColumns = new ArrayList<>(columnsToUse);
        sortedColumns.sort(String.CASE_INSENSITIVE_ORDER);

        // 2. On force les 5 premières colonnes.
        // 🛡️ L'FIX HNA : On utilise "typeIntervention_1" en interne pour la 2ème colonne
        List<String> finalHeaders = new ArrayList<>(Arrays.asList(
                "idIntervention", "typeIntervention_1", "etat", "commentaire", "loginAnalysteQu"
        ));

        // 3. On ajoute le reste trié
        for (String col : sortedColumns) {
            if (!finalHeaders.contains(col)) {
                finalHeaders.add(col);
            }
        }

        log.info("📝 Génération du fichier Excel ({} colonnes)...", finalHeaders.size());

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
                // 🛡️ L'FIX HNA : Si c'est "typeIntervention_1", on écrit "typeIntervention" dans l'Excel
                String headerName = finalHeaders.get(i).equals("typeIntervention_1") ? "typeIntervention" : finalHeaders.get(i);
                cell.setCellValue(headerName);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            int currentChunk = 1;

            for (List<Long> chunk : chunks) {
                log.info("   -> Écriture du lot {}/{}", currentChunk++, chunks.size());
                List<Intervention> interventions = interventionRepository.findAllById(chunk);
                interventions.sort((a, b) -> b.getId().compareTo(a.getId()));

                for (Intervention inv : interventions) {
                    if (inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("{}")) {
                        Row row = sheet.createRow(rowIdx++);
                        row.createCell(finalHeaders.indexOf("idIntervention")).setCellValue(inv.getIdIntervention());
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
                        String v1TypeRacc = v1.path("typePrestation").asText("");

                        Map<String, String> v1FactMap = new HashMap<>();
                        if (v1.has("elementsFacturationCalcule")) {
                            for (JsonNode e : v1.get("elementsFacturationCalcule")) {
                                String fName = e.path("designationElementFacturation").asText("");
                                v1FactMap.put(fName, e.path("montant").asText("0"));
                            }
                        }

                        Map<String, String> v1QualifMap = new HashMap<>();
                        Iterator<String> v1FieldNames = v1.fieldNames();
                        while (v1FieldNames.hasNext()) {
                            String fieldName = v1FieldNames.next();
                            v1QualifMap.put(fieldName, v1.path(fieldName).asText(""));
                        }

                        for (JsonNode version : versions) {
                            Row row = sheet.createRow(rowIdx++);
                            Map<String, String> rowData = new HashMap<>();

                            // 1. Base Infos
                            rowData.put("idIntervention", root.path("identifiant").asText(inv.getIdIntervention()));
                            rowData.put("identifiant", root.path("identifiant").asText(inv.getIdIntervention()));
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

                            // 2. Propriétés (Avec correction des fautes de frappe)
                            if (root.has("proprietes")) {
                                for (JsonNode p : root.get("proprietes")) {
                                    String mappedName = mapColumnName(p.path("nom").asText(""));
                                    rowData.put(mappedName, p.path("valeur").asText(""));
                                }
                            }

                            if (!version.isEmpty()) {
                                // 🛡️ L'FIX HNA : Le Domaine (RACC/SAV/RZO) va dans typeIntervention_1
                                String domain = "INCONNU";
                                if (version.has("_type")) {
                                    String t = version.get("_type").asText("");
                                    if (t.contains("Raccordement")) domain = "RACC";
                                    else if (t.contains("SAV")) domain = "SAV";
                                    else if (t.contains("Reseau")) domain = "RZO";
                                }
                                rowData.put("typeIntervention_1", domain);

                                rowData.put("etat", version.path("etat").asText(inv.getEtat()));

                                JsonNode etape = version.path("etapeTraitementFacturation");
                                rowData.put("commentaire", etape.path("commentaire").asText(""));
                                rowData.put("loginAnalysteQu", etape.path("acteur").path("login").asText(""));

                                // 3. Champs dynamiques (Normal + BRUT)
                                Iterator<String> fieldNames = version.fieldNames();
                                while (fieldNames.hasNext()) {
                                    String fieldName = fieldNames.next();
                                    // 🛡️ L'FIX HNA : On garde le 2ème typeIntervention (NOK, BRASSAGE_PM)
                                    if (!Arrays.asList("_type", "coutIntervention", "date", "elementsFacturationCalcule", "etapeTraitementFacturation", "etat", "identifiant", "typePrestation").contains(fieldName)) {
                                        rowData.put(fieldName, version.path(fieldName).asText(""));
                                        rowData.put(fieldName + "_BRUT", v1QualifMap.getOrDefault(fieldName, ""));
                                    }
                                }

                                // 4. Facturation (Normal + BRUT)
                                rowData.put("TOTAL", version.path("coutIntervention").path("montant").asText("0"));
                                rowData.put("TOTAL_BRUT", v1Total);
                                rowData.put("D1_TYPE_RACC", v1TypeRacc);

                                if (version.has("elementsFacturationCalcule")) {
                                    for (JsonNode e : version.get("elementsFacturationCalcule")) {
                                        String fName = e.path("designationElementFacturation").asText("");
                                        rowData.put(fName, e.path("montant").asText("0"));
                                        rowData.put(fName + "_BRUT", v1FactMap.getOrDefault(fName, "0"));
                                    }
                                }
                            }

                            // 5. Écriture dans les cellules Excel
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