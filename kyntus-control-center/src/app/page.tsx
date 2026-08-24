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
    const interval = setInterval(loadStats, 3000);
    return () => clearInterval(interval);
  }, []);

  const handleTrigger = async () => {
    setLoading(true);
    setMessage('Envoi de la requête à l\'orchestrateur...');
    
    const success = await triggerManualSync();
    
    if (success) {
      setMessage('Cycle de synchronisation lancé ! Observez les compteurs.');
    } else {
      setMessage('Échec de la communication avec le serveur.');
    }
    
    setTimeout(() => {
      setLoading(false);
      setMessage('');
    }, 4000);
  };

  return (
    <div className={styles.container}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.pageTitle}>Gringotts Sync</h1>
          <p className={styles.pageSubtitle}>Control Center - Buffer to Data Warehouse</p>
        </div>
        <div className={styles.statusContainer}>
          <span className={stats ? styles.statusOnline : styles.statusOffline}>
            {stats ? 'SERVEUR EN LIGNE' : 'SERVEUR HORS LIGNE'}
          </span>
        </div>
      </header>

      <main className={styles.main}>
        <div className={styles.grid}>
          <StatCard 
            title="Interventions (Data Warehouse)" 
            value={stats ? stats.total_interventions_local.toLocaleString() : '---'} 
            subtitle="Total sauvegardé en local"
          />
          <StatCard 
            title="Offset API Bouygues" 
            value={stats ? stats.current_bt_offset.toLocaleString() : '---'} 
            subtitle="Dernier point de reprise"
          />
        </div>

        <div className={styles.actionPanel}>
          <h2 className={styles.panelTitle}>Contrôle & Visualisation</h2>
          <p className={styles.panelDesc}>
            L'orchestrateur tourne automatiquement. Vous pouvez forcer un cycle ou consulter les données locales.
          </p>
          
          <div style={{ display: 'flex', gap: '16px', justifyContent: 'center', marginTop: '20px' }}>
            <button 
              className={styles.button} 
              onClick={handleTrigger} 
              disabled={loading || !stats}
            >
              {loading ? 'Exécution en cours...' : 'Forcer la synchronisation'}
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