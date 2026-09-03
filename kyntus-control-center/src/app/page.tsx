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

  const handleHeal = async () => {
    if (window.confirm("Cela va nettoyer les doublons et re-télécharger les détails manquants. Continuer ?")) {
      await healData();
      loadStats();
    }
  };

  const totalApi = stats?.total_api || 0;
  const totalLocal = stats?.total_interventions_local || 0;
  const progress = totalApi > 0 ? Math.min(100, Math.round((totalLocal / totalApi) * 100)) : 0;

  // 🚀 NOUVEAU : Calcul de la progression de la réparation
  const healTotal = stats?.heal_total || 0;
  const healCurrent = stats?.heal_current || 0;
  const healProgress = healTotal > 0 ? Math.min(100, Math.round((healCurrent / healTotal) * 100)) : 0;

  return (
    <div className={styles.container}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.pageTitle}>Gringotts Sync</h1>
          <p className={styles.pageSubtitle}>Mode Turbo (Logique Java) 🚀</p>
        </div>
        <div className={styles.statusContainer}>
          {/* 🚀 NOUVEAU : Affichage du statut HEALING */}
          <span className={stats?.is_healing ? styles.statusHealing : stats?.is_running ? styles.statusOnline : styles.statusOffline}>
            {stats?.is_healing ? '🛠️ RÉPARATION EN COURS' : stats?.is_running ? '🟢 EN COURS D\'ASPIRATION' : '🔴 À L\'ARRÊT'}
          </span>
        </div>
      </header>

      <main className={styles.main}>
        
        {/* 🚀 NOUVEAU : Progress Bar de la Réparation (Visible uniquement si is_healing = true) */}
        {stats?.is_healing && (
          <div className={styles.progressContainer} style={{ borderColor: '#059669', backgroundColor: '#ecfdf5' }}>
            <div className={styles.progressHeader}>
              <span className={styles.progressTitle} style={{ color: '#065f46' }}>Progression de la Réparation (Détails manquants)</span>
              <span className={styles.progressText} style={{ color: '#047857' }}>{healCurrent.toLocaleString()} / {healTotal.toLocaleString()} ({healProgress}%)</span>
            </div>
            <div className={styles.progressBarBg}>
              <div className={styles.progressBarFill} style={{ width: `${healProgress}%`, background: 'linear-gradient(90deg, #10b981, #34d399)' }}></div>
            </div>
          </div>
        )}

        {/* Progress Bar Normale */}
        {!stats?.is_healing && (
          <div className={styles.progressContainer}>
            <div className={styles.progressHeader}>
              <span className={styles.progressTitle}>Progression Réelle (EPS Uniques)</span>
              <span className={styles.progressText}>{totalLocal.toLocaleString()} / {totalApi.toLocaleString()} ({progress}%)</span>
            </div>
            <div className={styles.progressBarBg}>
              <div className={styles.progressBarFill} style={{ width: `${progress}%` }}></div>
            </div>
          </div>
        )}

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
            {!stats?.is_running && !stats?.is_healing ? (
              <button className={styles.buttonStart} onClick={handleStart}>▶ DÉMARRER</button>
            ) : (
              <button className={styles.buttonStop} onClick={handleStop} disabled={stats?.is_healing}>
                {stats?.is_healing ? 'Veuillez patienter...' : '⏹ STOPPER'}
              </button>
            )}
            <Link href="/interventions" className={styles.buttonLink}>Voir les données</Link>
          </div>

          <div style={{ marginTop: '20px' }}>
            <button 
              onClick={handleHeal}
              disabled={stats?.is_running || stats?.is_healing}
              style={{ backgroundColor: (stats?.is_running || stats?.is_healing) ? '#9ca3af' : '#059669', color: 'white', border: 'none', padding: '12px 24px', fontSize: '1rem', fontWeight: 600, borderRadius: '6px', cursor: (stats?.is_running || stats?.is_healing) ? 'not-allowed' : 'pointer' }}
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
                disabled={stats?.is_running || stats?.is_healing}
                style={{ backgroundColor: (stats?.is_running || stats?.is_healing) ? '#9ca3af' : 'var(--kyntus-blue)', color: 'white', border: 'none', padding: '10px 20px', borderRadius: '6px', cursor: (stats?.is_running || stats?.is_healing) ? 'not-allowed' : 'pointer', fontWeight: 'bold' }}
              >
                Valider l'Offset
              </button>
            </div>
          </div>

          <div style={{ marginTop: '40px', paddingTop: '20px', borderTop: '1px solid var(--border-color)' }}>
            <button className={styles.buttonReset} onClick={handleReset} disabled={stats?.is_running || stats?.is_healing}>
              ⚠️ RESET & RESTART FROM ZERO
            </button>
          </div>
        </div>
      </main>
    </div>
  );
}