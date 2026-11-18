package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explorateur d'EPS : filtres multi-critères, facettes (compteurs), détail. SQL 100% paramétré. */
@Service
@RequiredArgsConstructor
public class ExplorerService {

    private final JdbcTemplate jdbcTemplate;
    private final SyncOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile NamedParameterJdbcTemplate named;

    /** Dimensions filtrables : clé API -> colonne SQL. */
    public static final Map<String, String> DIMS = new LinkedHashMap<>();
    static {
        DIMS.put("periode", "periode");
        DIMS.put("categorie", "categorie");
        DIMS.put("type", "type_presta");
        DIMS.put("etat", "etat_qualif");
        DIMS.put("cloture", "code_cloture");
        DIMS.put("sous_traitant", "sous_traitant");
        DIMS.put("departement", "departement");
        DIMS.put("oi", "oi");
        DIMS.put("raccordement", "type_raccordement");
    }

    private static final String DETAIL_STATUS = "CASE WHEN detail_intervention IS NULL OR detail_intervention IN ('', '[]') THEN 'NON_ENRICHI' " +
            "WHEN detail_intervention = '{}' AND COALESCE(heal_attempts, 0) >= :maxAttempts THEN 'DEFINITIF' " +
            "WHEN detail_intervention = '{}' THEN 'EN_REESSAI' ELSE 'OK' END";

    private NamedParameterJdbcTemplate named() {
        if (named == null) named = new NamedParameterJdbcTemplate(jdbcTemplate);
        return named;
    }

    public record Filters(Map<String, List<String>> dims, String detail, String q, LocalDate from, LocalDate to) {}

    public static Filters parse(Map<String, String> p) {
        Map<String, List<String>> dims = new LinkedHashMap<>();
        for (String k : DIMS.keySet()) {
            String v = p.get(k);
            if (v != null && !v.isBlank()) {
                List<String> vals = Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
                if (!vals.isEmpty()) dims.put(k, vals);
            }
        }
        LocalDate from = null, to = null;
        try { if (p.get("from") != null && !p.get("from").isBlank()) from = LocalDate.parse(p.get("from")); } catch (Exception ignored) { }
        try { if (p.get("to") != null && !p.get("to").isBlank()) to = LocalDate.parse(p.get("to")); } catch (Exception ignored) { }
        return new Filters(dims, blankToNull(p.get("detail")), blankToNull(p.get("q")), from, to);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** WHERE dynamique (on peut exclure une dimension pour calculer sa facette). */
    private String where(Filters f, MapSqlParameterSource ps, String exclude) {
        List<String> c = new ArrayList<>();
        ps.addValue("maxAttempts", orchestrator.getHealerMaxAttempts());
        for (Map.Entry<String, List<String>> e : f.dims().entrySet()) {
            if (e.getKey().equals(exclude)) continue;
            String col = DIMS.get(e.getKey());
            List<String> vals = new ArrayList<>(e.getValue());
            boolean wantNull = vals.remove("∅");
            String pn = "d_" + e.getKey();
            List<String> parts = new ArrayList<>();
            if (!vals.isEmpty()) { parts.add(col + " IN (:" + pn + ")"); ps.addValue(pn, vals); }
            if (wantNull) parts.add(col + " IS NULL");
            c.add("(" + String.join(" OR ", parts) + ")");
        }
        if (f.detail() != null && !"detail".equals(exclude)) {
            c.add(DETAIL_STATUS + " = :detailStatus");
            ps.addValue("detailStatus", f.detail());
        }
        if (f.q() != null) {
            c.add("(id_intervention ILIKE :q OR fyt ILIKE :q)");
            ps.addValue("q", "%" + f.q() + "%");
        }
        if (f.from() != null) { c.add("date_intervention >= :from"); ps.addValue("from", Timestamp.valueOf(f.from().atStartOfDay())); }
        if (f.to() != null) { c.add("date_intervention < :to"); ps.addValue("to", Timestamp.valueOf(f.to().plusDays(1).atStartOfDay())); }
        return c.isEmpty() ? "" : " WHERE " + String.join(" AND ", c);
    }

    public Map<String, Object> search(Filters f, String sort, int page, int size) {
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        MapSqlParameterSource ps = new MapSqlParameterSource();
        String w = where(f, ps, null);
        String order = switch (sort == null ? "" : sort) {
            case "date_asc" -> "date_intervention ASC NULLS LAST, id ASC";
            case "id_desc" -> "id DESC";
            case "id_asc" -> "id ASC";
            default -> "date_intervention DESC NULLS LAST, id DESC";
        };
        ps.addValue("limit", size).addValue("offset", page * size);
        List<Map<String, Object>> rows = named().queryForList(
                "SELECT id, id_intervention, fyt, periode, categorie, type_presta, type_raccordement, etat_qualif, code_cloture, " +
                "sous_traitant, departement, oi, date_intervention, source_ingestion, heal_attempts, heal_next_at, last_seen_at, " +
                "COALESCE(length(detail_intervention), 0) AS detail_size, " + DETAIL_STATUS + " AS detail_status " +
                "FROM interventions" + w + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", ps);
        Long total = named().queryForObject("SELECT COUNT(*) FROM interventions" + w, ps, Long.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("total", total == null ? 0 : total);
        m.put("page", page);
        m.put("size", size);
        return m;
    }

    private final Map<String, Object[]> facetCache = new ConcurrentHashMap<>();

    /** Compteurs par valeur pour chaque dimension (en tenant compte des autres filtres). Cache 20 s. */
    public Map<String, Object> facets(Filters f) {
        String key = f.toString();
        Object[] hit = facetCache.get(key);
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < 20_000) {
            @SuppressWarnings("unchecked") Map<String, Object> cached = (Map<String, Object>) hit[1];
            return cached;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> d : DIMS.entrySet()) {
            MapSqlParameterSource ps = new MapSqlParameterSource();
            String w = where(f, ps, d.getKey());
            List<Map<String, Object>> vals = named().queryForList(
                    "SELECT " + d.getValue() + " AS v, COUNT(*) AS n FROM interventions" + w +
                    " GROUP BY " + d.getValue() + " ORDER BY n DESC LIMIT 60", ps);
            out.put(d.getKey(), vals);
        }
        MapSqlParameterSource ps = new MapSqlParameterSource();
        String w = where(f, ps, "detail");
        out.put("detail", named().queryForList("SELECT " + DETAIL_STATUS + " AS v, COUNT(*) AS n FROM interventions" + w +
                " GROUP BY 1 ORDER BY n DESC", ps));
        if (facetCache.size() > 200) facetCache.clear();
        facetCache.put(key, new Object[]{System.currentTimeMillis(), out});
        return out;
    }

    public List<Long> ids(Filters f, int max) {
        MapSqlParameterSource ps = new MapSqlParameterSource();
        String w = where(f, ps, null);
        ps.addValue("limit", max);
        return named().queryForList("SELECT id FROM interventions" + w + " ORDER BY id LIMIT :limit", ps, Long.class);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM interventions WHERE id = ?", id);
        if (rows.isEmpty()) return Map.of("found", false);
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        Object d = r.remove("detail_intervention");
        Object p = r.remove("payload_recu");
        r.put("detail_json", d); // JSON brut : parsé côté UI
        r.put("payload_json", p);
        r.put("found", true);
        return r;
    }
}
