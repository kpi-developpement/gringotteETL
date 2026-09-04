'use client';

import { useEffect, useState, useRef } from 'react';
import Link from 'next/link';
import { fetchStats, startSync, stopSync, resetSync, healData, SyncStats } from '../services/api';
import StatCard from '../components/StatCard';
import styles from './page.module.css';

export default function DashboardPage() {
  const [stats, setStats] = useState<SyncStats | null>(null);
  
  // Historique pour les Diagrammes (15 derniers ticks de 10s)
  const [radarHistory, setRadarHistory] = useState<number[]>(Array(15).fill(0));
  const [healerHistory, setHealerHistory] = useState<number[]>(Array(15).fill(0));
  
  // Vitesses actuelles (EPS/10s)
  const [currentRadarSpeed, setCurrentRadarSpeed] = useState(0);
  const [currentHealerSpeed, setCurrentHealerSpeed] = useState(0);

  const tickCount = useRef(0);
  const lastRadarTotal = useRef(0);
  const lastHealerTotal = useRef(0);

  const loadStats = async () => {
    const data = await fetchStats();
    if (data) {
      setStats(data);
      
      // Si redémarrage ou reset (les totaux baissent), on reset nos refs
      if (data.radar_processed_total < lastRadarTotal.current) {
        lastRadarTotal.current = data.radar_processed_total;
        lastHealerTotal.current = data.healer_processed_total;
      }

      // Incrémenter le compteur de ticks (on fetch toutes les 2s)
      tickCount.current += 1;
      
      // Toutes les 10 secondes (5 ticks de 2s), on calcule la vitesse
      if (tickCount.current >= 5) {
        const rDelta = Math.max(0, data.radar_processed_total - lastRadarTotal.current);
        const hDelta = Math.max(0, data.healer_processed_total - lastHealerTotal.current);

        setCurrentRadarSpeed(rDelta);
        setCurrentHealerSpeed(hDelta);

        setRadarHistory(prev => [...prev.slice(1), rDelta]);
        setHealerHistory(prev => [...prev.slice(1), hDelta]);

        lastRadarTotal.current = data.radar_processed_total;
        lastHealerTotal.current = data.healer_processed_total;
        tickCount.current = 0;
      }
    }
  };

  useEffect(() => {
    loadStats();
    const interval = setInterval(loadStats, 2000);
    return () => clearInterval(interval);
  }, []);

  const handleStart = async () => { await startSync(); loadStats(); };
  const handleStop = async () => { await stopSync(); loadStats(); };
  
  const handleReset = async () => {
    if (window.confirm("ATTENTION : Cela va effacer TOUTES les données sur IONOS et en Local. Êtes-vous sûr ?")) {
      await resetSync();
      setRadarHistory(Array(15).fill(0));
      setHealerHistory(Array(15).fill(0));
      setCurrentRadarSpeed(0);
      setCurrentHealerSpeed(0);
      loadStats();
    }
  };

  const handleSmartClean = async () => {
    if (window.confirm("Lancer un nettoyage des doublons ?")) {
      await healData();
      alert("Nettoyage lancé.");
      loadStats();
    }
  };

  const formatStatus = (status: string | undefined) => {
    if (!status) return 'Inconnu';
    if (status.includes('500') || status.includes('INTERNAL_SERVER_ERROR')) return '⚠️ Serveur Bouygues Surchargé (HTTP 500)';
    if (status.includes('504')) return '⚠️ Timeout API Bouygues (HTTP 504)';
    if (status.includes('403') || status.includes('Banni')) return '⛔ Bloqué par Akamai WAF (En pause)';
    return status;
  };

  const totalApi = stats?.total_api || 0;
  const currentOffset = stats?.current_bt_offset || 0;
  const progressRadar = totalApi > 0 ? Math.min(100, Math.round((currentOffset / totalApi) * 100)) : 0;

  const healTotal = stats?.heal_total || 0;
  const healCurrent = stats?.heal_current || 0;
  const progressHealer = healTotal > 0 ? Math.min(100, Math.round((healCurrent / healTotal) * 100)) : 100;

  const isRunning = stats?.is_running || false;
  const etaText = stats?.eta || "En attente...";

  const getRadarInsight = () => {
    if (!isRunning) return <span className={styles.highlightNeutral}>Daemon en pause.</span>;
    if (stats?.radar_status.includes("50")) return <span className={styles.highlightWarning}>API Bouygues en Timeout. Esquive en cours.</span>;
    if (stats?.radar_status.includes("Banni") || stats?.radar_status.includes("403")) return <span className={styles.highlightWarning}>Pare-feu Akamai actif. Le Radar esquive et patiente 15m.</span>;
    return <span className={styles.highlightGood}>Le Radar est fluide. Vitesse Actuelle: {currentRadarSpeed} EPS/10s.</span>;
  };

  const getHealerInsight = () => {
    if (!isRunning) return <span className={styles.highlightNeutral}>Daemon en pause.</span>;
    if (stats?.radar_status.includes("50") && stats?.healer_status.includes("Lot sauvegardé")) {
      return <span className={styles.highlightGood}>Twin-Turbo Actif: Le Radar bloque, l'Healer accélère le nettoyage.</span>;
    }
    if (healTotal === 0) return <span className={styles.highlightGood}>Base de données 100% qualifiée.</span>;
    return <span className={styles.highlightNeutral}>Enrichissement furtif en cours. Vitesse Actuelle: {currentHealerSpeed} EPS/10s.</span>;
  };

  return (
    <div className={styles.container}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.pageTitle}>Gringotts Control Center</h1>
          <p className={styles.pageSubtitle}>Gestionnaire de Synchronisation 24/7 (Daemon)</p>
        </div>
        <div className={styles.statusContainer}>
          {isRunning ? (
            <span className={styles.statusOnline}><span className={styles.pulse}></span> DAEMON ACTIF</span>
          ) : (
            <span className={styles.statusOffline}>DAEMON ARRÊTÉ</span>
          )}
        </div>
      </header>

      <div className={styles.dashboardGrid}>
        
        <div className={styles.panel}>
          <h2 className={styles.panelTitle}>Supervision des Moteurs</h2>
          
          <div className={styles.enginesContainer}>
            
            {/* MOTEUR 1 : RADAR */}
            <div className={styles.engineBox} style={{ borderTop: '4px solid var(--kyntus-blue)' }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName}>📡 Radar Circulaire</span>
                <span className={`${styles.engineEta} ${isRunning ? styles.engineEtaActive : ''}`}>
                  {isRunning ? `ETA: ${etaText}` : 'En veille'}
                </span>
              </div>
              
              <span className={styles.engineStatusText} style={{ color: stats?.radar_status.includes('Erreur') || stats?.radar_status.includes('Banni') || stats?.radar_status.includes('50') ? '#ef4444' : '#1e5a99' }}>
                ↳ Status: {formatStatus(stats?.radar_status)}
              </span>

              <div className={styles.progressStats}>
                <span>Progression Aspiration</span>
                <span>{currentOffset.toLocaleString()} / {totalApi.toLocaleString()} ({progressRadar}%)</span>
              </div>
              <div className={styles.progressBarBg}>
                <div className={styles.progressBarFill} style={{ width: `${progressRadar}%` }}></div>
              </div>

              {/* DIAGRAMME RADAR */}
              <div className={styles.diagramContainer}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className={styles.diagramLabel}>Activité Radar (EPS/10s)</span>
                  <span className={styles.speedLabel}>{currentRadarSpeed} EPS</span>
                </div>
                <div className={styles.sparkline}>
                  {radarHistory.map((val, i) => (
                    // Echelle Max: ~1500 EPS per 10s
                    <div key={i} className={styles.sparklineBar} style={{ height: `${Math.min(100, Math.max(2, (val / 1500) * 100))}%` }} title={`${val} EPS`}></div>
                  ))}
                </div>
              </div>
            </div>

            {/* MOTEUR 2 : HEALER */}
            <div className={styles.engineBox} style={{ borderTop: '4px solid #10b981' }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName}>🛠️ Background Healer</span>
                <span className={`${styles.engineEta} ${stats?.is_healing && healTotal > 0 ? styles.engineEtaActive : ''}`}>
                  {stats?.is_healing && healTotal > 0 ? 'En cours' : 'En veille'}
                </span>
              </div>
              
              <span className={styles.engineStatusText} style={{ color: stats?.healer_status.includes('Erreur') || stats?.healer_status.includes('Banni') || stats?.healer_status.includes('50') ? '#ef4444' : '#10b981' }}>
                ↳ Status: {formatStatus(stats?.healer_status)}
              </span>

              <div className={styles.progressStats}>
                <span>Détails récupérés (Lot en cours)</span>
                <span>{healCurrent.toLocaleString()} / {healTotal.toLocaleString()} ({progressHealer}%)</span>
              </div>
              <div className={styles.progressBarBg}>
                <div className={styles.progressBarFillHealer} style={{ width: `${progressHealer}%` }}></div>
              </div>

              {/* DIAGRAMME HEALER */}
              <div className={styles.diagramContainer}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className={styles.diagramLabel}>Activité Healer (EPS/10s)</span>
                  <span className={styles.speedLabel} style={{ color: '#10b981' }}>{currentHealerSpeed} EPS</span>
                </div>
                <div className={styles.sparkline}>
                  {healerHistory.map((val, i) => (
                    // Echelle Max: ~200 EPS per 10s
                    <div key={i} className={`${styles.sparklineBar} ${styles.sparklineBarHealer}`} style={{ height: `${Math.min(100, Math.max(2, (val / 200) * 100))}%` }} title={`${val} EPS`}></div>
                  ))}
                </div>
              </div>
            </div>
          </div>

          <div className={styles.statsGrid}>
            <StatCard title="Total Interventions (DW Local)" value={stats ? stats.total_interventions_local.toLocaleString() : '---'} />
            <StatCard title="Détails Manquants" value={stats ? stats.heal_total.toLocaleString() : '---'} />
          </div>
        </div>

        <div className={styles.panel} style={{ display: 'flex', flexDirection: 'column' }}>
          <h2 className={styles.panelTitle}>Commandes</h2>
          <div className={styles.controlsPanel}>
            {!isRunning ? (
              <button className={`${styles.mainButton} ${styles.btnStart}`} onClick={handleStart}>▶ DÉMARRER LE DAEMON</button>
            ) : (
              <button className={`${styles.mainButton} ${styles.btnStop}`} onClick={handleStop}>⏹ STOPPER LE DAEMON</button>
            )}
            <Link href="/interventions" className={`${styles.mainButton} ${styles.btnExplore}`}>🔍 Explorer les données</Link>
            <button className={styles.btnClean} onClick={handleSmartClean}>🧹 Forcer un Smart Clean</button>
          </div>
          <button className={styles.btnReset} onClick={handleReset}>⚠️ RESET TOTAL</button>
        </div>

        {/* ESPACE ANALYTICS */}
        <div className={styles.analyticsPanel}>
          <h2 className={styles.analyticsTitle}>
            <svg width="20" height="20" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
            Live Analytics & Pattern Insights
          </h2>
          
          <div className={styles.insightCards}>
            <div className={styles.insightCard}>
              <div className={styles.insightHeader}>Analyse Comportement Radar</div>
              <div className={styles.insightValue}>{getRadarInsight()}</div>
            </div>
            <div className={styles.insightCard}>
              <div className={styles.insightHeader}>Analyse Comportement Healer</div>
              <div className={styles.insightValue}>{getHealerInsight()}</div>
            </div>
          </div>

          {stats?.alerts && stats.alerts.length > 0 && (
            <div className={styles.alertsConsole}>
              {stats.alerts.map((alert, idx) => (
                <div key={idx} className={styles.alertItem}>
                  {alert.includes('500') || alert.includes('INTERNAL_SERVER') 
                    ? alert.replace(/HTTP 500.*/, '⚠️ API Surchargée (HTTP 500)')
                    : alert}
                </div>
              ))}
            </div>
          )}
        </div>

      </div>
    </div>
  );
}