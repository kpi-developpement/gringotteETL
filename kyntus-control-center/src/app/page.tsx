'use client';

import { useEffect, useState, useRef } from 'react';
import Link from 'next/link';
import { fetchStats, startSync, startPeriodSync, stopSync, resetSync, healData, cleanDuplicates, SyncStats } from '../services/api';
import StatCard from '../components/StatCard';
import styles from './page.module.css';

const IconRadar = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M8.28 15.28a6 6 0 017.44 0M5.45 12.45a10 10 0 0113.1 0M2.62 9.62a14 14 0 0118.76 0M12 19a1 1 0 100-2 1 1 0 000 2z"/></svg>;
const IconHealer = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/><path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/></svg>;
const IconClock = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>;
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
  const [periodHistory, setPeriodHistory] = useState<number[]>(Array(15).fill(0));
  
  const [currentRadarSpeed, setCurrentRadarSpeed] = useState(0);
  const [currentHealerSpeed, setCurrentHealerSpeed] = useState(0);
  const [currentPeriodSpeed, setCurrentPeriodSpeed] = useState(0);

  const [periodInput, setPeriodInput] = useState('2025_M01, 2025_M02, 2025_M03');

  const tickCount = useRef(0);
  const lastRadarTotal = useRef(0);
  const lastHealerTotal = useRef(0);
  const lastPeriodTotal = useRef(0);

  const loadStats = async () => {
    const data = await fetchStats();
    if (data) {
      setStats(data);
      
      if (data.radar_processed_total < lastRadarTotal.current) lastRadarTotal.current = data.radar_processed_total;
      if (data.healer_processed_total < lastHealerTotal.current) lastHealerTotal.current = data.healer_processed_total;
      if (data.period_processed_total < lastPeriodTotal.current) lastPeriodTotal.current = data.period_processed_total;

      tickCount.current += 1;
      
      if (tickCount.current >= 5) {
        const rDelta = Math.max(0, data.radar_processed_total - lastRadarTotal.current);
        const hDelta = Math.max(0, data.healer_processed_total - lastHealerTotal.current);
        const pDelta = Math.max(0, data.period_processed_total - lastPeriodTotal.current);

        setCurrentRadarSpeed(rDelta);
        setCurrentHealerSpeed(hDelta);
        setCurrentPeriodSpeed(pDelta);

        setRadarHistory(prev => [...prev.slice(1), rDelta]);
        setHealerHistory(prev => [...prev.slice(1), hDelta]);
        setPeriodHistory(prev => [...prev.slice(1), pDelta]);

        lastRadarTotal.current = data.radar_processed_total;
        lastHealerTotal.current = data.healer_processed_total;
        lastPeriodTotal.current = data.period_processed_total;
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
  
  const handleStartPeriods = async () => {
    if (!periodInput.trim()) return alert('Veuillez entrer au moins une période.');
    await startPeriodSync(periodInput);
    loadStats();
  };

  const handleReset = async () => {
    if (window.confirm("ATTENTION : Cela va effacer TOUTES les données sur IONOS et en Local. Êtes-vous sûr ?")) {
      await resetSync();
      setRadarHistory(Array(15).fill(0));
      setHealerHistory(Array(15).fill(0));
      setPeriodHistory(Array(15).fill(0));
      setCurrentRadarSpeed(0);
      setCurrentHealerSpeed(0);
      setCurrentPeriodSpeed(0);
      loadStats();
    }
  };

  const handleSmartClean = async () => {
    if (window.confirm("Lancer un nettoyage des doublons ?")) {
      await cleanDuplicates();
      alert("Nettoyage lancé.");
      loadStats();
    }
  };

  const formatStatus = (status: string | undefined) => {
    if (!status) return 'Inconnu';
    if (status.includes('404')) return 'Erreur 404: Endpoint Introuvable';
    if (status.includes('500') || status.includes('INTERNAL_SERVER_ERROR')) return 'Serveur Bouygues Surchargé (HTTP 500)';
    if (status.includes('504')) return 'Timeout API Bouygues (HTTP 504)';
    if (status.includes('403') || status.includes('Banni')) return 'Bloqué par Akamai WAF';
    return status;
  };

  const getStatusIcon = (status: string | undefined) => {
    if (!status) return null;
    if (status.includes('50') || status.includes('404') || status.includes('Banni') || status.includes('Erreur')) {
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

  const periodTotal = stats?.period_total || 0;
  const periodOffset = stats?.period_offset || 0;
  const progressPeriod = periodTotal > 0 ? Math.min(100, Math.round((periodOffset / periodTotal) * 100)) : 0;

  const isRunning = stats?.is_running || false;
  const isTimeMachine = stats?.current_period != null;
  const etaText = stats?.eta || "En attente...";

  const getRadarInsight = () => {
    if (!isRunning) return <span className={styles.highlightNeutral}>Daemon en pause.</span>;
    if (isTimeMachine) return <span className={styles.highlightGood}>Time Machine: Période {stats.current_period}. Navigation Sécurisée.</span>;
    if (stats?.radar_status.includes("404")) return <span className={styles.highlightWarning}>Configuration URL incorrecte (404).</span>;
    if (stats?.radar_status.includes("50")) return <span className={styles.highlightWarning}>API Bouygues en Timeout. Esquive en cours.</span>;
    return <span className={styles.highlightGood}>Le Radar est fluide. Vitesse Actuelle: {currentRadarSpeed} EPS/10s.</span>;
  };

  const getHealerInsight = () => {
    if (!isRunning) return <span className={styles.highlightNeutral}>Daemon en pause.</span>;
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
        
        {/* =========================================================================================
            🚀 3 CARTES (RADAR, TIME MACHINE, HEALER) 
           ========================================================================================= */}
        <div className={styles.panel} style={{ gridColumn: '1 / -1' }}>
          <h2 className={styles.panelTitle}>Supervision des Moteurs</h2>
          
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '20px' }}>
            
            {/* 1. RADAR GLOBAL */}
            <div className={styles.engineBox} style={{ borderTop: '4px solid #3b82f6', opacity: isTimeMachine ? 0.5 : 1 }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName}><IconRadar /> Radar Global</span>
                <span className={`${styles.engineEta} ${isRunning && !isTimeMachine ? styles.engineEtaActive : ''}`}>
                  {isRunning && !isTimeMachine ? `Actif` : 'En veille'}
                </span>
              </div>
              
              <span className={styles.engineStatusText} style={{ color: stats?.radar_status.includes('Erreur') || stats?.radar_status.includes('50') || stats?.radar_status.includes('404') ? '#ef4444' : '#1e293b' }}>
                {!isTimeMachine ? <>{getStatusIcon(stats?.radar_status)} {formatStatus(stats?.radar_status)}</> : 'Mode Période actif'}
              </span>

              <div className={styles.progressStats} style={{ marginTop: '10px' }}>
                <span style={{ fontWeight: 'bold' }}>Aspiration Globale</span>
                <span style={{ fontWeight: 'bold', color: '#3b82f6' }}>{progressRadar}%</span>
              </div>
              <div style={{ fontSize: '0.8rem', color: '#64748b', marginBottom: '8px', display: 'flex', justifyContent: 'space-between' }}>
                <span>Reçu: {currentOffset.toLocaleString()}</span>
                <span>Cible: {totalApi.toLocaleString()}</span>
              </div>
              <div className={styles.progressBarBg}>
                <div className={styles.progressBarFill} style={{ width: `${progressRadar}%` }}></div>
              </div>

              <div className={styles.diagramContainer}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className={styles.diagramLabel}>Activité (EPS/10s)</span>
                  <span className={styles.speedLabel}>{currentRadarSpeed} EPS</span>
                </div>
                <div className={styles.sparkline}>
                  {radarHistory.map((val, i) => (
                    <div key={i} className={styles.sparklineBar} style={{ height: `${Math.min(100, Math.max(2, (val / 500) * 100))}%` }}></div>
                  ))}
                </div>
              </div>
            </div>

            {/* 2. TIME MACHINE (PÉRIODES) */}
            <div className={styles.engineBox} style={{ borderTop: '4px solid #8b5cf6', opacity: isRunning && !isTimeMachine ? 0.5 : 1 }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName} style={{ color: '#6d28d9' }}><IconClock /> Time Machine</span>
                <span className={`${styles.engineEta} ${isTimeMachine ? styles.engineEtaActive : ''}`} style={{ backgroundColor: isTimeMachine ? '#ede9fe' : '#f1f5f9', color: isTimeMachine ? '#6d28d9' : '#64748b' }}>
                  {isTimeMachine ? `Mois : ${stats?.current_period}` : 'En veille'}
                </span>
              </div>
              
              <span className={styles.engineStatusText} style={{ color: stats?.radar_status.includes('Erreur') || stats?.radar_status.includes('50') || stats?.radar_status.includes('404') ? '#ef4444' : '#1e293b' }}>
                {isTimeMachine ? <>{getStatusIcon(stats?.radar_status)} {formatStatus(stats?.radar_status)}</> : 'Aucune période programmée'}
              </span>

              <div className={styles.progressStats} style={{ marginTop: '10px' }}>
                <span style={{ fontWeight: 'bold' }}>Mois en cours</span>
                <span style={{ fontWeight: 'bold', color: '#8b5cf6' }}>{progressPeriod}%</span>
              </div>
              <div style={{ fontSize: '0.8rem', color: '#64748b', marginBottom: '8px', display: 'flex', justifyContent: 'space-between' }}>
                <span>Reçu: {periodOffset.toLocaleString()}</span>
                <span>Cible: {periodTotal.toLocaleString()}</span>
              </div>
              <div className={styles.progressBarBg}>
                <div className={styles.progressBarFill} style={{ width: `${progressPeriod}%`, backgroundColor: '#8b5cf6' }}></div>
              </div>

              <div className={styles.diagramContainer}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className={styles.diagramLabel}>Activité (EPS/10s)</span>
                  <span className={styles.speedLabel} style={{ color: '#8b5cf6' }}>{currentPeriodSpeed} EPS</span>
                </div>
                <div className={styles.sparkline}>
                  {periodHistory.map((val, i) => (
                    <div key={i} className={styles.sparklineBar} style={{ backgroundColor: '#a78bfa', height: `${Math.min(100, Math.max(2, (val / 500) * 100))}%` }}></div>
                  ))}
                </div>
              </div>
            </div>

            {/* 3. HEALER */}
            <div className={styles.engineBox} style={{ borderTop: '4px solid #10b981' }}>
              <div className={styles.engineHeader}>
                <span className={styles.engineName}><IconHealer /> Background Healer</span>
                <span className={`${styles.engineEta} ${stats?.is_healing && healTotal > 0 ? styles.engineEtaActive : ''}`}>
                  {stats?.is_healing && healTotal > 0 ? 'En cours' : 'En veille'}
                </span>
              </div>
              
              <span className={styles.engineStatusText} style={{ color: stats?.healer_status.includes('Erreur') || stats?.healer_status.includes('50') ? '#ef4444' : '#1e293b' }}>
                 {getStatusIcon(stats?.healer_status)} {formatStatus(stats?.healer_status)}
              </span>

              <div className={styles.progressStats} style={{ marginTop: '10px' }}>
                <span style={{ fontWeight: 'bold' }}>Détails manquants</span>
                <span style={{ fontWeight: 'bold', color: '#10b981' }}>{progressHealer}%</span>
              </div>
              <div style={{ fontSize: '0.8rem', color: '#64748b', marginBottom: '8px', display: 'flex', justifyContent: 'space-between' }}>
                <span>Restauré: {healCurrent.toLocaleString()}</span>
                <span>Cible: {healTotal.toLocaleString()}</span>
              </div>
              <div className={styles.progressBarBg}>
                <div className={styles.progressBarFillHealer} style={{ width: `${progressHealer}%` }}></div>
              </div>

              <div className={styles.diagramContainer}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className={styles.diagramLabel}>Activité (EPS/10s)</span>
                  <span className={styles.speedLabel} style={{ color: '#10b981' }}>{currentHealerSpeed} EPS</span>
                </div>
                <div className={styles.sparkline}>
                  {healerHistory.map((val, i) => (
                    <div key={i} className={`${styles.sparklineBar} ${styles.sparklineBarHealer}`} style={{ height: `${Math.min(100, Math.max(2, (val / 100) * 100))}%` }}></div>
                  ))}
                </div>
              </div>
            </div>

          </div>
        </div>

        <div className={styles.panel} style={{ display: 'flex', flexDirection: 'column' }}>
          <h2 className={styles.panelTitle}>Commandes & Actions</h2>
          <div className={styles.controlsPanel}>
            
            <div style={{ display: 'flex', gap: '10px' }}>
                {!isRunning ? (
                  <button className={`${styles.mainButton} ${styles.btnStart}`} style={{ flex: 1 }} onClick={handleStart}>
                    <IconPlay /> Mode Standard (Global)
                  </button>
                ) : (
                  <button className={`${styles.mainButton} ${styles.btnStop}`} style={{ flex: 1 }} onClick={handleStop}>
                    <IconStop /> STOPPER LE DAEMON
                  </button>
                )}
            </div>

            {/* ESPACE TIME MACHINE UI */}
            <div style={{ background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '12px', padding: '16px', marginTop: '4px' }}>
                <h3 style={{ fontSize: '0.95rem', fontWeight: 700, color: '#0f172a', marginBottom: '4px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <IconClock /> Mode Time Machine
                </h3>
                <p style={{ fontSize: '0.75rem', color: '#64748b', marginBottom: '12px' }}>
                    Aspirer mois par mois pour éviter le Timeout de Bouygues.
                </p>
                
                <input 
                    type="text" 
                    value={periodInput} 
                    onChange={e => setPeriodInput(e.target.value)}
                    placeholder="Ex: 2025_M01, 2025_M02"
                    style={{ width: '100%', padding: '12px', borderRadius: '8px', border: '1px solid #cbd5e1', fontSize: '0.85rem', marginBottom: '12px', outline: 'none' }}
                />
                
                <button 
                    onClick={handleStartPeriods} 
                    disabled={isRunning}
                    style={{ width: '100%', padding: '12px', background: isRunning ? '#cbd5e1' : '#8b5cf6', color: 'white', border: 'none', borderRadius: '8px', fontWeight: 'bold', cursor: isRunning ? 'not-allowed' : 'pointer', transition: '0.2s', display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '8px' }}
                >
                    <IconPlay /> Démarrer la Time Machine
                </button>
            </div>

            <Link href="/interventions" className={`${styles.mainButton} ${styles.btnExplore}`}>
              <IconExplore /> Explorer les données
            </Link>
            <button className={styles.btnClean} onClick={handleSmartClean}>
              <IconClean /> Nettoyer les doublons
            </button>
          </div>
          <button className={styles.btnReset} onClick={handleReset}>
             <IconAlert /> RESET TOTAL
          </button>
        </div>

        <div className={styles.analyticsPanel}>
          <h2 className={styles.analyticsTitle}>
            <IconInsights /> Console & Alertes
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

          {stats?.alerts && stats.alerts.length > 0 ? (
            <div className={styles.alertsConsole}>
              {stats.alerts.map((alert, idx) => {
                let cleanAlert = alert;
                if (cleanAlert.includes('404')) cleanAlert = cleanAlert.replace(/HTTP 404.*/, 'Endpoint PHP Introuvable (HTTP 404)');
                if (cleanAlert.includes('500') || cleanAlert.includes('INTERNAL_SERVER')) cleanAlert = cleanAlert.replace(/HTTP 500.*/, 'Serveur API Surchargé (HTTP 500)');
                if (cleanAlert.includes('504') || cleanAlert.includes('GatewayTimeout')) cleanAlert = cleanAlert.replace(/HTTP 504.*/, 'Timeout Serveur Bouygues (HTTP 504)');
                
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
          ) : (
            <div style={{ padding: '20px', color: '#64748b', fontSize: '0.9rem', textAlign: 'center' }}>Aucune alerte récente.</div>
          )}
        </div>

      </div>
    </div>
  );
}