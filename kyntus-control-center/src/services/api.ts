const API_URL = process.env.NEXT_PUBLIC_API_URL || 'http://10.10.10.25:8117/api/dashboard';

export interface SyncStats {
  total_interventions_local: number;
  current_bt_offset: number;
  total_api: number;
  is_running: boolean;
  // 🚀 NOUVEAU
  is_healing: boolean;
  heal_total: number;
  heal_current: number;
}

export interface Intervention {
  id: number;
  id_intervention: string;
  environment: string;
  etat: string;
  type_intervention: string;
  date_modification_etat: string;
  detail_intervention: string; 
}

export interface PageResponse {
  content: Intervention[];
  totalPages: number;
  totalElements: number;
  number: number;
}

export const fetchStats = async (): Promise<SyncStats | null> => {
  try {
    const response = await fetch(`${API_URL}/stats`, { method: 'GET', cache: 'no-store' });
    if (!response.ok) throw new Error('Erreur réseau');
    return await response.json();
  } catch (error) { return null; }
};

export const startSync = async (): Promise<boolean> => {
  try { const res = await fetch(`${API_URL}/start`, { method: 'POST' }); return res.ok; } catch (e) { return false; }
};

export const stopSync = async (): Promise<boolean> => {
  try { const res = await fetch(`${API_URL}/stop`, { method: 'POST' }); return res.ok; } catch (e) { return false; }
};

export const resetSync = async (): Promise<boolean> => {
  try { const res = await fetch(`${API_URL}/reset`, { method: 'POST' }); return res.ok; } catch (e) { return false; }
};

export const setManualOffset = async (value: number): Promise<boolean> => {
  try {
    const res = await fetch(`${API_URL}/offset/${value}`, { method: 'POST' });
    return res.ok;
  } catch (e) { return false; }
};

export const cleanDuplicates = async (): Promise<string> => {
  try {
    const res = await fetch(`${API_URL}/clean-duplicates`, { method: 'POST' });
    const data = await res.json();
    return data.message || "Opération terminée.";
  } catch (e) { return "Erreur lors du nettoyage."; }
};

export const trimDatabase = async (keepCount: number): Promise<string> => {
  try {
    const res = await fetch(`${API_URL}/trim/${keepCount}`, { method: 'POST' });
    const data = await res.json();
    return data.message || "Opération terminée.";
  } catch (e) { return "Erreur lors de la suppression."; }
};

export const healData = async (): Promise<string> => {
  try {
    const res = await fetch(`${API_URL}/heal`, { method: 'POST' });
    const data = await res.json();
    return data.message || "Réparation lancée.";
  } catch (e) { return "Erreur lors du lancement de la réparation."; }
};

export const fetchInterventions = async (search: string, page: number, size: number = 50): Promise<PageResponse | null> => {
  try {
    const res = await fetch(`${API_URL}/interventions?search=${search}&page=${page}&size=${size}`, { cache: 'no-store' });
    if (!res.ok) throw new Error('Erreur réseau');
    return await res.json();
  } catch (error) { return null; }
};