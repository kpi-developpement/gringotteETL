'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { fetchStats, fetchHealer, fetchPeriods, SyncStats, HealerInfo, PeriodRow } from '../../services/api';

/** Polling générique, s'arrête quand l'onglet est caché. */
export function usePoll<T>(fn: () => Promise<T>, ms: number, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const refresh = useCallback(async () => {
    const d = await fnRef.current();
    if (d !== null && d !== undefined) setData(d);
  }, []);

  useEffect(() => {
    let alive = true;
    const run = async () => {
      if (typeof document !== 'undefined' && document.hidden) return;
      const d = await fnRef.current();
      if (alive && d !== null && d !== undefined) setData(d);
    };
    run();
    const id = setInterval(run, ms);
    return () => { alive = false; clearInterval(id); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, ...deps]);

  return { data, refresh };
}

export function useLive() {
  const stats = usePoll<SyncStats | null>(fetchStats, 2000);
  const healer = usePoll<HealerInfo | null>(fetchHealer, 4000);
  const periods = usePoll<PeriodRow[]>(fetchPeriods, 8000);
  const refreshAll = useCallback(() => { stats.refresh(); healer.refresh(); periods.refresh(); }, [stats, healer, periods]);
  return { stats: stats.data, healer: healer.data, periods: periods.data || [], refreshAll };
}

/** Compteur animé (easing) vers la valeur cible. */
export function useAnimatedNumber(target: number, duration = 900) {
  const [value, setValue] = useState(target);
  const fromRef = useRef(target);
  const rafRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    const from = fromRef.current;
    if (from === target) return;
    const start = performance.now();
    const step = (t: number) => {
      const p = Math.min(1, (t - start) / duration);
      const eased = 1 - Math.pow(1 - p, 3);
      const v = from + (target - from) * eased;
      setValue(v);
      if (p < 1) rafRef.current = requestAnimationFrame(step);
      else fromRef.current = target;
    };
    rafRef.current = requestAnimationFrame(step);
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); fromRef.current = target; };
  }, [target, duration]);

  return value;
}

export function useClock() {
  const [now, setNow] = useState<Date | null>(null);
  useEffect(() => {
    setNow(new Date());
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}

export function useToast() {
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => {
    if (!msg) return;
    const id = setTimeout(() => setMsg(null), 4500);
    return () => clearTimeout(id);
  }, [msg]);
  return { toast: msg, show: setMsg };
}
