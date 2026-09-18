const API_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8117/api/dashboard';

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
}

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

    // 🚀 N9raw la réponse ka text b3da bach nchoufo chno fiha
    const text = await res.text(); 

    try {
      // N7awlo nparsiwha JSON
      const data = JSON.parse(text); 
      return data.message || data.error;
    } catch (e) {
      // Ila mabghatch t-parsa (ya3ni Spring rje3 HTML 404 wla 500)
      return `❌ ERREUR SERVEUR (${res.status}): ${text.substring(0, 150)}...`;
    }

  } catch (err: any) {
    // Hadi yla l'Frontend ga3ma 9der ywssel l'serveur (serveur tafe awla port ghalat)
    return `❌ ERREUR FRONTEND: ${err.message}`;
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