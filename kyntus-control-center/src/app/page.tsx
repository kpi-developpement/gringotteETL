'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { fetchStats, triggerManualSync, SyncStats } from '../services/api';
import StatCard from '../components/StatCard';
import styles from './page.module.css';

export default function DashboardPage() {
  const [stats, setStats] = useState<SyncStats | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [message, setMessage] = useState<string>('');

  const loadStats = async () => {
    const data = await fetchStats();
    if (data) setStats(data);
  };

  useEffect(() => {
    loadStats();
    // L'interface kat-dir mise à jour kol 3 secondes bach t-chouf l'vitesse
    const interval = setInterval(loadStats, 3000);
    return () => clearInterval(interval);
  }, []);

  const handleTrigger = async () => {
    setLoading(true);
    setMessage('Boost envoyé ! L\'orchestrateur va accélérer le cycle en cours.');
    await triggerManualSync();
    setTimeout(() => {
      setLoading(false);
      setMessage('');
    }, 3000);
  };

  return (
    <div className={styles.container}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.pageTitle}>Gringotts Sync</h1>
          <p className={styles.pageSubtitle}>Mode Auto-Pilote Activé 🚀</p>
        </div>
        <div className={styles.statusContainer}>
          <span className={stats ? styles.statusOnline : styles.statusOffline}>
            {stats ? 'AUTO-SYNC : ON (15s)' : 'SERVEUR HORS LIGNE'}
          </span>
        </div>
      </header>

      <main className={styles.main}>
        <div className={styles.grid}>
          <StatCard 
            title="Interventions Sécurisées" 
            value={stats ? stats.total_interventions_local.toLocaleString() : '---'} 
            subtitle="Base de données locale"
          />
          <StatCard 
            title="Offset Bouygues" 
            value={stats ? stats.current_bt_offset.toLocaleString() : '---'} 
            subtitle="Progression de l'import"
          />
        </div>

        <div className={styles.actionPanel}>
          <h2 className={styles.panelTitle}>Synchronisation Automatique</h2>
          <p className={styles.panelDesc}>
            Le système est en mode automatique. Il vide le serveur IONOS et récupère les nouvelles données de Bouygues toutes les 15 secondes sans aucune action de votre part.
          </p>
          
          <div style={{ display: 'flex', gap: '16px', justifyContent: 'center', marginTop: '20px' }}>
            <button 
              className={styles.button} 
              onClick={handleTrigger} 
              disabled={loading || !stats}
              style={{ backgroundColor: 'var(--kyntus-dark)' }}
            >
              {loading ? 'Boost en cours...' : 'Forcer un Boost Immédiat'}
            </button>

            <Link href="/interventions" style={{
              backgroundColor: 'var(--kyntus-light)',
              color: 'var(--kyntus-dark)',
              border: '1px solid var(--border-color)',
              padding: '12px 32px',
              fontSize: '1rem',
              fontWeight: 600,
              borderRadius: '6px',
              textDecoration: 'none'
            }}>
              Voir les données
            </Link>
          </div>
          
          {message && (
            <div className={styles.messageBox}>
              {message}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}