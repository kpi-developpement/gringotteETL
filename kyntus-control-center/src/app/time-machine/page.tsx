'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { fetchStats, startPeriodSync, stopSync, fetchPeriodInfo } from '../../services/api';
import styles from '../page.module.css';

const IconBolt = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>;
const IconLayers = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>;
const IconClock = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>;
const IconFlask = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path d="M10 2v7.31"/><path d="M14 9.3V1.99"/><path d="M8.5 2h7"/><path d="M14 9.3a6.5 6.5 0 1 1-4 0"/><path d="M5.52 16h12.96"/></svg>;
const IconClose = () => <svg width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>;
const IconPlus = () => <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>;
const IconPlay = () => <svg width="18" height="18" fill="currentColor" viewBox="0 0 20 20"><path d="M4 4l12 6-12 6V4z"/></svg>;
const IconStop = () => <svg width="18" height="18" fill="currentColor" viewBox="0 0 20 20"><path d="M5 5h10v10H5z"/></svg>;
const IconCheckCircle = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>;
const IconAlert = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>;

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
            <Link href="/" className={styles.navPill}>
              <IconBolt />
              <span>Command Center</span>
            </Link>
            <Link href="/interventions" className={styles.navPill}>
              <IconLayers />
              <span>Explorateur EPS</span>
            </Link>
            <Link href="/time-machine" className={`${styles.navPill} ${styles.navPillActive}`}>
              <IconClock />
              <span>Time Machine</span>
            </Link>
            <Link href="/lab" className={styles.navPill}>
              <IconFlask />
              <span>API Lab</span>
            </Link>
          </nav>
        </header>

        <div className={styles.mainGrid}>
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
                <IconPlus />
                <span>Ajouter</span>
              </button>
            </div>

            {periodInfo && (
              <div className={`${styles.periodScanBanner} ${getPeriodScanClass()}`}>
                <span className={styles.scanLabelGroup}>
                  {(periodInfo.total > 0 && periodInfo.offset >= periodInfo.total) ? (
                    <><IconCheckCircle /> Terminé :</>
                  ) : periodInfo.offset > 0 ? (
                    <><IconAlert /> À reprendre :</>
                  ) : (
                    <><IconBolt /> Nouveau :</>
                  )}
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
                  <span>{p}</span>
                  {!isRunning && (
                    <button onClick={() => removePeriod(p)}>
                      <IconClose />
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>

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
                  <IconStop />
                  <span>Stopper le processus</span>
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
                  <IconPlay />
                  <span>DÉMARRER L&apos;ASPIRATION</span>
                </button>
              </>
            )}
          </div>
        </div>

      </div>
    </div>
  );
}