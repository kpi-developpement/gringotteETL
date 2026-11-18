package com.kyntus.gringotts_sync.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

/**
 * Régulateur adaptatif partagé entre la Time Machine et le Healer.
 *
 * - Espace globalement les appels vers Bytel (délai minimum entre deux requêtes).
 * - Limite le nombre de requêtes simultanées (limite dynamique).
 * - AIMD : succès rapide => on accélère doucement ; 5xx / timeout / réponse lente => on freine fort.
 * - 403 / Akamai => pause globale (les deux moteurs s'arrêtent ensemble).
 * - Trop d'erreurs d'affilée => pause "disjoncteur" pour laisser respirer le serveur.
 */
@Slf4j
@Component
public class BytelThrottle {

    private final long minDelayMs;
    private final long maxDelayMs;
    private final int maxInFlight;
    private final long slowResponseMs;
    private final long blockPauseMs;
    private final long breakerPauseMs;
    private final int breakerThreshold;

    private long delayMs;
    private int inFlightLimit;
    private int inFlight = 0;
    private long nextSlotAt = 0;
    private int consecutiveErrors = 0;
    private int consecutiveFastSuccesses = 0;
    private volatile long pausedUntil = 0;
    private volatile String pauseReason = "";

    public BytelThrottle(
            @Value("${kyntus.throttle.min-delay-ms:250}") long minDelayMs,
            @Value("${kyntus.throttle.max-delay-ms:15000}") long maxDelayMs,
            @Value("${kyntus.throttle.max-in-flight:4}") int maxInFlight,
            @Value("${kyntus.throttle.slow-response-ms:25000}") long slowResponseMs,
            @Value("${kyntus.throttle.block-pause-ms:900000}") long blockPauseMs,
            @Value("${kyntus.throttle.breaker-pause-ms:120000}") long breakerPauseMs,
            @Value("${kyntus.throttle.breaker-threshold:6}") int breakerThreshold) {
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxInFlight = Math.max(1, maxInFlight);
        this.slowResponseMs = slowResponseMs;
        this.blockPauseMs = blockPauseMs;
        this.breakerPauseMs = breakerPauseMs;
        this.breakerThreshold = breakerThreshold;
        this.delayMs = Math.max(minDelayMs, 800);
        this.inFlightLimit = Math.max(1, this.maxInFlight / 2);
    }

    /**
     * Attend un créneau libre. Retourne false si keepGoing devient faux pendant l'attente.
     * Si true est retourné, l'appelant DOIT appeler release() (via onSuccess/onOverload/onBlocked/onError).
     */
    public boolean acquire(BooleanSupplier keepGoing) {
        // 1) Pause globale (403 / disjoncteur)
        while (System.currentTimeMillis() < pausedUntil) {
            if (!keepGoing.getAsBoolean()) return false;
            sleepQuiet(1000);
        }
        // 2) Slot de concurrence
        synchronized (this) {
            while (inFlight >= inFlightLimit) {
                if (!keepGoing.getAsBoolean()) return false;
                try { wait(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            inFlight++;
        }
        // 3) Espacement global entre requêtes
        long waitMs;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextSlotAt);
            nextSlotAt = slot + delayMs;
            waitMs = slot - now;
        }
        if (waitMs > 0) sleepQuiet(waitMs);
        if (!keepGoing.getAsBoolean()) { release(); return false; }
        return true;
    }

    public synchronized void onSuccess(long latencyMs) {
        consecutiveErrors = 0;
        if (latencyMs > slowResponseMs) {
            // Bytel répond mais rame : on freine avant qu'il tombe.
            consecutiveFastSuccesses = 0;
            delayMs = Math.min(maxDelayMs, (long) (delayMs * 1.5) + 200);
            inFlightLimit = Math.max(1, inFlightLimit - 1);
        } else {
            consecutiveFastSuccesses++;
            delayMs = Math.max(minDelayMs, delayMs - 50);
            if (consecutiveFastSuccesses >= 10 && inFlightLimit < maxInFlight) {
                inFlightLimit++;
                consecutiveFastSuccesses = 0;
            }
        }
        releaseLocked();
    }

    /** 5xx, 504, timeout, connexion refusée... */
    public synchronized void onOverload(String reason) {
        consecutiveFastSuccesses = 0;
        consecutiveErrors++;
        delayMs = Math.min(maxDelayMs, delayMs * 2);
        inFlightLimit = 1;
        if (consecutiveErrors >= breakerThreshold) {
            pause(breakerPauseMs, "Disjoncteur: " + consecutiveErrors + " erreurs (" + reason + ")");
            consecutiveErrors = 0;
        }
        releaseLocked();
    }

    /**
     * Pression "douce" : Bytel répond vite mais en omettant beaucoup d'IDs (symptôme de surcharge).
     * On ralentit un peu, sans disjoncteur ni libération de slot.
     */
    public synchronized void onSoftPressure() {
        consecutiveFastSuccesses = 0;
        delayMs = Math.min(maxDelayMs, (long) (delayMs * 1.25) + 100);
        if (inFlightLimit > 1) inFlightLimit--;
    }

    /** 403 / Akamai : tout le monde s'arrête. */
    public synchronized void onBlocked() {
        consecutiveFastSuccesses = 0;
        delayMs = maxDelayMs / 2;
        inFlightLimit = 1;
        pause(blockPauseMs, "Pare-feu Bytel (403)");
        releaseLocked();
    }

    /** Erreur non liée à la charge (glitch de données, 404...). */
    public synchronized void onError() {
        releaseLocked();
    }

    public synchronized void release() {
        releaseLocked();
    }

    private void releaseLocked() {
        if (inFlight > 0) inFlight--;
        notifyAll();
    }

    private void pause(long ms, String reason) {
        long until = System.currentTimeMillis() + ms;
        if (until > pausedUntil) {
            pausedUntil = until;
            pauseReason = reason;
            log.warn("[THROTTLE] Pause globale {}s : {}", ms / 1000, reason);
        }
    }

    public boolean isPaused() { return System.currentTimeMillis() < pausedUntil; }

    public synchronized String describe() {
        if (isPaused()) {
            long s = (pausedUntil - System.currentTimeMillis()) / 1000;
            return "PAUSE " + s + "s — " + pauseReason;
        }
        return "Délai " + delayMs + "ms | Parallèle " + inFlightLimit + "/" + maxInFlight + " | En vol " + inFlight;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
