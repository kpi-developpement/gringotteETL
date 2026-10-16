'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { fetchStats, startPeriodSync, stopSync, fetchPeriodInfo } from '../../services/api';
import styles from '../page.module.css';

const IconClock = () => <svg width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>;
const IconTrash = () => <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>;
const IconPlay = () => <svg width="18" height="18" fill="currentColor" viewBox="0 0 20 20"><path d="M4 4l12 6-12 6V4z"/></svg>;
const IconStop = () => <svg width="18" height="18" fill="currentColor" viewBox="0 0 20 20"><path d="M5 5h10v10H5z"/></svg>;

export default function TimeMachinePage() {
  const [selectedPeriods, setSelectedPeriods] = useState<string[]>([]);
  const [currentSelection, setCurrentSelection] = useState('2026_M01');

  const [isRunning, setIsRunning] = useState(false);
  const [isActionPending, setIsActionPending] = useState(false);
  const [currentPeriodActive, setCurrentPeriodActive] = useState<string | null>(null);
  const [periodInfo, setPeriodInfo] = useState<{offset: number, total: number} | null>(null);

  const availableYears = ['2026', '2025', '2024'];
  const availableMonths = ['M01', 'M02', 'M03', 'M04', 'M05', 'M06', 'M07', 'M08', 'M09', 'M10', 'M11', 'M12'];

  const loadStatus = async () => {
    const data = await fetchStats();
    if (data) {
      setIsRunning(data.is_running && data.current_period !== null);
      setCurrentPeriodActive(data.current_period);
    }
  };

  useEffect(() => {
    loadStatus();
    const interval = setInterval(loadStatus, 2000);
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

  const handleStart = async () => {
    if (selectedPeriods.length === 0) return alert('Ajoutez au moins une période à la liste.');
    setIsActionPending(true);
    await startPeriodSync(selectedPeriods.join(','));
    await loadStatus();
    setIsActionPending(false);
  };

  const handleStop = async () => {
    setIsActionPending(true);
    await stopSync();
    await loadStatus();
    setIsActionPending(false);
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

        <header className={styles.topNavDock}>
          <div className={styles.brandGroup}>
            <div className={styles.vaultEmblem}><IconClock /></div>
            <div>
              <h1 className={styles.pageTitle}>CHRONOS // TIME MACHINE</h1>
              <p className={styles.pageSubtitle}>Aspiration Bouygues sécurisée par Périodes mensuelles</p>
            </div>
          </div>

          <nav className={styles.navPills}>
            <Link href="/" className={styles.navPill}>⚡ Command Center</Link>
            <Link href="/interventions" className={styles.navPill}>🗄️ Explorateur EPS</Link>
            <Link href="/time-machine" className={`${styles.navPill} ${styles.navPillActive}`}>⏱️ Time Machine</Link>
            <Link href="/lab" className={styles.navPill}>🧪 API Lab</Link>
          </nav>
        </header>

        <div className={styles.mainGrid}>
          {/* PANNEAU DE SÉLECTION */}
          <div className={styles.glassCard}>
            <div className={styles.cardHeader}>
              <h2 className={styles.cardTitle}>
                <div className={`${styles.iconBox} ${styles.iconTime}`}><IconClock /></div>
                1. Sélectionner les mois à aspirer
              </h2>
            </div>

            <div className={styles.tmSelector}>
              <select
                value={currentSelection}
                onChange={e => setCurrentSelection(e.target.value)}
                className={styles.tmSelect}
                disabled={isRunning}
              >
                {availableYears.map(year => (
                  <optgroup key={year} label={`Année ${year}`}>
                    {availableMonths.map(month => (
                      <option key={`${year}_${month}`} value={`${year}_${month}`}>
                        {year} — Mois {month.replace('M', '')}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </select>
              <button
                onClick={addPeriod}
                disabled={isRunning}
                className={`${styles.btnSecondary} ${styles.btnAuto} ${styles.btnViolet}`}
              >
                + Ajouter
              </button>
            </div>

            {periodInfo && (
              <div className={`${styles.periodScanBanner} ${getPeriodScanClass()}`}>
                <span>
                  {(periodInfo.total > 0 && periodInfo.offset >= periodInfo.total)
                    ? '✅ Terminé :'
                    : (periodInfo.offset > 0 ? '⚠️ À reprendre :' : '🆕 Nouveau :')}
                </span>
                <span>
                  {periodInfo.offset.toLocaleString()} / {periodInfo.total > 0 ? periodInfo.total.toLocaleString() : '?'} EPS
                </span>
              </div>
            )}

            <div className={styles.metricLabel}>
              Liste d&apos;attente ({selectedPeriods.length})
            </div>

            <div className={styles.tmList} style={{ minHeight: '150px', alignContent: 'flex-start' }}>
              {selectedPeriods.length === 0 && (
                <span className={styles.tmEmptyText}>Aucune période sélectionnée...</span>
              )}
              {selectedPeriods.map(p => (
                <div key={p} className={styles.tmTag}>
                  {p}
                  {!isRunning && (
                    <button onClick={() => removePeriod(p)}><IconTrash /></button>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* PANNEAU D'EXÉCUTION */}
          <div className={styles.glassCard} style={{ justifyContent: 'space-between' }}>
            <div className={styles.cardHeader}>
              <h2 className={styles.cardTitle}>2. Contrôle d&apos;Exécution</h2>
              <span className={`${styles.statusBadge} ${isRunning ? styles.statusOnline : styles.statusOffline}`}>
                {isRunning && <span className={styles.pulse}></span>}
                {isRunning ? 'MOTEUR ACTIF' : 'STANDBY'}
              </span>
            </div>

            {isRunning ? (
              <div style={{ textAlign: 'center', padding: '24px 0' }}>
                <div className={styles.metricLabel}>PÉRIODE EN COURS D&apos;ASPIRATION</div>
                <div style={{ fontSize: '2.6rem', fontWeight: 900, color: '#fbbf24', margin: '14px 0 32px 0', fontFamily: 'monospace' }}>
                  {currentPeriodActive || 'Chargement...'}
                </div>

                <button
                  onClick={handleStop}
                  disabled={isActionPending}
                  className={`${styles.btnPrimary} ${styles.bgRed}`}
                >
                  <IconStop /> Stopper le processus
                </button>
              </div>
            ) : (
              <>
                <p style={{ color: '#94a3b8', fontSize: '0.92rem', lineHeight: 1.65 }}>
                  Le moteur va aspirer les périodes une par une. L&apos;offset sera sauvegardé automatiquement pour chaque mois. Si vous relancez un mois déjà entamé, il reprendra exactement là où il s&apos;est arrêté.
                </p>
                <button
                  onClick={handleStart}
                  disabled={isActionPending || selectedPeriods.length === 0}
                  className={`${styles.btnPrimary} ${styles.bgPurple}`}
                >
                  <IconPlay /> DÉMARRER L&apos;ASPIRATION
                </button>
              </>
            )}
          </div>
        </div>

      </div>
    </div>
  );
}