'use client';

import { useEffect, useState, useRef } from 'react';
import Link from 'next/link';
import {
  fetchStats,
  startPeriodSync,
  stopSync,
  resetSync,
  purgePeriod,
  cleanDuplicates,
  setManualOffset,
  startHealer,
  stopHealer,
  retryFailedHeals,
  fetchPeriodInfo,
  rescanPeriod,
  fixPeriods,
  setAutopilot,
  SyncStats
} from '../services/api';
import styles from './page.module.css';

/* ========================================================================== */
/* PURE VECTOR SVG ICONS (ZERO EMOJI SYSTEM)                                  */
/* ========================================================================== */
const IconBolt = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>;
const IconLayers = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>;
const IconClock = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>;
const IconFlask = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path d="M10 2v7.31"/><path d="M14 9.3V1.99"/><path d="M8.5 2h7"/><path d="M14 9.3a6.5 6.5 0 1 1-4 0"/><path d="M5.52 16h12.96"/></svg>;
const IconHealer = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="M9 12h6"/><path d="M12 9v6"/></svg>;
const IconPlay = () => <svg width="16" height="16" fill="currentColor" viewBox="0 0 20 20"><path d="M4 4l12 6-12 6V4z"/></svg>;
const IconStop = () => <svg width="16" height="16" fill="currentColor" viewBox="0 0 20 20"><path d="M5 5h10v10H5z"/></svg>;
const IconTrash = () => <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>;
const IconClose = () => <svg width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>;
const IconPlus = () => <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>;
const IconCheckCircle = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>;
const IconAlert = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>;
const IconDatabase = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>;
const IconTerminal = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>;
const IconEdit = () => <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>;
const IconRefresh = () => <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.59-9.21l-5.45 5.45"/></svg>;
const IconSearch = () => <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>;
const IconFilterClean = () => <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/></svg>;

export default function DashboardPage() {
  const [stats, setStats] = useState<SyncStats | null>(null);
  const [healerMode, setHealerMode] = useState('TIME_MACHINE');

  const [periodHistory, setPeriodHistory] = useState<number[]>(Array(12).fill(0));
  const [currentPeriodSpeed, setCurrentPeriodSpeed] = useState(0);

  const [selectedPeriods, setSelectedPeriods] = useState<string[]>([]);
  const [currentSelection, setCurrentSelection] = useState('2026_M01');
  const [periodInfo, setPeriodInfo] = useState<{offset: number, total: number} | null>(null);

  const [purgeSelection, setPurgeSelection] = useState('2026_M01');
  const [rescanSelection, setRescanSelection] = useState('2026_M02');
  const [fixSelection, setFixSelection] = useState('2026_M02');

  const availableYears = ['2026', '2025', '2024'];
  const availableMonths = ['M01', 'M02', 'M03', 'M04', 'M05', 'M06', 'M07', 'M08', 'M09', 'M10', 'M11', 'M12'];

  const tickCount = useRef(0);
  const lastPeriodTotal = useRef(0);

  const loadStats = async () => {
    const data = await fetchStats();
    if (data) {
      setStats(data);

      if (data.period_processed_total < lastPeriodTotal.current) {
        lastPeriodTotal.current = data.period_processed_total;
      }

      tickCount.current += 1;

      if (tickCount.current >= 4) {
        const pDelta = Math.max(0, data.period_processed_total - lastPeriodTotal.current);
        setCurrentPeriodSpeed(pDelta);
        setPeriodHistory(prev => [...prev.slice(1), pDelta]);
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

  useEffect(() => {
    const checkPeriod = async () => {
      const info = await fetchPeriodInfo(currentSelection);
      setPeriodInfo(info);
    };
    checkPeriod();
  }, [currentSelection]);

  const addPeriod = () => {
    if (!selectedPeriods.includes(currentSelection)) {
      setSelectedPeriods([...selectedPeriods, currentSelection]);
    }
  };

  const removePeriod = (p: string) => {
    setSelectedPeriods(selectedPeriods.filter(item => item !== p));
  };

  const handleStartTimeMachine = async () => {
    if (selectedPeriods.length === 0) return alert('Veuillez ajouter au moins une période à la liste.');
    await startPeriodSync(selectedPeriods.join(','));
    loadStats();
  };

  const handleToggleAutopilot = async () => { await setAutopilot(!stats?.autopilot?.enabled); loadStats(); };
  const handleStop = async () => { await stopSync(); loadStats(); };
  const handleStartHealer = async () => { await startHealer(healerMode); loadStats(); };
  const handleStopHealer = async () => { await stopHealer(); loadStats(); };

  const handleReset = async () => {
    if (window.confirm("ATTENTION : Purge Totale de la base de données (IONOS + Java). Confirmer ?")) {
      await resetSync();
      setPeriodHistory(Array(12).fill(0));
      loadStats();
    }
  };

  const handlePurgePeriod = async () => {
    if (window.confirm(`Voulez-vous vraiment supprimer TOUTES les données de la période ${purgeSelection} ? L'offset sera remis à zéro.`)) {
      const msg = await purgePeriod(purgeSelection);
      alert(msg);
      loadStats();
    }
  };

  const handleRescanPeriod = async () => {
    if (window.confirm(`Voulez-vous faire un SMART RESCAN de ${rescanSelection} ? Cela récupérera les ${rescanSelection} EPS manquants sans supprimer vos données actuelles.`)) {
      const msg = await rescanPeriod(rescanSelection);
      alert(msg);
      loadStats();
    }
  };

  const handleSmartClean = async () => {
    if (window.confirm("Lancer un nettoyage des doublons ?")) {
      await cleanDuplicates();
      loadStats();
    }
  };

  const handleFixPeriods = async () => {
    if (window.confirm(`Voulez-vous vraiment vérifier tous les EPS de ${fixSelection} et déplacer les intrus vers leur vrai mois ?`)) {
      const msg = await fixPeriods(fixSelection);
      alert(msg);
      loadStats();
    }
  };

  const handleForceOffset = async () => {
    const currentVal = stats?.period_offset;
    const newOffset = prompt(`L'offset actuel est de ${currentVal}.\nEntrez la nouvelle valeur (ex: 16000) :`, currentVal?.toString());

    if (newOffset !== null) {
      const val = parseInt(newOffset, 10);
      if (!isNaN(val) && val >= 0) {
        await setManualOffset(val);
        alert(`Offset forcé à ${val} avec succès !`);
        loadStats();
      } else {
        alert("Valeur invalide.");
      }
    }
  };

  const handleRetryFailedHeals = async () => {
    if (window.confirm("Voulez-vous remettre les EPS Fantômes dans la file d'attente du Healer pour une nouvelle tentative ?")) {
      const msg = await retryFailedHeals();
      alert(msg);
      loadStats();
    }
  };

  const isRunning = stats?.is_running || false;
  const isHealing = stats?.is_healing || false;

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

  const rStatus = getStatusInfo(stats?.time_machine_status);
  const hStatus = getStatusInfo(stats?.healer_status);

  const getStatusCssClass = (type: string) => {
    if (type === 'statusError') return styles.statusError;
    if (type === 'statusWarn') return styles.statusWarn;
    if (type === 'statusGood') return styles.statusGood;
    return '';
  };

  const getPeriodScanClass = () => {
    if (!periodInfo) return styles.scanNew;
    if (periodInfo.total > 0 && periodInfo.offset >= periodInfo.total) return styles.scanDone;
    if (periodInfo.offset > 0) return styles.scanResume;
    return styles.scanNew;
  };

  return (
    <div className={styles.pageWrapper}>
      <div className={styles.container}>

        {/* TOP NAVIGATION DOCK (PURE VECTOR ICONS) */}
        <header className={styles.topNavDock}>
          <div className={styles.brandGroup}>
            <div className={styles.vaultEmblem}>
              <IconBolt />
            </div>
            <div>
              <h1 className={styles.pageTitle}>GRINGOTTS // QUANTUM SYNC</h1>
              <p className={styles.pageSubtitle}>Extracteur Historique Spécialisé & Enrichissement Temps Réel</p>
            </div>
          </div>

          <nav className={styles.navPills}>
            <Link href="/" className={`${styles.navPill} ${styles.navPillActive}`}>
              <IconBolt />
              <span>Command Center</span>
            </Link>
            <Link href="/interventions" className={styles.navPill}>
              <IconLayers />
              <span>Explorateur EPS</span>
            </Link>
            <Link href="/time-machine" className={styles.navPill}>
              <IconClock />
              <span>Time Machine</span>
            </Link>
            <Link href="/lab" className={styles.navPill}>
              <IconFlask />
              <span>API Lab</span>
            </Link>
          </nav>

          <div className={`${styles.statusBadge} ${isRunning ? styles.statusOnline : styles.statusOffline}`}>
            {isRunning && <span className={styles.pulse}></span>}
            {isRunning ? 'MOTEUR ACTIF' : 'MOTEUR ARRÊTÉ'}
          </div>
        </header>

        {/* TELEMETRY RIBBON */}
        <section className={styles.telemetryRibbon}>
          <div className={styles.telemetryCard}>
            <span className={styles.telemetryLabel}>
              <span>Base Locale Totale</span>
              <IconDatabase />
            </span>
            <span className={styles.telemetryValue}>
              {(stats?.total_interventions_local || 0).toLocaleString()}
            </span>
            <span className={styles.telemetrySub}>Interventions indexées</span>
          </div>

          <div className={styles.telemetryCard}>
            <span className={styles.telemetryLabel}>
              <span>Période Active</span>
              <IconClock />
            </span>
            <span className={styles.telemetryValue}>
              {stats?.current_period || 'STANDBY'}
            </span>
            <span className={styles.telemetrySub}>Vélocité: {currentPeriodSpeed} EPS/cycle</span>
          </div>

          <div className={styles.telemetryCard}>
            <span className={styles.telemetryLabel}>
              <span>Total Aspiré (Session)</span>
              <IconBolt />
            </span>
            <span className={styles.telemetryValue}>
              {(stats?.period_processed_total || 0).toLocaleString()}
            </span>
            <span className={styles.telemetrySub}>ETA: {stats?.eta || 'Calcul...'}</span>
          </div>

          <div className={styles.telemetryCard}>
            <span className={styles.telemetryLabel}>
              <span>Enrichissement Healer</span>
              <IconHealer />
            </span>
            <span className={styles.telemetryValue}>
              {(stats?.healer_processed_total || 0).toLocaleString()}
            </span>
            <span className={styles.telemetrySub}>
              {isHealing ? `Actif (${healerMode})` : 'En veille'}
            </span>
          </div>
        </section>

        {/* MAIN BENTO GRID */}
        <div className={styles.mainGrid}>

          {/* LEFT COLUMN: TIME MACHINE + HEALER */}
          <div className={styles.columnStack}>

            {/* AUTOPILOT CARD */}
            <div className={styles.glassCard}>
              <div className={styles.cardHeader}>
                <h2 className={styles.cardTitle}>
                  <div className={`${styles.iconBox} ${styles.iconTime}`}><IconBolt /></div>
                  AutoPilot (Sync Autonome)
                </h2>
                <button
                  onClick={handleToggleAutopilot}
                  className={`${styles.btnPrimary} ${stats?.autopilot?.enabled ? styles.bgRed : styles.bgEmerald}`}
                  style={{ width: "auto", padding: "8px 16px" }}
                >
                  {stats?.autopilot?.enabled ? 'Désactiver' : 'Activer'}
                </button>
              </div>
              <div style={{ display: 'grid', gap: 6, fontSize: 13, opacity: 0.9 }}>
                <div><b>État :</b> {stats?.autopilot?.enabled ? '🟢 ' : '⚪ '}{stats?.autopilot?.status || '-'} <span style={{ opacity: 0.6 }}>(tick {stats?.autopilot?.last_tick || '-'})</span></div>
                <div><b>Périodes suivies :</b> {(stats?.autopilot?.managed_periods || []).join(', ') || '-'}</div>
                <div><b>File d&apos;attente :</b> {(stats?.queued_periods || []).join(', ') || 'vide'}</div>
                <div><b>Protection Bytel :</b> {stats?.autopilot?.throttle || '-'}</div>
                {(stats?.autopilot?.last_gaps || []).length > 0 && (
                  <div><b>Derniers trous détectés :</b> {stats?.autopilot?.last_gaps.join(', ')}</div>
                )}
              </div>
            </div>

            {/* TIME MACHINE CARD */}
            <div className={styles.glassCard}>
              <div className={styles.cardHeader}>
                <h2 className={styles.cardTitle}>
                  <div className={`${styles.iconBox} ${styles.iconTime}`}><IconClock /></div>
                  Time Machine (Aspiration Mensuelle)
                </h2>
                <span className={`${styles.statusText} ${getStatusCssClass(rStatus.css)}`}>
                  {rStatus.icon} {rStatus.text}
                </span>
              </div>

              {!isRunning && (
                <div className={styles.controlsGroup}>
                  <div className={styles.tmSelector}>
                    <select
                      className={styles.tmSelect}
                      value={currentSelection}
                      onChange={e => setCurrentSelection(e.target.value)}
                    >
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
                    <button className={`${styles.btnSecondary} ${styles.btnAuto} ${styles.btnViolet}`} onClick={addPeriod}>
                      <IconPlus />
                      <span>Ajouter</span>
                    </button>
                  </div>

                  {periodInfo && (
                    <div className={`${styles.periodScanBanner} ${getPeriodScanClass()}`}>
                      <span className={styles.scanLabelGroup}>
                        {(periodInfo.total > 0 && periodInfo.offset >= periodInfo.total) ? (
                          <><IconCheckCircle /> Période Terminée</>
                        ) : periodInfo.offset > 0 ? (
                          <><IconAlert /> Reprise Disponible</>
                        ) : (
                          <><IconBolt /> Nouvelle Période</>
                        )}
                      </span>
                      <span>
                        {periodInfo.offset.toLocaleString()} / {periodInfo.total > 0 ? periodInfo.total.toLocaleString() : '?'} EPS
                      </span>
                    </div>
                  )}

                  <div className={styles.tmList}>
                    {selectedPeriods.length === 0 && (
                      <span className={styles.tmEmptyText}>Aucune période dans la file d&apos;attente</span>
                    )}
                    {selectedPeriods.map(p => (
                      <div key={p} className={styles.tmTag}>
                        <span>{p}</span>
                        <button onClick={() => removePeriod(p)} title="Retirer">
                          <IconClose />
                        </button>
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
                  <span className={styles.metricValue}>
                    {periodOffset.toLocaleString()} / {periodTotal.toLocaleString()}
                  </span>
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
                  <span>VÉLOCITÉ LIVE : {currentPeriodSpeed} EPS</span>
                  <span>OFFSET : {periodOffset.toLocaleString()}</span>
                </div>
              </div>

              <div className={styles.sparkline}>
                {periodHistory.map((val, i) => (
                  <div
                    key={i}
                    className={styles.sparklineBar}
                    style={{ height: `${Math.min(100, Math.max(8, (val / 300) * 100))}%` }}
                  />
                ))}
              </div>

              <div className={styles.controlsGroup}>
                {!isRunning ? (
                  <button
                    className={`${styles.btnPrimary} ${styles.bgPurple}`}
                    onClick={handleStartTimeMachine}
                    disabled={selectedPeriods.length === 0}
                  >
                    <IconPlay />
                    <span>Démarrer la Time Machine</span>
                  </button>
                ) : (
                  <button className={`${styles.btnPrimary} ${styles.bgRed}`} onClick={handleStop}>
                    <IconStop />
                    <span>Stopper la Time Machine</span>
                  </button>
                )}
              </div>
            </div>

            {/* BACKGROUND HEALER CARD */}
            <div className={styles.glassCard}>
              <div className={styles.cardHeader}>
                <h2 className={styles.cardTitle}>
                  <div className={`${styles.iconBox} ${styles.iconHealer}`}><IconHealer /></div>
                  Background Healer (Enrichissement JSON)
                </h2>
                <span className={`${styles.statusText} ${getStatusCssClass(hStatus.css)}`}>
                  {hStatus.icon} {hStatus.text}
                </span>
              </div>

              <div className={styles.inlineRow}>
                <select
                  value={healerMode}
                  onChange={e => setHealerMode(e.target.value)}
                  disabled={isHealing}
                  className={styles.tmSelect}
                >
                  <option value="TIME_MACHINE">Priorité TIME MACHINE (Plus Anciens d&apos;abord)</option>
                  <option value="RADAR">Priorité RADAR (Plus Récents d&apos;abord)</option>
                </select>

                {!isHealing ? (
                  <button
                    onClick={handleStartHealer}
                    className={`${styles.btnPrimary} ${styles.bgEmerald}`}
                    style={{ width: 'auto', padding: '12px 22px' }}
                  >
                    <IconPlay />
                    <span>Démarrer</span>
                  </button>
                ) : (
                  <button
                    onClick={handleStopHealer}
                    className={`${styles.btnPrimary} ${styles.bgRed}`}
                    style={{ width: 'auto', padding: '12px 22px' }}
                  >
                    <IconStop />
                    <span>Stopper</span>
                  </button>
                )}
              </div>

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
                  <span>Progression de l&apos;Enrichissement</span>
                  <span>{progressHealer}%</span>
                </div>
                <div className={styles.progressTrack}>
                  <div className={styles.progressFillHealer} style={{ width: `${progressHealer}%` }}></div>
                </div>
              </div>
            </div>

          </div>

          {/* RIGHT COLUMN: SYSTEM TOOLS + LIVE TERMINAL */}
          <div className={styles.columnStack}>

            {/* SYSTEM TOOLS CARD */}
            <div className={styles.glassCard}>
              <div className={styles.cardHeader}>
                <h2 className={styles.cardTitle}>
                  <div className={`${styles.iconBox} ${styles.iconStandard}`}><IconDatabase /></div>
                  Outils Système & Maintenance
                </h2>
              </div>

              <div className={styles.controlsGroup}>
                <button className={styles.btnSecondary} onClick={handleSmartClean}>
                  <IconFilterClean />
                  <span>Nettoyer les doublons exacts</span>
                </button>

                {/* NETTOYER LES INTRUS */}
                <div className={styles.inlineRow}>
                  <select
                    className={`${styles.tmSelect} ${styles.tmSelectGold}`}
                    value={fixSelection}
                    onChange={e => setFixSelection(e.target.value)}
                  >
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
                  <button
                    className={`${styles.btnSecondary} ${styles.btnAuto} ${styles.btnGold}`}
                    onClick={handleFixPeriods}
                  >
                    <IconRefresh />
                    <span>Nettoyer les intrus</span>
                  </button>
                </div>

                <Link href="/interventions">
                  <button className={`${styles.btnSecondary} ${styles.btnCyan}`}>
                    <IconLayers />
                    <span>Explorer les données brutes (Interventions)</span>
                  </button>
                </Link>

                <button className={styles.btnSecondary} onClick={handleForceOffset}>
                  <IconEdit />
                  <span>Forcer l&apos;Offset (Reprise Manuelle)</span>
                </button>

                <button className={`${styles.btnSecondary} ${styles.btnViolet}`} onClick={handleRetryFailedHeals}>
                  <IconRefresh />
                  <span>Réparer les EPS Fantômes</span>
                </button>

                {/* SMART RESCAN */}
                <div className={styles.inlineRow}>
                  <select
                    className={`${styles.tmSelect} ${styles.tmSelectCyan}`}
                    value={rescanSelection}
                    onChange={e => setRescanSelection(e.target.value)}
                  >
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
                  <button
                    className={`${styles.btnSecondary} ${styles.btnAuto} ${styles.btnCyan}`}
                    onClick={handleRescanPeriod}
                    disabled={isRunning}
                  >
                    <IconSearch />
                    <span>Smart Rescan</span>
                  </button>
                </div>

                {/* PURGE PERIOD */}
                <div className={styles.inlineRow}>
                  <select
                    className={`${styles.tmSelect} ${styles.tmSelectCrimson}`}
                    value={purgeSelection}
                    onChange={e => setPurgeSelection(e.target.value)}
                  >
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
                  <button
                    className={`${styles.btnSecondary} ${styles.btnAuto} ${styles.btnCrimson}`}
                    onClick={handlePurgePeriod}
                  >
                    <IconTrash />
                    <span>Purger Mois</span>
                  </button>
                </div>

                <button
                  className={`${styles.btnPrimary} ${styles.bgRed}`}
                  style={{ marginTop: '6px' }}
                  onClick={handleReset}
                >
                  <IconTrash />
                  <span>Reset & Purge Totale</span>
                </button>
              </div>
            </div>

            {/* LIVE TERMINAL CONSOLE */}
            <div className={styles.consoleWrapper}>
              <div className={styles.consoleHeader}>
                <IconTerminal />
                <span>Console d&apos;Alertes Live</span>
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
                <div style={{ padding: '28px', color: '#64748b', fontSize: '0.84rem', textAlign: 'center', fontWeight: 700 }}>
                  Aucun événement enregistré dans le flux.
                </div>
              )}
            </div>

          </div>

        </div>
      </div>
    </div>
  );
}