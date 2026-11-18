package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contrôle automatique n°1 : extraction des champs (catégorie, type, sous-traitant…) pour les EPS
 * déjà enrichis, et correction automatique de la période quand le détail Bytel dit autre chose
 * (ancien bouton "Nettoyer les intrus", désormais automatique).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetailBackfillService {

    private final JdbcTemplate jdbcTemplate;
    private final ActivityFeed feed;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PENDING = "extract_v IS NULL AND detail_intervention IS NOT NULL AND detail_intervention NOT IN ('', '[]', '{}')";
    private static final String SQL = "UPDATE interventions SET categorie = ?, type_presta = ?, type_raccordement = ?, etat_qualif = ?, " +
            "code_cloture = ?, sous_traitant = ?, departement = ?, oi = ?, fyt = ?, date_intervention = ?, " +
            "periode = COALESCE(?, periode), extract_v = " + DetailFields.VERSION + " WHERE id = ?";

    private volatile long remaining = -1;
    private volatile long movedTotal = 0;

    public long getRemaining() { return remaining; }
    public long getMovedTotal() { return movedTotal; }

    @Scheduled(initialDelay = 20_000, fixedDelay = 15_000)
    public void run() {
        try {
            int rounds = 0;
            while (rounds++ < 40) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT id, periode, detail_intervention FROM interventions WHERE " + PENDING + " ORDER BY id LIMIT 1000");
                if (rows.isEmpty()) break;
                List<Object[]> params = new ArrayList<>(rows.size());
                int moved = 0;
                for (Map<String, Object> r : rows) {
                    long id = ((Number) r.get("id")).longValue();
                    DetailFields f = DetailFields.parse(objectMapper, (String) r.get("detail_intervention"));
                    if (f.periode() != null && !Objects.equals(f.periode(), r.get("periode"))) moved++;
                    Object[] p = f.params(null, id);
                    params.add(java.util.Arrays.copyOfRange(p, 1, p.length)); // sans detail_intervention
                }
                jdbcTemplate.batchUpdate(SQL, params);
                movedTotal += moved;
                if (moved > 0) {
                    feed.log("SYS", "WARN", "Contrôle auto : " + moved + " EPS rangés dans le mauvais mois → déplacés vers la période indiquée par Bytel.");
                }
            }
            Long left = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interventions WHERE " + PENDING, Long.class);
            if (remaining != 0 && left != null && left == 0 && remaining > 0) {
                feed.log("SYS", "OK", "Extraction des champs terminée : catégories / types / sous-traitants disponibles pour tous les EPS enrichis.");
            } else if (left != null && left > 0) {
                feed.log("SYS", "INFO", "Extraction des champs en cours : " + left + " EPS restants.");
            }
            remaining = left == null ? -1 : left;
        } catch (Exception e) {
            log.warn("[BACKFILL] {}", e.getMessage());
        }
    }
}
