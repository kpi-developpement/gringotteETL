package com.kyntus.gringotts_sync.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Console live (mémoire, non persistée) : chaque page aspirée, chaque lot du Healer, chaque erreur.
 * L'UI la lit en "tail -f" via /activity?after=<seq>.
 */
@Component
public class ActivityFeed {

    public record Line(long seq, String at, String engine, String level, String msg) {}

    private static final int CAPACITY = 4000;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Deque<Line> lines = new ArrayDeque<>(CAPACITY);
    private long seq = 0;

    /** engine : TM | HEALER | AUTO | BYTEL | SYS ; level : INFO | OK | WARN | ERROR */
    public synchronized void log(String engine, String level, String msg) {
        if (lines.size() >= CAPACITY) lines.pollFirst();
        lines.addLast(new Line(++seq, LocalDateTime.now().format(FMT), engine, level, msg));
    }

    public synchronized Map<String, Object> since(long after, int limit, String engine) {
        List<Line> out = new ArrayList<>();
        Iterator<Line> it = lines.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            Line l = it.next();
            if (l.seq() <= after) break;
            if (engine == null || engine.isBlank() || "ALL".equalsIgnoreCase(engine)
                    || Arrays.asList(engine.split(",")).contains(l.engine())) out.add(l);
        }
        Collections.reverse(out);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last_seq", seq);
        m.put("lines", out);
        return m;
    }
}
