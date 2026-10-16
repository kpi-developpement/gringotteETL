'use client';

import { useState } from 'react';
import Link from 'next/link';
import styles from '../page.module.css';

const IconBolt = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>;
const IconLayers = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>;
const IconClock = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>;
const IconFlask = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><path d="M10 2v7.31"/><path d="M14 9.3V1.99"/><path d="M8.5 2h7"/><path d="M14 9.3a6.5 6.5 0 1 1-4 0"/><path d="M5.52 16h12.96"/></svg>;
const IconPlay = () => <svg width="16" height="16" fill="currentColor" viewBox="0 0 20 20"><path d="M4 4l12 6-12 6V4z"/></svg>;
const IconTerminal = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>;

export default function ApiLabPage() {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [activeTest, setActiveTest] = useState<number | null>(null);

  const testEndpoint = async (type: number) => {
    setLoading(true);
    setActiveTest(type);
    setResult(null);
    try {
      const res = await fetch(`http://localhost:8117/api/dashboard/lab/test-endpoint?type=${type}`);
      const data = await res.json();
      setResult(data);
    } catch (err: any) {
      setResult({ error: err.message });
    }
    setLoading(false);
  };

  return (
    <div className={styles.pageWrapper}>
      <div className={styles.container}>

        <header className={styles.topNavDock}>
          <div className={styles.brandGroup}>
            <div className={styles.vaultEmblem}><IconFlask /></div>
            <div>
              <h1 className={styles.pageTitle}>GRINGOTTS // API LAB</h1>
              <p className={styles.pageSubtitle}>Test des paramètres cachés Bouygues pour contourner l&apos;Offset</p>
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
            <Link href="/time-machine" className={styles.navPill}>
              <IconClock />
              <span>Time Machine</span>
            </Link>
            <Link href="/lab" className={`${styles.navPill} ${styles.navPillActive}`}>
              <IconFlask />
              <span>API Lab</span>
            </Link>
          </nav>
        </header>

        <div className={styles.labGrid}>
          <div className={styles.glassCard}>
            <h3 className={styles.cardTitle}>Test 1 : Filtre Période</h3>
            <code className={styles.codeSnippet}>?periode=2025-M09</code>
            <button
              onClick={() => testEndpoint(1)}
              disabled={loading}
              className={`${styles.btnPrimary} ${styles.bgBlue}`}
            >
              <IconPlay />
              <span>{loading && activeTest === 1 ? 'Test en cours...' : 'Lancer le Test 1'}</span>
            </button>
          </div>

          <div className={styles.glassCard}>
            <h3 className={styles.cardTitle}>Test 2 : Date Intervention</h3>
            <code className={styles.codeSnippet}>?dateIntervention_gte=...</code>
            <button
              onClick={() => testEndpoint(2)}
              disabled={loading}
              className={`${styles.btnPrimary} ${styles.bgEmerald}`}
            >
              <IconPlay />
              <span>{loading && activeTest === 2 ? 'Test en cours...' : 'Lancer le Test 2'}</span>
            </button>
          </div>

          <div className={styles.glassCard}>
            <h3 className={styles.cardTitle}>Test 3 : Date Ouverture</h3>
            <code className={styles.codeSnippet}>?dateOuverture=2025-09</code>
            <button
              onClick={() => testEndpoint(3)}
              disabled={loading}
              className={`${styles.btnPrimary} ${styles.bgPurple}`}
            >
              <IconPlay />
              <span>{loading && activeTest === 3 ? 'Test en cours...' : 'Lancer le Test 3'}</span>
            </button>
          </div>
        </div>

        <div className={styles.glassCard}>
          <div className={styles.cardHeader}>
            <h2 className={styles.cardTitle}>
              <div className={`${styles.iconBox} ${styles.iconStandard}`}><IconTerminal /></div>
              Réponse Brute Bouygues API
            </h2>
            {result && (
              <span className={`${styles.statusText} ${result.http_code === 200 ? styles.statusGood : styles.statusError}`}>
                HTTP {result.http_code || 'ERROR'}
              </span>
            )}
          </div>

          {loading ? (
            <div style={{ color: '#38bdf8', fontWeight: 800, padding: '20px 0', display: 'flex', alignItems: 'center', gap: '10px' }}>
              <IconBolt />
              <span>Envoi de la sonde à IONOS puis Bouygues...</span>
            </div>
          ) : result ? (
            <div className={styles.controlsGroup}>
              {result.url_testee && (
                <code className={styles.codeSnippet}>{result.url_testee}</code>
              )}
              <pre className={styles.jsonOutputPre}>
                {JSON.stringify(result.response || result, null, 2)}
              </pre>
            </div>
          ) : (
            <div style={{ color: '#64748b', fontWeight: 700, padding: '20px 0' }}>
              Sélectionnez l&apos;un des 3 protocoles ci-dessus pour inspecter le payload JSON.
            </div>
          )}
        </div>

      </div>
    </div>
  );
}