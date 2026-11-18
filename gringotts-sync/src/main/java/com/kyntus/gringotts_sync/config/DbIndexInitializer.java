package com.kyntus.gringotts_sync.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Crée les index manquants au démarrage (idempotent).
 * CONCURRENTLY => pas de verrou bloquant sur la table pendant la création.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class DbIndexInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    private static final String[] INDEXES = {
            // Upsert Time Machine (findByIdInterventionIn) + dédoublonnage
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_id_intervention ON interventions (id_intervention)",
            // Filtres / comptages par période
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_periode ON interventions (periode)",
            // File d'attente du Healer : index partiel minuscule, uniquement les EPS sans détails
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_missing_detail ON interventions (id) " +
                    "WHERE detail_intervention IS NULL OR detail_intervention = '[]' OR detail_intervention = ''",
            // Compteur des EPS fantômes
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_phantom ON interventions (id) WHERE detail_intervention = '{}'",
            // File du Healer par période (tri périodes récentes d'abord)
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_fresh_by_period ON interventions (periode, id) " +
                    "WHERE detail_intervention IS NULL OR detail_intervention = '[]' OR detail_intervention = ''",
            // Ré-essais programmés des fantômes
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_retry ON interventions (periode, heal_next_at) WHERE detail_intervention = '{}'",
            // Contrôle des orphelins (EPS plus renvoyés par Bytel)
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_period_seen ON interventions (periode, last_seen_at)",
            // Explorateur : filtres
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_period_cat ON interventions (periode, categorie)",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_cat_type ON interventions (categorie, type_presta)",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_date_interv ON interventions (date_intervention)",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_soustraitant ON interventions (sous_traitant)",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_departement ON interventions (departement)",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_fyt ON interventions (fyt)",
            // Rattrapage de l'extraction
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interv_to_extract ON interventions (id) WHERE extract_v IS NULL " +
                    "AND detail_intervention IS NOT NULL AND detail_intervention NOT IN ('', '[]', '{}')",
            // Journal : filtres par source / période
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_events_source ON sync_events (source, id)",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_events_period ON sync_events (period, id)",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_events_created ON sync_events (created_at)",
            // Purges / jointures logs
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_actions_log_intervention ON actions_log (intervention_id)"
    };

    @Override
    public void run(ApplicationArguments args) {
        for (String sql : INDEXES) {
            try {
                long t0 = System.currentTimeMillis();
                jdbcTemplate.execute(sql);
                log.info("[DB] Index OK ({} ms): {}", System.currentTimeMillis() - t0, sql.substring(0, Math.min(90, sql.length())));
            } catch (Exception e) {
                log.warn("[DB] Index ignoré ({}): {}", e.getMessage(), sql);
            }
        }
    }
}
