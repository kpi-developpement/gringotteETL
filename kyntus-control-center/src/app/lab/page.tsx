'use client';

import { useState } from 'react';
import Link from 'next/link';

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
    <div style={{ padding: '40px', maxWidth: '1000px', margin: '0 auto', fontFamily: 'sans-serif' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '30px' }}>
        <div>
          <h1 style={{ fontSize: '2rem', color: '#0f172a', marginBottom: '8px' }}>🧪 API Lab (Bouygues Testing)</h1>
          <p style={{ color: '#64748b' }}>Test des paramètres cachés pour contourner l'Offset</p>
        </div>
        <Link href="/" style={{ padding: '10px 20px', background: '#e2e8f0', borderRadius: '8px', textDecoration: 'none', color: '#334155', fontWeight: 'bold' }}>
          ← Retour au Dashboard
        </Link>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '20px', marginBottom: '40px' }}>
        
        {/* BOUTON 1 */}
        <div style={{ padding: '20px', background: '#fff', borderRadius: '12px', border: '1px solid #e2e8f0', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)' }}>
          <h3 style={{ marginTop: 0, color: '#1e293b' }}>Test 1: Période</h3>
          <code style={{ display: 'block', padding: '10px', background: '#f8fafc', borderRadius: '6px', fontSize: '0.8rem', marginBottom: '15px' }}>
            ?periode=2025-M09
          </code>
          <button 
            onClick={() => testEndpoint(1)}
            disabled={loading}
            style={{ width: '100%', padding: '12px', background: '#3b82f6', color: 'white', border: 'none', borderRadius: '8px', fontWeight: 'bold', cursor: loading ? 'not-allowed' : 'pointer' }}
          >
            {loading && activeTest === 1 ? 'Test en cours...' : 'Lancer le Test 1'}
          </button>
        </div>

        {/* BOUTON 2 */}
        <div style={{ padding: '20px', background: '#fff', borderRadius: '12px', border: '1px solid #e2e8f0', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)' }}>
          <h3 style={{ marginTop: 0, color: '#1e293b' }}>Test 2: Date Interv.</h3>
          <code style={{ display: 'block', padding: '10px', background: '#f8fafc', borderRadius: '6px', fontSize: '0.8rem', marginBottom: '15px', wordBreak: 'break-all' }}>
            ?dateIntervention_gte=...
          </code>
          <button 
            onClick={() => testEndpoint(2)}
            disabled={loading}
            style={{ width: '100%', padding: '12px', background: '#10b981', color: 'white', border: 'none', borderRadius: '8px', fontWeight: 'bold', cursor: loading ? 'not-allowed' : 'pointer' }}
          >
            {loading && activeTest === 2 ? 'Test en cours...' : 'Lancer le Test 2'}
          </button>
        </div>

        {/* BOUTON 3 */}
        <div style={{ padding: '20px', background: '#fff', borderRadius: '12px', border: '1px solid #e2e8f0', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)' }}>
          <h3 style={{ marginTop: 0, color: '#1e293b' }}>Test 3: Date Ouverture</h3>
          <code style={{ display: 'block', padding: '10px', background: '#f8fafc', borderRadius: '6px', fontSize: '0.8rem', marginBottom: '15px' }}>
            ?dateOuverture=2025-09
          </code>
          <button 
            onClick={() => testEndpoint(3)}
            disabled={loading}
            style={{ width: '100%', padding: '12px', background: '#8b5cf6', color: 'white', border: 'none', borderRadius: '8px', fontWeight: 'bold', cursor: loading ? 'not-allowed' : 'pointer' }}
          >
            {loading && activeTest === 3 ? 'Test en cours...' : 'Lancer le Test 3'}
          </button>
        </div>

      </div>

      {/* CONSOLE DES RÉSULTATS */}
      <div style={{ background: '#0f172a', borderRadius: '12px', padding: '24px', color: '#f8fafc', minHeight: '300px' }}>
        <h3 style={{ margin: '0 0 15px 0', color: '#38bdf8' }}>Résultat de Bouygues API :</h3>
        {loading ? (
          <div style={{ color: '#94a3b8', fontStyle: 'italic' }}>Envoi de la requête à IONOS puis Bouygues...</div>
        ) : result ? (
          <div>
            <div style={{ marginBottom: '15px' }}>
              <span style={{ padding: '4px 10px', background: result.http_code === 200 ? '#065f46' : '#991b1b', borderRadius: '4px', fontWeight: 'bold', marginRight: '10px' }}>
                HTTP {result.http_code || 'ERROR'}
              </span>
              <span style={{ color: '#94a3b8', fontSize: '0.9rem' }}>{result.url_testee}</span>
            </div>
            <pre style={{ background: '#020617', padding: '15px', borderRadius: '8px', overflowX: 'auto', fontSize: '0.85rem', color: '#a5b4fc', border: '1px solid #1e293b' }}>
              {JSON.stringify(result.response || result, null, 2)}
            </pre>
          </div>
        ) : (
          <div style={{ color: '#64748b' }}>Cliquez sur un test pour voir la réponse ici...</div>
        )}
      </div>

    </div>
  );
}