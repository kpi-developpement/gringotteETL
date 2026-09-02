'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { fetchStats, startSync, stopSync, resetSync, setManualOffset, SyncStats } from '../services/api';
import StatCard from '../components/StatCard';
import styles from './page.module.css';

export default function DashboardPage() {
  const [stats, setStats] = useState<SyncStats | null>(null);
  const [newOffset, setNewOffset] = useState<string>('');

  const loadStats = async () => {
    const data = await fetchStats();
    if (data) setStats(data);
  };

  useEffect(() => {
    loadStats();
    const interval = setInterval(loadStats, 2000);
    return () => clearInterval(interval);
  }, []);

  const handleStart = async () => { await startSync(); loadStats(); };
  const handleStop = async () => { await stopSync(); loadStats(); };
  
  const handleReset = async () => {
    if (window.confirm("ATTENTION : Cela va effacer TOUTES les données sur IONOS et en Local, puis recommencer l'import depuis zéro. Êtes-vous sûr ?")) {
      await resetSync();
      loadStats();
    }
  };

  const handleSetOffset = async () => {
    const val = parseInt(newOffset);
    if (!isNaN(val) && val >= 0) {
      await setManualOffset(val);
      setNewOffset('');
      loadStats();
      alert(`Offset mis à jour à ${val}. Cliquez sur Démarrer pour reprendre à partir d'ici.`);
    }
  };

  const totalApi = stats?.total_api || 0;
  const currentOffset = stats?.current_bt_offset || 0;
  const progress = totalApi > 0 ? Math.min(100, Math.round((currentOffset / totalApi) * 100)) : 0;

  return (
    <div className={styles.container}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.pageTitle}>Gringotts Sync</h1>
          <p className={styles.pageSubtitle}>Mode Turbo Continu 🚀</p>
        </div>
        <div className={styles.statusContainer}>
          <span className={stats?.is_running ? styles.statusOnline : styles.statusOffline}>
            {stats?.is_running ? '🟢 EN COURS D\'ASPIRATION' : '🔴 À L\'ARRÊT'}
          </span>
        </div>
      </header>

      <main className={styles.main}>
        <div className={styles.progressContainer}>
          <div className={styles.progressHeader}>
            <span className={styles.progressTitle}>Progression Globale de l'Import</span>
            <span className={styles.progressText}>{currentOffset} / {totalApi} ({progress}%)</span>
          </div>
          <div className={styles.progressBarBg}>
            <div className={styles.progressBarFill} style={{ width: `${progress}%` }}></div>
          </div>
        </div>

        <div className={styles.grid}>
          <StatCard 
            title="Interventions Sécurisées" 
            value={stats ? stats.total_interventions_local.toLocaleString() : '---'} 
            subtitle="Sauvegardées en local"
          />
          <StatCard 
            title="Total Bouygues (API)" 
            value={stats ? stats.total_api.toLocaleString() : '---'} 
            subtitle="Volume total à aspirer"
          />
        </div>

        <div className={styles.actionPanel}>
          <h2 className={styles.panelTitle}>Contrôle du Moteur</h2>
          
          <div style={{ display: 'flex', gap: '16px', justifyContent: 'center', marginTop: '20px', flexWrap: 'wrap' }}>
            {!stats?.is_running ? (
              <button className={styles.buttonStart} onClick={handleStart}>▶ DÉMARRER</button>
            ) : (
              <button className={styles.buttonStop} onClick={handleStop}>⏹ STOPPER</button>
            )}
            <Link href="/interventions" className={styles.buttonLink}>Voir les données</Link>
          </div>

          <div style={{ marginTop: '30px', padding: '20px', backgroundColor: '#f9fafb', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
            <h3 style={{ fontSize: '1rem', marginBottom: '10px', color: 'var(--kyntus-dark)' }}>Modifier l'Offset Manuellement</h3>
            <div style={{ display: 'flex', gap: '10px', justifyContent: 'center' }}>
              <input 
                type="number" 
                value={newOffset} 
                onChange={(e) => setNewOffset(e.target.value)} 
                placeholder="Ex: 711003"
                style={{ padding: '10px', borderRadius: '6px', border: '1px solid #ccc', width: '200px' }}
              />
              <button 
                onClick={handleSetOffset}
                style={{ backgroundColor: 'var(--kyntus-blue)', color: 'white', border: 'none', padding: '10px 20px', borderRadius: '6px', cursor: 'pointer', fontWeight: 'bold' }}
              >
                Valider l'Offset
              </button>
            </div>
          </div>

          <div style={{ marginTop: '40px', paddingTop: '20px', borderTop: '1px solid var(--border-color)' }}>
            <button className={styles.buttonReset} onClick={handleReset}>
              ⚠️ RESET & RESTART FROM ZERO
            </button>
          </div>
        </div>
      </main>
    </div>
  );
}