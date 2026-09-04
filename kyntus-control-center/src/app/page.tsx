'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { fetchStats, startSync, stopSync, resetSync, healData, SyncStats } from '../services/api';
import StatCard from '../components/StatCard';
import styles from './page.module.css';

export default function DashboardPage() {
  const [stats, setStats] = useState<SyncStats | null>(null);

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

  const handleSmartClean = async () => {
    if (window.confirm("Cela va lancer un nettoyage des doublons dans la base de données. Continuer ?")) {
      await healData();
      alert("Nettoyage lancé en arrière-plan.");
      loadStats();
    }
  };

  // Calculs pour le Radar (Aspirateur)
  const totalApi = stats?.total_api || 0;
  const currentOffset = stats?.current_bt_offset || 0;
  const progressRadar = totalApi > 0 ? Math.min(100, Math.round((currentOffset / totalApi) * 100)) : 0;

  // Calculs pour le Healer (Enrichisseur)
  const healTotal = stats?.heal_total || 0;
  const healCurrent = stats?.heal_current || 0;
  const progressHealer = healTotal > 0 ? Math.min(100, Math.round((healCurrent / healTotal) * 100)) : 100;

  const isRunning = stats?.is_running || false;
  const etaText = stats?.eta || "En attente...";

  return (
    <div className={styles.container}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.pageTitle}>Gringotts Control Center</h1>
          <p className={styles.pageSubtitle}>Gestionnaire de Synchronisation 24/7 (Daemon)</p>
        </div>
        <div className={styles.statusContainer}>
          {isRunning ? (
            <span className={styles.statusOnline}>
              <span className={styles.pulse}></span> DAEMON ACTIF
            </span>
          ) : (
            <span className={styles.statusOffline}>
              DAEMON ARRÊTÉ
            </span>
          )}
        </div>
      </header>

      <div className={styles.dashboardGrid}>
        
        {/* PANNEAU DE SUPERVISION DES MOTEURS */}
        <div className={styles.panel}>
          <h2 className={styles.panelTitle}>Supervision des Moteurs</h2>
          
          <div className={styles.enginesContainer}>
            
            {/* MOTEUR 1 : RADAR */}
            <div className={styles.engineBox} style={{ borderLeft: '4px solid var(--kyntus-blue)' }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName}>📡 Radar Circulaire (Aspirateur)</span>
                <span className={`${styles.engineEta} ${isRunning ? styles.engineEtaActive : ''}`}>
                  {isRunning ? `ETA: ${etaText}` : 'En veille'}
                </span>
              </div>
              <div className={styles.progressStats}>
                <span>Progression du scan API</span>
                <span>{currentOffset.toLocaleString()} / {totalApi.toLocaleString()} ({progressRadar}%)</span>
              </div>
              <div className={styles.progressBarBg}>
                <div className={styles.progressBarFill} style={{ width: `${progressRadar}%` }}></div>
              </div>
              <p style={{ fontSize: '0.8rem', color: '#64748b', marginTop: '8px' }}>
                Récupère les EPS et statuts par lots de 300. Tourne en boucle infinie.
              </p>
            </div>

            {/* MOTEUR 2 : HEALER */}
            <div className={styles.engineBox} style={{ borderLeft: '4px solid #10b981' }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName}>🛠️ Background Healer (Enrichisseur)</span>
                <span className={`${styles.engineEta} ${stats?.is_healing && healTotal > 0 ? styles.engineEtaActive : ''}`}>
                  {stats?.is_healing && healTotal > 0 ? 'En cours...' : 'En veille (À jour)'}
                </span>
              </div>
              <div className={styles.progressStats}>
                <span>Détails récupérés (Lot en cours)</span>
                <span>{healCurrent.toLocaleString()} / {healTotal.toLocaleString()} ({progressHealer}%)</span>
              </div>
              <div className={styles.progressBarBg}>
                <div className={styles.progressBarFillHealer} style={{ width: `${progressHealer}%` }}></div>
              </div>
              <p style={{ fontSize: '0.8rem', color: '#64748b', marginTop: '8px' }}>
                Récupère les détails manquants (20 par 20) furtivement pour éviter Akamai WAF.
              </p>
            </div>

          </div>

          <div className={styles.statsGrid}>
            <StatCard 
              title="Total Interventions (DW Local)" 
              value={stats ? stats.total_interventions_local.toLocaleString() : '---'} 
            />
            <StatCard 
              title="Détails Manquants" 
              value={stats ? stats.heal_total.toLocaleString() : '---'} 
            />
          </div>
        </div>

        {/* PANNEAU DE CONTRÔLE */}
        <div className={styles.panel} style={{ display: 'flex', flexDirection: 'column' }}>
          <h2 className={styles.panelTitle}>Commandes</h2>
          
          <div className={styles.controlsPanel}>
            {!isRunning ? (
              <button className={`${styles.mainButton} ${styles.btnStart}`} onClick={handleStart}>
                ▶ DÉMARRER LE DAEMON
              </button>
            ) : (
              <button className={`${styles.mainButton} ${styles.btnStop}`} onClick={handleStop}>
                ⏹ STOPPER LE DAEMON
              </button>
            )}

            <Link href="/interventions" className={`${styles.mainButton} ${styles.btnExplore}`}>
              🔍 Explorer les données
            </Link>

            <button className={styles.btnClean} onClick={handleSmartClean}>
              🧹 Forcer un Smart Clean (Doublons)
            </button>
          </div>

          <button className={styles.btnReset} onClick={handleReset}>
            ⚠️ RESET TOTAL (DANGER)
          </button>
        </div>

      </div>
    </div>
  );
}