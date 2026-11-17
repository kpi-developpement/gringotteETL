package com.kyntus.gringotts_sync.service;

import com.kyntus.gringotts_sync.domain.SyncEvent;
import com.kyntus.gringotts_sync.repository.SyncEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class EventLogService {

    public static final String TM = "TIME_MACHINE";
    public static final String HEALER = "HEALER";
    public static final String AUTOPILOT = "AUTOPILOT";
    public static final String BYTEL = "BYTEL";
    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String SYSTEM = "SYSTEM";

    public static final String INFO = "INFO";
    public static final String SUCCESS = "SUCCESS";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";

    private final SyncEventRepository repository;
    private final int retentionDays;

    public EventLogService(SyncEventRepository repository,
                           @Value("${kyntus.events.retention-days:14}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    public void log(String source, String level, String period, String message) {
        try {
            repository.save(SyncEvent.builder()
                    .createdAt(LocalDateTime.now())
                    .source(source)
                    .level(level)
                    .period(period)
                    .message(message.length() > 2000 ? message.substring(0, 2000) : message)
                    .build());
        } catch (Exception e) {
            log.warn("Impossible d'écrire l'événement: {}", e.getMessage());
        }
    }

    public List<SyncEvent> search(String source, String level, String period, long beforeId, String search, int limit) {
        List<String> sources = Arrays.stream(nz(source).split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
        return repository.search(sources.isEmpty(), sources.isEmpty() ? List.of("_") : sources,
                nz(level), nz(period), beforeId, nz(search),
                PageRequest.of(0, Math.max(1, Math.min(limit, 5000))));
    }

    private static final java.time.format.DateTimeFormatter EXPORT_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");
    private static final Map<String, String> SRC_LABEL = Map.of(
            TM, "Time Machine", HEALER, "Healer", AUTOPILOT, "AutoPilot",
            BYTEL, "Bytel", MAINTENANCE, "Maintenance", SYSTEM, "Système");
    private static final Map<String, String> LVL_LABEL = Map.of(INFO, "Info", SUCCESS, "OK", WARN, "Alerte", ERROR, "Erreur");

    /** Export texte (TSV) prêt à coller dans un ticket / Excel. */
    public String exportText(String source, String level, String period, String search, int limit) {
        StringBuilder sb = new StringBuilder("Horodatage\tSource\tNiveau\tPériode\tMessage\n");
        for (SyncEvent e : search(source, level, period, 0, search, limit)) {
            sb.append(e.getCreatedAt().format(EXPORT_FMT)).append('\t')
              .append(SRC_LABEL.getOrDefault(e.getSource(), e.getSource())).append('\t')
              .append(LVL_LABEL.getOrDefault(e.getLevel(), e.getLevel())).append('\t')
              .append(e.getPeriod() == null ? "—" : e.getPeriod()).append('\t')
              .append(e.getMessage().replace('\n', ' ').replace('\t', ' ')).append('\n');
        }
        return sb.toString();
    }

    public Map<String, Long> countsLast24h() {
        Map<String, Long> m = new LinkedHashMap<>(Map.of(INFO, 0L, SUCCESS, 0L, WARN, 0L, ERROR, 0L));
        for (Object[] row : repository.countByLevelSince(LocalDateTime.now().minusHours(24))) {
            m.put((String) row[0], (Long) row[1]);
        }
        return m;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeOld() {
        int n = repository.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));
        log.info("[EVENTS] {} anciens événements supprimés.", n);
    }

    private static String nz(String s) {
        return s == null || s.equalsIgnoreCase("ALL") ? "" : s.trim();
    }
}
