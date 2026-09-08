'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { fetchStats, startPeriodSync, stopSync, cancelResume } from '../../services/api';

const IconClock = () => <svg width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>;
const IconTrash = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>;
const IconPlay = () => <svg width="18" height="18" fill="currentColor" viewBox="0 0 20 20"><path d="M4 4l12 6-12 6V4z"/></svg>;
const IconX = () => <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>;

export default function TimeMachinePage() {
  const [selectedPeriods, setSelectedPeriods] = useState<string[]>([]);
  const [currentSelection, setCurrentSelection] = useState('2026_M01');
  
  const [isRunning, setIsRunning] = useState(false);
  const [currentPeriodActive, setCurrentPeriodActive] = useState<string | null>(null);
  
  const [savedPeriod, setSavedPeriod] = useState<string | null>(null);
  const [savedOffset, setSavedOffset] = useState<number>(0);
  const [savedTotal, setSavedTotal] = useState<number>(0);

  const availableYears = ['2026', '2025', '2024'];
  const availableMonths = ['M01', 'M02', 'M03', 'M04', 'M05', 'M06', 'M07', 'M08', 'M09', 'M10', 'M11', 'M12'];

  const loadStatus = async () => {
    const data = await fetchStats();
    if (data) {
      setIsRunning(data.is_running && data.current_period !== null);
      setCurrentPeriodActive(data.current_period);
      
      if (!data.is_running && data.saved_period && data.saved_period !== "") {
        setSavedPeriod(data.saved_period);
        setSavedOffset(data.period_offset);
        setSavedTotal(data.period_total);
      } else {
        setSavedPeriod(null);
      }
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

  const handleResume = async () => {
    if (savedPeriod) {
      await startPeriodSync(savedPeriod);
      loadStatus();
    }
  };

  const handleCancelResume = async () => {
    if (confirm("Voulez-vous vraiment annuler cette session ? L'avancement sera perdu et vous devrez recommencer depuis zéro.")) {
      await cancelResume();
      setSavedPeriod(null);
      loadStatus();
    }
  };

  const handleStop = async () => {
    await stopSync();
    loadStatus();
  };

  // 🛡️ L'FIX HNA : On force la variable à être un vrai Booléen (true/false) pour que TypeScript soit content
  const hasSavedSession = Boolean(!isRunning && savedPeriod && savedPeriod !== "" && savedOffset < savedTotal);

  return (
    <div style={{ padding: '40px 20px', maxWidth: '900px', margin: '0 auto', fontFamily: 'system-ui, -apple-system, sans-serif' }}>
      
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '30px', paddingBottom: '20px', borderBottom: '1px solid #e2e8f0' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
          <div style={{ background: '#8b5cf6', color: 'white', padding: '12px', borderRadius: '12px', boxShadow: '0 4px 10px rgba(139, 92, 246, 0.3)' }}>
            <IconClock />
          </div>
          <div>
            <h1 style={{ fontSize: '2rem', fontWeight: 900, color: '#0f172a', margin: 0, letterSpacing: '-1px' }}>Time Machine</h1>
            <p style={{ color: '#64748b', marginTop: '4px', fontWeight: 600 }}>Aspiration Bouygues sécurisée par Périodes mensuelles</p>
          </div>
        </div>
        <Link href="/" style={{ padding: '10px 20px', background: '#f1f5f9', color: '#334155', fontWeight: 'bold', borderRadius: '8px', textDecoration: 'none', border: '1px solid #e2e8f0', transition: 'all 0.2s' }}>
          ← Retour au Dashboard
        </Link>
      </div>

      {/* 🚀 BLOC DE REPRISE (RESUME) */}
      {hasSavedSession && (
        <div style={{ background: 'linear-gradient(135deg, #fffbeb, #fef3c7)', border: '1px solid #fde68a', borderRadius: '16px', padding: '24px', marginBottom: '30px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', boxShadow: '0 10px 25px -5px rgba(245, 158, 11, 0.15)' }}>
          <div>
            <h3 style={{ margin: '0 0 8px 0', color: '#b45309', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '1.2rem', fontWeight: 900 }}>
              <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="3" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
              Session Interrompue
            </h3>
            <p style={{ margin: 0, color: '#92400e', fontWeight: 600 }}>
              Le téléchargement de la période <strong>{savedPeriod}</strong> s'est arrêté à <strong>{savedOffset.toLocaleString()} / {savedTotal.toLocaleString()}</strong>.
            </p>
          </div>
          <div style={{ display: 'flex', gap: '10px' }}>
            <button onClick={handleCancelResume} style={{ background: '#fef2f2', color: '#ef4444', border: '1px solid #fecaca', padding: '12px 20px', borderRadius: '10px', fontWeight: 800, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px', transition: 'all 0.2s' }}>
              <IconX /> Annuler
            </button>
            <button onClick={handleResume} style={{ background: '#d97706', color: 'white', border: 'none', padding: '12px 24px', borderRadius: '10px', fontWeight: 900, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px', boxShadow: '0 4px 10px rgba(217, 119, 6, 0.3)', transition: 'all 0.2s' }}>
              <IconPlay /> Reprendre
            </button>
          </div>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '30px' }}>
        
        {/* PANNEAU DE SÉLECTION */}
        <div style={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: '16px', padding: '24px', boxShadow: '0 10px 30px -5px rgba(0,0,0,0.05)', opacity: hasSavedSession ? 0.5 : 1, pointerEvents: hasSavedSession ? 'none' : 'auto' }}>
          <h2 style={{ fontSize: '1.2rem', fontWeight: 800, color: '#1e293b', margin: '0 0 20px 0' }}>1. Sélectionner les mois</h2>
          
          <div style={{ display: 'flex', gap: '10px', marginBottom: '20px' }}>
            <select 
              value={currentSelection} 
              onChange={e => setCurrentSelection(e.target.value)}
              style={{ flex: 1, padding: '12px 16px', borderRadius: '10px', border: '2px solid #e2e8f0', outline: 'none', background: '#f8fafc', fontWeight: 700, color: '#0f172a' }}
              disabled={isRunning || hasSavedSession}
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
            <button onClick={addPeriod} disabled={isRunning || hasSavedSession} style={{ padding: '0 24px', background: isRunning || hasSavedSession ? '#cbd5e1' : '#10b981', color: 'white', border: 'none', borderRadius: '10px', fontWeight: 900, cursor: isRunning || hasSavedSession ? 'not-allowed' : 'pointer', transition: 'all 0.2s' }}>
              Ajouter
            </button>
          </div>

          <h3 style={{ fontSize: '0.85rem', fontWeight: 800, color: '#64748b', textTransform: 'uppercase', letterSpacing: '1px', marginBottom: '10px' }}>Liste d'attente ({selectedPeriods.length})</h3>
          
          <div style={{ background: '#f8fafc', border: '2px dashed #e2e8f0', borderRadius: '12px', minHeight: '150px', padding: '15px', display: 'flex', flexWrap: 'wrap', gap: '10px', alignContent: 'flex-start' }}>
            {selectedPeriods.length === 0 && <span style={{ color: '#94a3b8', fontSize: '0.9rem', fontWeight: 600, margin: 'auto' }}>Aucune période sélectionnée...</span>}
            
            {selectedPeriods.map(p => (
              <div key={p} style={{ background: '#e0e7ff', color: '#4338ca', padding: '8px 14px', borderRadius: '8px', fontSize: '0.85rem', fontWeight: 800, display: 'flex', alignItems: 'center', gap: '10px', border: '1px solid #c7d2fe' }}>
                {p}
                {!isRunning && !hasSavedSession && (
                  <button onClick={() => removePeriod(p)} style={{ background: 'none', border: 'none', color: '#ef4444', cursor: 'pointer', padding: 0, display: 'flex' }}>
                    <IconTrash />
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* PANNEAU DE CONTRÔLE */}
        <div style={{ background: 'linear-gradient(135deg, #0f172a, #1e293b)', border: '1px solid #334155', borderRadius: '16px', padding: '30px', color: 'white', boxShadow: '0 20px 40px -10px rgba(0,0,0,0.3)' }}>
          <h2 style={{ fontSize: '1.2rem', fontWeight: 800, color: '#38bdf8', margin: '0 0 20px 0', textTransform: 'uppercase', letterSpacing: '1px' }}>2. Exécution</h2>

          {isRunning ? (
            <div style={{ textAlign: 'center', padding: '20px 0' }}>
              <div style={{ display: 'inline-block', padding: '8px 16px', background: 'rgba(16, 185, 129, 0.1)', border: '1px solid #10b981', color: '#34d399', borderRadius: '99px', fontWeight: 800, fontSize: '0.85rem', marginBottom: '25px', letterSpacing: '1px' }}>
                <span style={{ display: 'inline-block', width: '8px', height: '8px', background: '#10b981', borderRadius: '50%', marginRight: '8px', boxShadow: '0 0 8px #10b981' }}></span>
                MOTEUR ACTIF
              </div>
              <h3 style={{ fontSize: '1rem', color: '#94a3b8', margin: '0 0 10px 0', fontWeight: 600 }}>Période en cours d'aspiration :</h3>
              <div style={{ fontSize: '2.5rem', fontWeight: 900, color: '#fcd34d', letterSpacing: '-1px', textShadow: '0 4px 15px rgba(252, 211, 77, 0.2)' }}>{currentPeriodActive || 'Chargement...'}</div>
              
              <button onClick={handleStop} style={{ marginTop: '40px', width: '100%', padding: '16px', background: 'linear-gradient(135deg, #ef4444, #b91c1c)', color: 'white', border: 'none', borderRadius: '12px', fontWeight: 900, fontSize: '1rem', cursor: 'pointer', boxShadow: '0 10px 20px rgba(239,68,68,0.3)', transition: 'all 0.2s', textTransform: 'uppercase', letterSpacing: '1px' }}>
                Stopper le processus
              </button>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%', justifyContent: 'center' }}>
              <p style={{ color: '#94a3b8', fontSize: '0.95rem', lineHeight: '1.6', marginBottom: '30px', textAlign: 'center', fontWeight: 500 }}>
                {hasSavedSession 
                  ? "Une session est en attente. Veuillez la reprendre ou l'annuler avant de lancer une nouvelle aspiration."
                  : "Le moteur va aspirer les périodes une par une. L'offset sera remis à zéro à chaque nouveau mois pour garantir qu'aucune erreur 500 ne se produise côté Bouygues."}
              </p>
              <button onClick={handleStart} disabled={hasSavedSession || selectedPeriods.length === 0} style={{ width: '100%', padding: '16px', background: hasSavedSession || selectedPeriods.length === 0 ? '#334155' : 'linear-gradient(135deg, #8b5cf6, #6d28d9)', color: hasSavedSession || selectedPeriods.length === 0 ? '#64748b' : 'white', border: 'none', borderRadius: '12px', fontWeight: 900, fontSize: '1rem', cursor: hasSavedSession || selectedPeriods.length === 0 ? 'not-allowed' : 'pointer', boxShadow: hasSavedSession || selectedPeriods.length === 0 ? 'none' : '0 10px 20px rgba(139,92,246,0.3)', transition: 'all 0.2s', textTransform: 'uppercase', letterSpacing: '1px' }}>
                🚀 DÉMARRER L'ASPIRATION
              </button>
            </div>
          )}
        </div>

      </div>
    </div>
  );
}