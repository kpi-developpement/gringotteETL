const API_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8117/api/dashboard';

export interface SyncStats {
  total_interventions_local: number;
  current_bt_offset: number;
  status: string;
}

export const fetchStats = async (): Promise<SyncStats | null> => {
  try {
    const response = await fetch(`${API_URL}/stats`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store', // Pour éviter le cache de Next.js
    });
    
    if (!response.ok) throw new Error('Erreur réseau');
    return await response.json();
  } catch (error) {
    console.error('Erreur lors de la récupération des stats:', error);
    return null;
  }
};

export const triggerManualSync = async (): Promise<boolean> => {
  try {
    const response = await fetch(`${API_URL}/trigger`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    });
    return response.ok;
  } catch (error) {
    console.error('Erreur lors du déclenchement manuel:', error);
    return false;
  }
};