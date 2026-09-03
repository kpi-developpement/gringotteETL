'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { fetchStats, startSync, stopSync, resetSync, setManualOffset, healData, SyncStats } from '../services/api';
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

  // 🚀 NOUVEAU : Le bouton Heal
  const handleHeal = async () => {
    if (window.confirm("Cela va nettoyer les doublons et re-télécharger les détails manquants. Continuer ?")) {
      const msg = await healData();
      alert(msg);
    }
  };

  const totalApi = stats?.total_api || 0;
  const totalLocal = stats?.total_interventions_local || 0;
  const progress = totalApi > 0 ? Math.min(100, Math.round((totalLocal / totalApi) * 100)) : 0;

  return (
    <div className={styles.container}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.pageTitle}>Gringotts Sync</h1>
          <p className={styles.pageSubtitle}>Mode Turbo (Logique Java) 🚀</p>
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
            <span className={styles.progressTitle}>Progression Réelle (EPS Uniques)</span>
            <span className={styles.progressText}>{totalLocal.toLocaleString()} / {totalApi.toLocaleString()} ({progress}%)</span>
          </div>
          <div className={styles.progressBarBg}>
            <div className={styles.progressBarFill} style={{ width: `${progress}%` }}></div>
          </div>
        </div>

        <div className={styles.grid}>
          <StatCard 
            title="Interventions Uniques Sécurisées" 
            value={stats ? stats.total_interventions_local.toLocaleString() : '---'} 
            subtitle="Base de données locale (Postgres)"
          />
          <StatCard 
            title="Offset API Bouygues" 
            value={stats ? stats.current_bt_offset.toLocaleString() : '---'} 
            subtitle="Curseur de lecture actuel"
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

          {/* 🚀 L'Bouton Heal */}
          <div style={{ marginTop: '20px' }}>
            <button 
              onClick={handleHeal}
              style={{ backgroundColor: '#059669', color: 'white', border: 'none', padding: '12px 24px', fontSize: '1rem', fontWeight: 600, borderRadius: '6px', cursor: 'pointer' }}
            >
              🛠️ RÉPARER LES DONNÉES (Smart Clean & Heal)
            </button>
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