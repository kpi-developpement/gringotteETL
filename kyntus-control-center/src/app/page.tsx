'use client';

import { useEffect, useState, useRef } from 'react';
import Link from 'next/link';
import { fetchStats, startSync, startPeriodSync, stopSync, resetSync, healData, cleanDuplicates, setManualOffset, SyncStats } from '../services/api';
import styles from './page.module.css';

// ==========================================
// 🚀 ICONS
// ==========================================
const IconActivity = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline></svg>;
const IconClock = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>;
const IconHealer = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path><path d="M9 12h6"></path><path d="M12 9v6"></path></svg>;
const IconPlay = () => <svg width="20" height="20" fill="currentColor" viewBox="0 0 20 20"><path d="M4 4l12 6-12 6V4z"/></svg>;
const IconStop = () => <svg width="20" height="20" fill="currentColor" viewBox="0 0 20 20"><path d="M5 5h10v10H5z"/></svg>;
const IconTrash = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>;
const IconCheckCircle = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>;
const IconAlert = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>;
const IconDatabase = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><ellipse cx="12" cy="5" rx="9" ry="3"></ellipse><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path></svg>;
const IconTerminal = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><polyline points="4 17 10 11 4 5"></polyline><line x1="12" y1="19" x2="20" y2="19"></line></svg>;
const IconEdit = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>;

export default function DashboardPage() {
  const [stats, setStats] = useState<SyncStats | null>(null);
  
  // Onglets (Tabs)
  const [activeTab, setActiveTab] = useState<'standard' | 'timemachine'>('standard');

  // Historique pour Sparklines
  const [radarHistory, setRadarHistory] = useState<number[]>(Array(12).fill(0));
  const [periodHistory, setPeriodHistory] = useState<number[]>(Array(12).fill(0));
  
  const [currentRadarSpeed, setCurrentRadarSpeed] = useState(0);
  const [currentPeriodSpeed, setCurrentPeriodSpeed] = useState(0);

  // Sélecteur Time Machine
  const [selectedPeriods, setSelectedPeriods] = useState<string[]>([]);
  const [currentSelection, setCurrentSelection] = useState('2026_M01');
  const availableYears = ['2026', '2025', '2024'];
  const availableMonths = ['M01', 'M02', 'M03', 'M04', 'M05', 'M06', 'M07', 'M08', 'M09', 'M10', 'M11', 'M12'];

  const tickCount = useRef(0);
  const lastRadarTotal = useRef(0);
  const lastPeriodTotal = useRef(0);

  const loadStats = async () => {
    const data = await fetchStats();
    if (data) {
      setStats(data);
      
      // Auto-switch Tab si Time Machine est actif
      if (data.is_running && data.current_period !== null) {
        setActiveTab('timemachine');
      } else if (data.is_running && data.current_period === null) {
        setActiveTab('standard');
      }
      
      if (data.radar_processed_total < lastRadarTotal.current) lastRadarTotal.current = data.radar_processed_total;
      if (data.period_processed_total < lastPeriodTotal.current) lastPeriodTotal.current = data.period_processed_total;

      tickCount.current += 1;
      
      if (tickCount.current >= 4) {
        const rDelta = Math.max(0, data.radar_processed_total - lastRadarTotal.current);
        const pDelta = Math.max(0, data.period_processed_total - lastPeriodTotal.current);

        setCurrentRadarSpeed(rDelta);
        setCurrentPeriodSpeed(pDelta);

        setRadarHistory(prev => [...prev.slice(1), rDelta]);
        setPeriodHistory(prev => [...prev.slice(1), pDelta]);

        lastRadarTotal.current = data.radar_processed_total;
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

  const addPeriod = () => {
    if (!selectedPeriods.includes(currentSelection)) {
      setSelectedPeriods([...selectedPeriods, currentSelection]);
    }
  };

  const removePeriod = (p: string) => {
    setSelectedPeriods(selectedPeriods.filter(item => item !== p));
  };

  // 🚀 ACTIONS DU MOTEUR
  const handleStartStandard = async () => { await startSync(); loadStats(); };
  const handleStop = async () => { await stopSync(); loadStats(); };
  
  const handleStartTimeMachine = async () => {
    if (selectedPeriods.length === 0) return alert('Veuillez ajouter au moins une période à la liste.');
    await startPeriodSync(selectedPeriods.join(','));
    loadStats();
  };

  const handleReset = async () => {
    if (window.confirm("ATTENTION : Purge Totale de la base de données. Confirmer ?")) {
      await resetSync();
      setRadarHistory(Array(12).fill(0));
      setPeriodHistory(Array(12).fill(0));
      loadStats();
    }
  };

  const handleSmartClean = async () => {
    if (window.confirm("Lancer un nettoyage des doublons ?")) {
      await cleanDuplicates();
      loadStats();
    }
  };

  // 🛡️ L'FIX HNA : La fonction pour forcer l'offset depuis la Home Page
  const handleForceOffset = async () => {
    const currentVal = activeTab === 'standard' ? stats?.current_bt_offset : stats?.period_offset;
    const newOffset = prompt(`L'offset actuel est de ${currentVal}.\nEntrez la nouvelle valeur (ex: 16000) :`, currentVal?.toString());
    
    if (newOffset !== null) {
      const val = parseInt(newOffset, 10);
      if (!isNaN(val) && val >= 0) {
        await setManualOffset(val);
        alert(`Offset forcé à ${val} avec succès !`);
        loadStatus();
      } else {
        alert("Valeur invalide.");
      }
    }
  };

  // Helper CSS & Status
  const isRunning = stats?.is_running || false;

  const totalApi = stats?.total_api || 0;
  const currentOffset = stats?.current_bt_offset || 0;
  const progressRadar = totalApi > 0 ? Math.min(100, Math.round((currentOffset / totalApi) * 100)) : 0;

  const periodTotal = stats?.period_total || 0;
  const periodOffset = stats?.period_offset || 0;
  const progressPeriod = periodTotal > 0 ? Math.min(100, Math.round((periodOffset / periodTotal) * 100)) : 0;

  const healTotal = stats?.heal_total || 0;
  const healCurrent = stats?.heal_current || 0;
  const progressHealer = healTotal > 0 ? Math.min(100, Math.round((healCurrent / healTotal) * 100)) : 100;

  const getStatusInfo = (status: string | undefined) => {
    if (!status) return { text: 'Inconnu', css: '', icon: <IconAlert /> };
    if (status.includes('404')) return { text: 'Erreur 404 (URL PHP)', css: 'statusError', icon: <IconAlert /> };
    if (status.includes('500') || status.includes('504') || status.includes('Timeout')) return { text: 'Surcharge / Timeout Bouygues', css: 'statusError', icon: <IconAlert /> };
    if (status.includes('403') || status.includes('Banni')) return { text: 'Bloqué par Akamai WAF', css: 'statusError', icon: <IconAlert /> };
    if (status.includes('Arrêt')) return { text: status, css: 'statusWarn', icon: <IconStop /> };
    return { text: status, css: 'statusGood', icon: <IconCheckCircle /> };
  };

  const rStatus = getStatusInfo(stats?.radar_status);
  const hStatus = getStatusInfo(stats?.healer_status);

  const getStatusCssClass = (type: string) => {
    if (type === 'statusError') return styles.statusError;
    if (type === 'statusWarn') return styles.statusWarn;
    if (type === 'statusGood') return styles.statusGood;
    return '';
  };

  return (
    <div className={styles.pageWrapper}>
      <div className={styles.container}>
        
        <header className={styles.header}>
          <div>
            <h1 className={styles.pageTitle}>Gringotts Control Center</h1>
            <p className={styles.pageSubtitle}>Supervision et Orchestration API</p>
          </div>
          <div className={`${styles.statusBadge} ${isRunning ? styles.statusOnline : styles.statusOffline}`}>
            {isRunning && <span className={styles.pulse}></span>}
            {isRunning ? 'DAEMON ACTIF' : 'DAEMON ARRÊTÉ'}
          </div>
        </header>

        {/* TABS SWITCHER */}
        <div className={styles.tabContainer}>
          <button 
            className={`${styles.tabBtn} ${activeTab === 'standard' ? styles.tabActiveStandard : ''}`}
            onClick={() => setActiveTab('standard')}
          >
            <IconActivity /> Mode Standard (Global)
          </button>
          <button 
            className={`${styles.tabBtn} ${activeTab === 'timemachine' ? styles.tabActiveTimeMachine : ''}`}
            onClick={() => setActiveTab('timemachine')}
          >
            <IconClock /> Mode Time Machine (Périodes)
          </button>
        </div>

        <div className={styles.mainGrid}>
          
          {/* =========================================================
              COLONNE DE GAUCHE : MOTEUR ACTIF
              ========================================================= */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '30px' }}>
            
            {/* VUE STANDARD */}
            {activeTab === 'standard' && (
              <div className={styles.glassCard}>
                <div className={styles.cardHeader}>
                  <h2 className={styles.cardTitle}>
                    <div className={`${styles.iconBox} ${styles.iconStandard}`}><IconActivity /></div>
                    Radar d'Aspiration (Global)
                  </h2>
                  <span className={`${styles.statusText} ${getStatusCssClass(rStatus.css)}`}>{rStatus.icon} {rStatus.text}</span>
                </div>

                <div className={styles.metricsGrid}>
                  <div className={styles.metricBox}>
                    <span className={styles.metricLabel}>Cible API Bouygues</span>
                    <span className={styles.metricValue}>{totalApi.toLocaleString()}</span>
                  </div>
                  <div className={styles.metricBox}>
                    <span className={styles.metricLabel}>Dossiers Téléchargés</span>
                    <span className={styles.metricValue}>{currentOffset.toLocaleString()}</span>
                  </div>
                </div>

                <div className={styles.progressSection}>
                  <div className={styles.progressHeader}>
                    <span>Progression du Scan</span>
                    <span>{progressRadar}%</span>
                  </div>
                  <div className={styles.progressTrack}>
                    <div className={styles.progressFillStandard} style={{ width: `${progressRadar}%` }}></div>
                  </div>
                  <div className={styles.progressFooter}>
                    <span>Vitesse: {currentRadarSpeed} EPS</span>
                    <span>ETA: {stats?.eta || '---'}</span>
                  </div>
                </div>

                <div className={styles.sparkline}>
                  {radarHistory.map((val, i) => (
                    <div key={i} className={styles.sparklineBar} style={{ height: `${Math.min(100, Math.max(5, (val / 300) * 100))}%`, background: '#3b82f6' }}></div>
                  ))}
                </div>

                <div className={styles.controlsGroup}>
                  {!isRunning ? (
                    <button className={`${styles.btnPrimary} ${styles.bgBlue}`} onClick={handleStartStandard}>
                      <IconPlay /> Lancer le Radar Standard
                    </button>
                  ) : (
                    <button className={`${styles.btnPrimary} ${styles.bgRed}`} onClick={handleStop}>
                      <IconStop /> Stopper le Processus
                    </button>
                  )}
                </div>
              </div>
            )}

            {/* VUE TIME MACHINE */}
            {activeTab === 'timemachine' && (
              <div className={styles.glassCard}>
                <div className={styles.cardHeader}>
                  <h2 className={styles.cardTitle}>
                    <div className={`${styles.iconBox} ${styles.iconTime}`}><IconClock /></div>
                    Time Machine (Mensuel)
                  </h2>
                  <span className={`${styles.statusText} ${getStatusCssClass(rStatus.css)}`}>{rStatus.icon} {rStatus.text}</span>
                </div>

                {/* SÉLECTEUR DE MOIS */}
                {!isRunning && (
                  <div className={styles.controlsGroup}>
                    <div className={styles.tmSelector}>
                      <select className={styles.tmSelect} value={currentSelection} onChange={e => setCurrentSelection(e.target.value)}>
                        {availableYears.map(year => (
                          <optgroup key={year} label={`Année ${year}`}>
                            {availableMonths.map(month => (
                              <option key={`${year}_${month}`} value={`${year}_${month}`}>
                                {year} - Mois {month.replace('M', '')}
                              </option>
                            ))}
                          </optgroup>
                        ))}
                      </select>
                      <button className={styles.btnSecondary} style={{ width: 'auto' }} onClick={addPeriod}>Ajouter</button>
                    </div>
                    
                    <div className={styles.tmList}>
                      {selectedPeriods.length === 0 && <span style={{ fontSize: '0.8rem', color: '#94a3b8', margin: 'auto' }}>Aucune période sélectionnée</span>}
                      {selectedPeriods.map(p => (
                        <div key={p} className={styles.tmTag}>
                          {p}
                          <button onClick={() => removePeriod(p)}><IconTrash /></button>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                <div className={styles.metricsGrid}>
                  <div className={styles.metricBox}>
                    <span className={styles.metricLabel}>Période Active</span>
                    <span className={styles.metricValue}>{stats?.current_period || '---'}</span>
                  </div>
                  <div className={styles.metricBox}>
                    <span className={styles.metricLabel}>Téléchargés (Ce mois)</span>
                    <span className={styles.metricValue}>{periodOffset.toLocaleString()} / {periodTotal.toLocaleString()}</span>
                  </div>
                </div>

                <div className={styles.progressSection}>
                  <div className={styles.progressHeader}>
                    <span>Progression du Mois</span>
                    <span>{progressPeriod}%</span>
                  </div>
                  <div className={styles.progressTrack}>
                    <div className={styles.progressFillTime} style={{ width: `${progressPeriod}%` }}></div>
                  </div>
                  <div className={styles.progressFooter}>
                    <span>Vitesse: {currentPeriodSpeed} EPS</span>
                  </div>
                </div>

                <div className={styles.sparkline}>
                  {periodHistory.map((val, i) => (
                    <div key={i} className={styles.sparklineBar} style={{ height: `${Math.min(100, Math.max(5, (val / 300) * 100))}%`, background: '#8b5cf6' }}></div>
                  ))}
                </div>

                <div className={styles.controlsGroup}>
                  {!isRunning ? (
                    <button className={`${styles.btnPrimary} ${styles.bgPurple}`} onClick={handleStartTimeMachine} disabled={selectedPeriods.length === 0}>
                      <IconPlay /> Démarrer la Time Machine
                    </button>
                  ) : (
                    <button className={`${styles.btnPrimary} ${styles.bgRed}`} onClick={handleStop}>
                      <IconStop /> Stopper la Time Machine
                    </button>
                  )}
                </div>
              </div>
            )}

            {/* HEALER (GLOBAL - TOUJOURS VISIBLE) */}
            <div className={styles.glassCard}>
              <div className={styles.cardHeader}>
                <h2 className={styles.cardTitle}>
                  <div className={`${styles.iconBox} ${styles.iconHealer}`}><IconHealer /></div>
                  Background Healer
                </h2>
                <span className={`${styles.statusText} ${getStatusCssClass(hStatus.css)}`}>{hStatus.icon} {hStatus.text}</span>
              </div>
              
              <p style={{ fontSize: '0.85rem', color: '#64748b', margin: 0 }}>
                Le Healer détecte automatiquement les interventions sans détails (importées par le Radar ou la Time Machine) et les enrichit en tâche de fond.
              </p>

              <div className={styles.metricsGrid}>
                <div className={styles.metricBox}>
                  <span className={styles.metricLabel}>Détails à restaurer</span>
                  <span className={styles.metricValue}>{healTotal.toLocaleString()}</span>
                </div>
                <div className={styles.metricBox}>
                  <span className={styles.metricLabel}>Restaurés (Lot actuel)</span>
                  <span className={styles.metricValue}>{healCurrent.toLocaleString()}</span>
                </div>
              </div>

              <div className={styles.progressSection}>
                <div className={styles.progressHeader}>
                  <span>Progression de l'Enrichissement</span>
                  <span>{progressHealer}%</span>
                </div>
                <div className={styles.progressTrack}>
                  <div className={styles.progressFillHealer} style={{ width: `${progressHealer}%` }}></div>
                </div>
              </div>
            </div>

          </div>

          {/* =========================================================
              COLONNE DE DROITE : OUTILS ET CONSOLE
              ========================================================= */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '30px' }}>
            
            <div className={styles.glassCard}>
              <h2 className={styles.cardTitle} style={{ marginBottom: '15px' }}><IconDatabase /> Outils Système</h2>
              <div className={styles.controlsGroup}>
                <button className={styles.btnSecondary} onClick={handleSmartClean}>
                  Nettoyer les doublons
                </button>
                <Link href="/interventions" style={{ textDecoration: 'none' }}>
                  <button className={styles.btnSecondary}>
                     Explorer les données brutes
                  </button>
                </Link>
                
                {/* 🛡️ L'FIX HNA : Le bouton Forcer l'Offset est là ! */}
                <button className={styles.btnSecondary} onClick={handleForceOffset}>
                  <IconEdit /> Forcer l'Offset (Reprise Manuelle)
                </button>

                <button className={styles.btnSecondary} style={{ color: '#ef4444', borderColor: '#fecaca' }} onClick={handleReset}>
                  Reset & Purge Totale
                </button>
              </div>
            </div>

            <div className={styles.consoleWrapper}>
              <div className={styles.consoleHeader}>
                <IconTerminal /> Console d'Alertes Live
              </div>
              {stats?.alerts && stats.alerts.length > 0 ? (
                <div>
                  {stats.alerts.map((alert, idx) => {
                    let cleanAlert = alert;
                    if (cleanAlert.includes('404')) cleanAlert = cleanAlert.replace(/HTTP 404.*/, 'Endpoint PHP Introuvable (URL Invalide)');
                    if (cleanAlert.includes('500') || cleanAlert.includes('504')) cleanAlert = cleanAlert.replace(/HTTP 50.*/, 'Serveur Bouygues Surchargé (Attente...)');
                    
                    const timeMatch = cleanAlert.match(/^\[(.*?)\]/);
                    const timeStr = timeMatch ? timeMatch[0] : '';
                    const msgStr = cleanAlert.replace(/^\[.*?\]\s*/, '');

                    return (
                      <div key={idx} className={styles.alertItem}>
                        <span className={styles.alertTime}>{timeStr}</span>
                        <span className={styles.alertText}>{msgStr}</span>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div style={{ padding: '20px', color: '#475569', fontSize: '0.85rem', textAlign: 'center' }}>Aucun événement enregistré.</div>
              )}
            </div>

          </div>

        </div>
      </div>
    </div>
  );
}