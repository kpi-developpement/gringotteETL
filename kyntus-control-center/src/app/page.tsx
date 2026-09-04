'use client';

import { useEffect, useState, useRef } from 'react';
import Link from 'next/link';
import { fetchStats, startSync, stopSync, resetSync, healData, SyncStats } from '../services/api';
import StatCard from '../components/StatCard';
import styles from './page.module.css';

// 🚀 NOUVEAU: Icons SVG Pro
const IconRadar = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M8.28 15.28a6 6 0 017.44 0M5.45 12.45a10 10 0 0113.1 0M2.62 9.62a14 14 0 0118.76 0M12 19a1 1 0 100-2 1 1 0 000 2z"/></svg>;
const IconHealer = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/><path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/></svg>;
const IconPlay = () => <svg width="18" height="18" fill="currentColor" viewBox="0 0 20 20"><path d="M4 4l12 6-12 6V4z"/></svg>;
const IconStop = () => <svg width="18" height="18" fill="currentColor" viewBox="0 0 20 20"><path d="M5 5h10v10H5z"/></svg>;
const IconExplore = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>;
const IconClean = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>;
const IconAlert = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/></svg>;
const IconInsights = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z"/></svg>;

export default function DashboardPage() {
  const [stats, setStats] = useState<SyncStats | null>(null);
  
  const [radarHistory, setRadarHistory] = useState<number[]>(Array(15).fill(0));
  const [healerHistory, setHealerHistory] = useState<number[]>(Array(15).fill(0));
  
  const [currentRadarSpeed, setCurrentRadarSpeed] = useState(0);
  const [currentHealerSpeed, setCurrentHealerSpeed] = useState(0);

  const tickCount = useRef(0);
  const lastRadarTotal = useRef(0);
  const lastHealerTotal = useRef(0);

  const loadStats = async () => {
    const data = await fetchStats();
    if (data) {
      setStats(data);
      
      if (data.radar_processed_total < lastRadarTotal.current) {
        lastRadarTotal.current = data.radar_processed_total;
        lastHealerTotal.current = data.healer_processed_total;
      }

      tickCount.current += 1;
      
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
    if (status.includes('500') || status.includes('INTERNAL_SERVER_ERROR')) return 'Serveur Bouygues Surchargé (HTTP 500)';
    if (status.includes('504')) return 'Timeout API Bouygues (HTTP 504)';
    if (status.includes('403') || status.includes('Banni')) return 'Bloqué par Akamai WAF (En pause)';
    return status;
  };

  const getStatusIcon = (status: string | undefined) => {
    if (!status) return null;
    if (status.includes('50') || status.includes('Banni') || status.includes('Erreur')) {
      return <span style={{color: '#ef4444'}}><IconAlert /></span>;
    }
    return <span style={{color: '#10b981'}}><IconPlay /></span>;
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
            <div className={styles.engineBox} style={{ borderTop: '4px solid #3b82f6' }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName}><IconRadar /> Radar Circulaire</span>
                <span className={`${styles.engineEta} ${isRunning ? styles.engineEtaActive : ''}`}>
                  {isRunning ? `ETA: ${etaText}` : 'En veille'}
                </span>
              </div>
              
              <span className={styles.engineStatusText} style={{ color: stats?.radar_status.includes('Erreur') || stats?.radar_status.includes('Banni') || stats?.radar_status.includes('50') ? '#ef4444' : '#1e293b' }}>
                {getStatusIcon(stats?.radar_status)} {formatStatus(stats?.radar_status)}
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
                    // Echelle Max: ~500 EPS per 10s (puisque batch = 100)
                    <div key={i} className={styles.sparklineBar} style={{ height: `${Math.min(100, Math.max(2, (val / 500) * 100))}%` }} title={`${val} EPS`}></div>
                  ))}
                </div>
              </div>
            </div>

            {/* MOTEUR 2 : HEALER */}
            <div className={styles.engineBox} style={{ borderTop: '4px solid #10b981' }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName}><IconHealer /> Background Healer</span>
                <span className={`${styles.engineEta} ${stats?.is_healing && healTotal > 0 ? styles.engineEtaActive : ''}`}>
                  {stats?.is_healing && healTotal > 0 ? 'En cours' : 'En veille'}
                </span>
              </div>
              
              <span className={styles.engineStatusText} style={{ color: stats?.healer_status.includes('Erreur') || stats?.healer_status.includes('Banni') || stats?.healer_status.includes('50') ? '#ef4444' : '#1e293b' }}>
                 {getStatusIcon(stats?.healer_status)} {formatStatus(stats?.healer_status)}
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
                    // Echelle Max: ~100 EPS per 10s (puisque batch = 20 et pause = 1s)
                    <div key={i} className={`${styles.sparklineBar} ${styles.sparklineBarHealer}`} style={{ height: `${Math.min(100, Math.max(2, (val / 100) * 100))}%` }} title={`${val} EPS`}></div>
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
              <button className={`${styles.mainButton} ${styles.btnStart}`} onClick={handleStart}>
                <IconPlay /> DÉMARRER LE DAEMON
              </button>
            ) : (
              <button className={`${styles.mainButton} ${styles.btnStop}`} onClick={handleStop}>
                <IconStop /> STOPPER LE DAEMON
              </button>
            )}
            <Link href="/interventions" className={`${styles.mainButton} ${styles.btnExplore}`}>
              <IconExplore /> Explorer les données
            </Link>
            <button className={styles.btnClean} onClick={handleSmartClean}>
              <IconClean /> Forcer un Smart Clean
            </button>
          </div>
          <button className={styles.btnReset} onClick={handleReset}>
             <IconAlert /> RESET TOTAL
          </button>
        </div>

        {/* ESPACE ANALYTICS */}
        <div className={styles.analyticsPanel}>
          <h2 className={styles.analyticsTitle}>
            <IconInsights /> Live Analytics & Pattern Insights
          </h2>
          
          <div className={styles.insightCards}>
            <div className={styles.insightCard}>
              <div className={styles.insightHeader}><IconRadar /> Analyse Comportement Radar</div>
              <div className={styles.insightValue}>{getRadarInsight()}</div>
            </div>
            <div className={styles.insightCard}>
              <div className={styles.insightHeader}><IconHealer /> Analyse Comportement Healer</div>
              <div className={styles.insightValue}>{getHealerInsight()}</div>
            </div>
          </div>

          {stats?.alerts && stats.alerts.length > 0 && (
            <div className={styles.alertsConsole}>
              {stats.alerts.map((alert, idx) => {
                // Nettoyage de l'alerte pour affichage
                let cleanAlert = alert;
                if (cleanAlert.includes('500') || cleanAlert.includes('INTERNAL_SERVER')) {
                  cleanAlert = cleanAlert.replace(/HTTP 500.*/, 'Serveur API Surchargé (HTTP 500)');
                }
                if (cleanAlert.includes('504') || cleanAlert.includes('GatewayTimeout')) {
                  cleanAlert = cleanAlert.replace(/HTTP 504.*/, 'Timeout Serveur Bouygues (HTTP 504)');
                }
                
                // Séparation de l'heure et du message
                const timeMatch = cleanAlert.match(/^\[(.*?)\]/);
                const timeStr = timeMatch ? timeMatch[0] : '';
                const msgStr = cleanAlert.replace(/^\[.*?\]\s*/, '');

                return (
                  <div key={idx} className={styles.alertItem}>
                    <span className={styles.alertTime}>{timeStr}</span>
                    <span>{msgStr}</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

      </div>
    </div>
  );
}