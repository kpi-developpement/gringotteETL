'use client';

import { useState } from 'react';
import Link from 'next/link';
import styles from '../page.module.css';

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
            <div className={styles.vaultEmblem}>🧪</div>
            <div>
              <h1 className={styles.pageTitle}>GRINGOTTS // API LAB</h1>
              <p className={styles.pageSubtitle}>Test des paramètres cachés Bouygues pour contourner l&apos;Offset</p>
            </div>
          </div>

          <nav className={styles.navPills}>
            <Link href="/" className={styles.navPill}>⚡ Command Center</Link>
            <Link href="/interventions" className={styles.navPill}>🗄️ Explorateur EPS</Link>
            <Link href="/time-machine" className={styles.navPill}>⏱️ Time Machine</Link>
            <Link href="/lab" className={`${styles.navPill} ${styles.navPillActive}`}>🧪 API Lab</Link>
          </nav>
        </header>

        <div className={styles.labGrid}>
          {/* TEST 1 */}
          <div className={styles.glassCard}>
            <h3 className={styles.cardTitle}>Test 1 : Filtre Période</h3>
            <code className={styles.codeSnippet}>?periode=2025-M09</code>
            <button
              onClick={() => testEndpoint(1)}
              disabled={loading}
              className={`${styles.btnPrimary} ${styles.bgBlue}`}
            >
              {loading && activeTest === 1 ? 'Test en cours...' : 'Lancer le Test 1'}
            </button>
          </div>

          {/* TEST 2 */}
          <div className={styles.glassCard}>
            <h3 className={styles.cardTitle}>Test 2 : Date Intervention</h3>
            <code className={styles.codeSnippet}>?dateIntervention_gte=...</code>
            <button
              onClick={() => testEndpoint(2)}
              disabled={loading}
              className={`${styles.btnPrimary} ${styles.bgEmerald}`}
            >
              {loading && activeTest === 2 ? 'Test en cours...' : 'Lancer le Test 2'}
            </button>
          </div>

          {/* TEST 3 */}
          <div className={styles.glassCard}>
            <h3 className={styles.cardTitle}>Test 3 : Date Ouverture</h3>
            <code className={styles.codeSnippet}>?dateOuverture=2025-09</code>
            <button
              onClick={() => testEndpoint(3)}
              disabled={loading}
              className={`${styles.btnPrimary} ${styles.bgPurple}`}
            >
              {loading && activeTest === 3 ? 'Test en cours...' : 'Lancer le Test 3'}
            </button>
          </div>
        </div>

        {/* CONSOLE DES RÉSULTATS */}
        <div className={styles.glassCard}>
          <div className={styles.cardHeader}>
            <h2 className={styles.cardTitle}>Réponse Brute Bouygues API</h2>
            {result && (
              <span className={`${styles.statusText} ${result.http_code === 200 ? styles.statusGood : styles.statusError}`}>
                HTTP {result.http_code || 'ERROR'}
              </span>
            )}
          </div>

          {loading ? (
            <div style={{ color: '#38bdf8', fontWeight: 800, padding: '20px 0' }}>
              ⚡ Envoi de la sonde à IONOS puis Bouygues...
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