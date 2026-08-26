const API_URL = process.env.NEXT_PUBLIC_API_URL || 'http://10.10.10.25:8117/api/dashboard';

export interface SyncStats {
  total_interventions_local: number;
  current_bt_offset: number;
  total_api: number;
  is_running: boolean;
}

export const fetchStats = async (): Promise<SyncStats | null> => {
  try {
    const response = await fetch(`${API_URL}/stats`, { method: 'GET', cache: 'no-store' });
    if (!response.ok) throw new Error('Erreur réseau');
    return await response.json();
  } catch (error) {
    return null;
  }
};

export const startSync = async (): Promise<boolean> => {
  try {
    const res = await fetch(`${API_URL}/start`, { method: 'POST' });
    return res.ok;
  } catch (error) { return false; }
};

export const stopSync = async (): Promise<boolean> => {
  try {
    const res = await fetch(`${API_URL}/stop`, { method: 'POST' });
    return res.ok;
  } catch (error) { return false; }
};

// 🛡️ NOUVEAU
export const resetSync = async (): Promise<boolean> => {
  try {
    const res = await fetch(`${API_URL}/reset`, { method: 'POST' });
    return res.ok;
  } catch (error) { return false; }
};