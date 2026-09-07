'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { fetchStats, startPeriodSync, stopSync } from '../../services/api';

const IconClock = () => <svg width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>;
const IconTrash = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>;

export default function TimeMachinePage() {
  const [selectedPeriods, setSelectedPeriods] = useState<string[]>([]);
  const [currentSelection, setCurrentSelection] = useState('2026_M01');
  const [isRunning, setIsRunning] = useState(false);
  const [currentPeriodActive, setCurrentPeriodActive] = useState<string | null>(null);

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
    const periodsStr = selectedPeriods.join(',');
    await startPeriodSync(periodsStr);
    loadStatus();
  };

  const handleStop = async () => {
    await stopSync();
    loadStatus();
  };

  return (
    <div style={{ padding: '40px 20px', maxWidth: '900px', margin: '0 auto', fontFamily: 'sans-serif' }}>
      
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '30px', paddingBottom: '20px', borderBottom: '1px solid #e2e8f0' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
          <div style={{ background: '#8b5cf6', color: 'white', padding: '12px', borderRadius: '12px' }}>
            <IconClock />
          </div>
          <div>
            <h1 style={{ fontSize: '2rem', fontWeight: 800, color: '#0f172a', margin: 0 }}>Time Machine</h1>
            <p style={{ color: '#64748b', marginTop: '4px' }}>Aspiration Bouygues sécurisée par Périodes mensuelles</p>
          </div>
        </div>
        <Link href="/" style={{ padding: '10px 20px', background: '#f1f5f9', color: '#334155', fontWeight: 'bold', borderRadius: '8px', textDecoration: 'none' }}>
          ← Retour au Dashboard
        </Link>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '30px' }}>
        
        {/* PANNEAU DE SÉLECTION */}
        <div style={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: '12px', padding: '24px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)' }}>
          <h2 style={{ fontSize: '1.2rem', fontWeight: 700, color: '#1e293b', marginBottom: '20px' }}>1. Sélectionner les mois</h2>
          
          <div style={{ display: 'flex', gap: '10px', marginBottom: '20px' }}>
            <select 
              value={currentSelection} 
              onChange={e => setCurrentSelection(e.target.value)}
              style={{ flex: 1, padding: '12px', borderRadius: '8px', border: '1px solid #cbd5e1', outline: 'none', background: '#f8fafc' }}
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
            <button onClick={addPeriod} disabled={isRunning} style={{ padding: '0 20px', background: isRunning ? '#cbd5e1' : '#10b981', color: 'white', border: 'none', borderRadius: '8px', fontWeight: 'bold', cursor: isRunning ? 'not-allowed' : 'pointer' }}>
              Ajouter
            </button>
          </div>

          <h3 style={{ fontSize: '0.9rem', fontWeight: 700, color: '#64748b', marginBottom: '10px' }}>Liste d'attente ({selectedPeriods.length}) :</h3>
          
          <div style={{ background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', minHeight: '150px', padding: '10px', display: 'flex', flexWrap: 'wrap', gap: '10px', alignContent: 'flex-start' }}>
            {selectedPeriods.length === 0 && <span style={{ color: '#94a3b8', fontSize: '0.9rem', padding: '10px' }}>Aucune période sélectionnée...</span>}
            
            {selectedPeriods.map(p => (
              <div key={p} style={{ background: '#e0e7ff', color: '#047857', padding: '6px 12px', borderRadius: '99px', fontSize: '0.85rem', fontWeight: 'bold', display: 'flex', alignItems: 'center', gap: '8px', border: '1px solid #a7f3d0' }}>
                {p}
                {!isRunning && (
                  <button onClick={() => removePeriod(p)} style={{ background: 'none', border: 'none', color: '#047857', cursor: 'pointer', padding: 0, display: 'flex' }}>
                    <IconTrash />
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* PANNEAU DE CONTRÔLE */}
        <div style={{ background: '#0f172a', border: '1px solid #1e293b', borderRadius: '12px', padding: '24px', color: 'white', boxShadow: '0 10px 15px -3px rgba(0,0,0,0.1)' }}>
          <h2 style={{ fontSize: '1.2rem', fontWeight: 700, color: '#38bdf8', marginBottom: '20px' }}>2. Exécution</h2>

          {isRunning ? (
            <div style={{ textAlign: 'center', padding: '30px 0' }}>
              <div style={{ display: 'inline-block', padding: '10px 20px', background: 'rgba(16, 185, 129, 0.2)', border: '1px solid #10b981', color: '#34d399', borderRadius: '8px', fontWeight: 'bold', marginBottom: '20px' }}>
                Moteur Time Machine Actif
              </div>
              <h3 style={{ fontSize: '1.5rem', margin: '0 0 10px 0' }}>Période en cours :</h3>
              <div style={{ fontSize: '2rem', fontWeight: 900, color: '#fcd34d' }}>{currentPeriodActive || 'Chargement...'}</div>
              
              <button onClick={handleStop} style={{ marginTop: '30px', width: '100%', padding: '16px', background: '#ef4444', color: 'white', border: 'none', borderRadius: '8px', fontWeight: 'bold', fontSize: '1.1rem', cursor: 'pointer', boxShadow: '0 4px 6px rgba(239,68,68,0.3)' }}>
                ⏹ STOPPER LE PROCESSUS
              </button>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%', justifyContent: 'center' }}>
              <p style={{ color: '#94a3b8', fontSize: '0.95rem', lineHeight: '1.6', marginBottom: '30px', textAlign: 'center' }}>
                Le moteur va aspirer les périodes une par une. L'offset sera remis à zéro à chaque nouveau mois pour garantir qu'aucune erreur 500 ne se produise côté Bouygues.
              </p>
              <button onClick={handleStart} style={{ width: '100%', padding: '16px', background: '#8b5cf6', color: 'white', border: 'none', borderRadius: '8px', fontWeight: 'bold', fontSize: '1.1rem', cursor: 'pointer', boxShadow: '0 4px 6px rgba(139,92,246,0.3)' }}>
                🚀 DÉMARRER L'ASPIRATION
              </button>
            </div>
          )}
        </div>

      </div>
    </div>
  );
}