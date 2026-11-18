const API_URL = typeof window !== 'undefined' 
  ? `http://${window.location.hostname}:8117/api/dashboard`
  : 'http://10.10.10.25:8117/api/dashboard';

export interface SyncStats {
  total_interventions_local: number;
  period_offset: number;
  period_total: number;
  period_processed_total: number;
  current_period: string | null;
  is_running: boolean;
  eta: string;
  is_healing: boolean;
  healer_mode: string;
  heal_total: number;
  heal_current: number;
  time_machine_status: string;
  healer_status: string;
  alerts: string[];
  healer_processed_total: number;
  queued_periods?: string[];
  autopilot?: AutoPilotInfo;
  period_inserted?: number;
  period_updated?: number;
  event_counts_24h?: Record<string, number>;
}

export type ScopeMode = 'CURRENT_YEAR' | 'LAST_12' | 'CUSTOM';

export interface AutoPilotInfo {
  enabled: boolean;
  status: string;
  last_tick: string;
  scope_mode: ScopeMode;
  scope_label: string;
  scope_start: string;
  scope_periods: string[];
  periods_total: number;
  periods_complete: number;
  completeness: number;
  phase_counts: { collect: number; enrich: number; verify: number; complete: number };
  next_current_refresh_min: number;
  refresh: { current_min: number; previous_h: number; closed_h: number; verify_h: number };
  pass: number;
  pass_started: string | null;
  pass_done: number;
  pass_total: number;
  managed_periods: string[];
  throttle: string;
  bytel_paused: boolean;
}

export interface SyncEvent {
  id: number;
  createdAt: string;
  source: 'TIME_MACHINE' | 'HEALER' | 'AUTOPILOT' | 'BYTEL' | 'MAINTENANCE' | 'SYSTEM' | string;
  level: 'INFO' | 'SUCCESS' | 'WARN' | 'ERROR' | string;
  period: string | null;
  message: string;
}

export type CollectStatus = 'ACTIVE' | 'QUEUED' | 'TODO' | 'PARTIAL' | 'GAP' | 'EMPTY' | 'DONE';
export type EnrichStatus = 'WAIT' | 'DONE' | 'RETRY' | 'ACTIVE' | 'TODO';
export type VerifyStatus = 'WAIT' | 'TODO' | 'DONE' | 'GAP' | 'GAP_PERSISTENT';
export type Overall = 'COMPLETE' | 'IN_PROGRESS' | 'ANOMALY' | 'EMPTY' | 'OUT_OF_SCOPE';

export interface PeriodRow {
  period: string;
  in_scope: boolean;
  overall: Overall;
  collect: { status: CollectStatus; offset: number; remote_total: number; progress: number; scans: number; last_scan: string | null; next_refresh_min: number | null };
  enrich: { status: EnrichStatus; fresh: number; retry: number; definitive: number; with_detail: number; pct: number };
  verify: { status: VerifyStatus; at: string | null; remote: number | null; local: number | null; orphans: number | null };
  state: 'ACTIVE' | 'QUEUED' | 'DONE' | 'PENDING';
  local_rows: number;
  local_distinct: number;
  missing_details: number;
  phantoms: number;
  remote_total: number;
  gap: number;
  gap_source: 'live' | 'last_scan' | 'unknown';
  offset: number;
  progress: number;
  scans: number;
  pass_done: number;
  last_scan: string | null;
  last_result: { inserted: number; updated: number; local: number; remote: number; duration: string; empty: boolean; changed: number | null; orphans: number | null } | null;
}

export interface HealerLot {
  at: string; size: number; ok: number; not_found: number; failed: number; duration_ms: number; queue: number;
}

export interface HealerInfo {
  running: boolean;
  mode: string;
  status: string;
  queue: number;
  fresh: number;
  retry_due: number;
  backoff: number;
  definitive: number;
  max_attempts: number;
  scope_start: string;
  phantoms: number;
  enriched: number;
  not_found: number;
  failed: number;
  failed_batches: number;
  recovered: number;
  rate_per_min: number;
  last_success_ago_s: number;
  last_error: string | null;
  uptime_s: number;
  recent_lots: HealerLot[];
  bytel_paused: boolean;
  throttle: string;
}

export interface EventFilters {
  source?: string; level?: string; period?: string; search?: string; beforeId?: number; limit?: number;
}

export const fetchEvents = async (f: EventFilters = {}): Promise<SyncEvent[]> => {
  try {
    const params = new URLSearchParams();
    Object.entries(f).forEach(([k, v]) => { if (v !== undefined && v !== '' && v !== 'ALL') params.append(k, String(v)); });
    const res = await fetch(`${API_URL}/events?${params.toString()}`);
    if (!res.ok) return [];
    return await res.json();
  } catch { return []; }
};

export const eventsExportUrl = (f: EventFilters = {}) => {
  const params = new URLSearchParams();
  Object.entries(f).forEach(([k, v]) => { if (v !== undefined && v !== '' && v !== 'ALL') params.append(k, String(v)); });
  return `${API_URL}/events/export?${params.toString()}`;
};

const postJson = async (path: string, body: unknown) => {
  try {
    const res = await fetch(`${API_URL}${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    return await res.json();
  } catch { return { error: 'Erreur de connexion' }; }
};

export const setScope = (mode: ScopeMode, start?: string) => postJson('/scope', { mode, start });
export const verifyPeriod = (period: string) => postJson('/verify-period', { period });
export const recollectPeriod = (period: string) => postJson('/recollect-period', { period });

export interface EpsCheck {
  id_intervention: string;
  found: boolean;
  duplicates: number;
  verdict: 'OK' | 'ABSENT_BASE' | 'DOUBLON' | 'NON_ENRICHI' | 'EN_REESSAI' | 'DEFINITIF';
  rows: {
    id: number; periode: string | null; in_scope: boolean; etat: string | null; type: string | null; mainteneur: string | null;
    source: string | null; date_modification_etat: string | null; last_seen_at: string | null;
    detail_status: string; detail_size: number; heal_attempts: number; heal_next_at: string | null;
  }[];
  live?: { reachable: boolean; has_detail?: boolean; latency_ms?: number; preview?: string; repaired?: number; error?: string };
}

export const checkEps = async (id: string, live: boolean): Promise<EpsCheck | null> => {
  try {
    const res = await fetch(`${API_URL}/check/${encodeURIComponent(id.trim())}?live=${live}`);
    if (!res.ok) return null;
    return await res.json();
  } catch { return null; }
};

export const fetchPeriods = async (): Promise<PeriodRow[]> => {
  try {
    const res = await fetch(`${API_URL}/periods`);
    if (!res.ok) return [];
    return await res.json();
  } catch { return []; }
};

export const fetchHealer = async (): Promise<HealerInfo | null> => {
  try {
    const res = await fetch(`${API_URL}/healer`);
    if (!res.ok) return null;
    return await res.json();
  } catch { return null; }
};

export const setAutopilot = async (enabled: boolean): Promise<AutoPilotInfo | null> => {
  try {
    const res = await fetch(`${API_URL}/autopilot`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled }),
    });
    return await res.json();
  } catch (err) {
    return null;
  }
};

export interface PageResponse {
  content: any[];
  number: number;
  totalPages: number;
  totalElements: number;
  size: number;
}

export const fetchStats = async (): Promise<SyncStats | null> => {
  try {
    const res = await fetch(`${API_URL}/stats`);
    if (!res.ok) return null;
    return await res.json();
  } catch (err) {
    return null;
  }
};

export const fetchPeriodInfo = async (period: string) => {
  try {
    const res = await fetch(`${API_URL}/period-info?period=${period}`);
    if (!res.ok) return null;
    return await res.json();
  } catch (err) {
    return null;
  }
};

export const startPeriodSync = async (periods: string) => {
  try {
    const res = await fetch(`${API_URL}/start-periods`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ periods }),
    });
    const data = await res.json();
    return data.message || data.error;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const stopSync = async () => {
  try {
    const res = await fetch(`${API_URL}/stop`, { method: 'POST' });
    const data = await res.json();
    return data.message;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const startHealer = async (mode: string) => {
  try {
    const res = await fetch(`${API_URL}/start-healer`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode }),
    });
    const data = await res.json();
    return data.message;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const stopHealer = async () => {
  try {
    const res = await fetch(`${API_URL}/stop-healer`, { method: 'POST' });
    const data = await res.json();
    return data.message;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const resetSync = async () => {
  try {
    const res = await fetch(`${API_URL}/reset`, { method: 'POST' });
    const data = await res.json();
    return data.message;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const purgePeriod = async (period: string) => {
  try {
    const res = await fetch(`${API_URL}/purge-period`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ period }),
    });
    const data = await res.json();
    return data.message || data.error;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const cleanDuplicates = async () => {
  try {
    const res = await fetch(`${API_URL}/clean-duplicates`, { method: 'POST' });
    const data = await res.json();
    return data.message;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const setManualOffset = async (value: number) => {
  try {
    const res = await fetch(`${API_URL}/offset/${value}`, { method: 'POST' });
    const data = await res.json();
    return data.message || data.error;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const retryFailedHeals = async () => {
  try {
    const res = await fetch(`${API_URL}/retry-failed-heals`, { method: 'POST' });
    const data = await res.json();
    return data.message;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const rescanPeriod = async (period: string) => {
  try {
    const res = await fetch(`${API_URL}/rescan-period`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ period }),
    });
    const data = await res.json();
    return data.message || data.error;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

// 🚀 L'FIX HNA: Zidna l'period bash tsift l'API
export const fixPeriods = async (period: string) => {
  try {
    const res = await fetch(`${API_URL}/fix-periods`, { 
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ period }),
    });
    const data = await res.json();
    return data.message || data.error;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const fetchInterventions = async (search: string, source: string, period: string, page: number, size: number = 50): Promise<PageResponse | null> => {
  try {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString()
    });
    
    if (search) params.append('search', search);
    if (source && source !== 'ALL') params.append('source', source);
    if (period) params.append('period', period);

    const res = await fetch(`${API_URL}/interventions?${params.toString()}`);
    if (!res.ok) return null;
    return await res.json();
  } catch (err) {
    return null;
  }
};

export const trimDatabase = async (keepCount: number) => {
  try {
    const res = await fetch(`${API_URL}/trim/${keepCount}`, { method: 'POST' });
    const data = await res.json();
    return data.message || data.error;
  } catch (err) {
    return 'Erreur de connexion';
  }
};

export const exportInterventionsExcel = async (source: string, period: string, type: string) => {
  try {
    const params = new URLSearchParams();
    if (source && source !== 'ALL') params.append('source', source);
    if (period) params.append('period', period);
    if (type && type !== 'ALL') params.append('type', type);

    const res = await fetch(`${API_URL}/export?${params.toString()}`);
    if (!res.ok) throw new Error('Erreur lors de l\'export');
    
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    
    const filename = `Export_Gringotts_${type === 'ALL' ? 'Global' : type}_${source === 'ALL' ? 'ToutesSources' : source}${period ? '_' + period : ''}.xlsx`;
    a.download = filename;
    
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
  } catch (err) {
    console.error(err);
    alert('Erreur lors du téléchargement du fichier Excel.');
  }
};