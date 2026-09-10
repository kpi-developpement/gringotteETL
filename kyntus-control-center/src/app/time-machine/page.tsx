'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { fetchStats, startPeriodSync, stopSync, fetchPeriodInfo } from '../../services/api';

const IconClock = () => <svg width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>;
const IconTrash = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>;
const IconPlay = () => <svg width="18" height="18" fill="currentColor" viewBox="0 0 20 20"><path d="M4 4l12 6-12 6V4z"/></svg>;
const IconStop = () => <svg width="20" height="20" fill="currentColor" viewBox="0 0 20 20"><path d="M5 5h10v10H5z"/></svg>;

export default function TimeMachinePage() {
  const [selectedPeriods, setSelectedPeriods] = useState<string[]>([]);
  const [currentSelection, setCurrentSelection] = useState('2026_M01');
  
  const [isRunning, setIsRunning] = useState(false);
  const [isActionPending, setIsActionPending] = useState(false); 
  const [currentPeriodActive, setCurrentPeriodActive] = useState<string | null>(null);
  
  // 🛡️ L'FIX HNA : State pour stocker l'info du mois sélectionné
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

  // 🛡️ L'FIX HNA : Dès qu'on change le mois dans le menu déroulant, on scanne la DB
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

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '30px' }}>
        
        {/* PANNEAU DE SÉLECTION */}
        <div style={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: '16px', padding: '24px', boxShadow: '0 10px 30px -5px rgba(0,0,0,0.05)' }}>
          <h2 style={{ fontSize: '1.2rem', fontWeight: 800, color: '#1e293b', margin: '0 0 20px 0' }}>1. Sélectionner les mois</h2>
          
          <div style={{ display: 'flex', gap: '10px', marginBottom: '15px' }}>
            <select 
              value={currentSelection} 
              onChange={e => setCurrentSelection(e.target.value)}
              style={{ flex: 1, padding: '12px 16px', borderRadius: '10px', border: '2px solid #e2e8f0', outline: 'none', background: '#f8fafc', fontWeight: 700, color: '#0f172a' }}
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
            <button onClick={addPeriod} disabled={isRunning} style={{ padding: '0 24px', background: isRunning ? '#cbd5e1' : '#10b981', color: 'white', border: 'none', borderRadius: '10px', fontWeight: 900, cursor: isRunning ? 'not-allowed' : 'pointer', transition: 'all 0.2s' }}>
              Ajouter
            </button>
          </div>

          {/* 🛡️ L'FIX HNA : Le Scanner en temps réel du mois sélectionné */}
          {periodInfo && (
            <div style={{ marginBottom: '25px', padding: '12px', borderRadius: '10px', display: 'flex', alignItems: 'center', gap: '10px', fontSize: '0.9rem', fontWeight: 'bold',
              background: (periodInfo.total > 0 && periodInfo.offset >= periodInfo.total) ? '#ecfdf5' : (periodInfo.offset > 0 ? '#fffbeb' : '#eff6ff'),
              color: (periodInfo.total > 0 && periodInfo.offset >= periodInfo.total) ? '#059669' : (periodInfo.offset > 0 ? '#d97706' : '#2563eb'),
              border: `1px solid ${(periodInfo.total > 0 && periodInfo.offset >= periodInfo.total) ? '#a7f3d0' : (periodInfo.offset > 0 ? '#fde68a' : '#bfdbfe')}`
            }}>
              {(periodInfo.total > 0 && periodInfo.offset >= periodInfo.total) ? '✅ Terminé :' : (periodInfo.offset > 0 ? '⚠️ À reprendre :' : '🆕 Nouveau :')}
              <span style={{ color: '#0f172a' }}>{periodInfo.offset.toLocaleString()} / {periodInfo.total > 0 ? periodInfo.total.toLocaleString() : '?'} EPS</span>
            </div>
          )}

          <h3 style={{ fontSize: '0.85rem', fontWeight: 800, color: '#64748b', textTransform: 'uppercase', letterSpacing: '1px', marginBottom: '10px' }}>Liste d'attente ({selectedPeriods.length})</h3>
          
          <div style={{ background: '#f8fafc', border: '2px dashed #e2e8f0', borderRadius: '12px', minHeight: '150px', padding: '15px', display: 'flex', flexWrap: 'wrap', gap: '10px', alignContent: 'flex-start' }}>
            {selectedPeriods.length === 0 && <span style={{ color: '#94a3b8', fontSize: '0.9rem', fontWeight: 600, margin: 'auto' }}>Aucune période sélectionnée...</span>}
            
            {selectedPeriods.map(p => (
              <div key={p} style={{ background: '#e0e7ff', color: '#4338ca', padding: '8px 14px', borderRadius: '8px', fontSize: '0.85rem', fontWeight: 800, display: 'flex', alignItems: 'center', gap: '10px', border: '1px solid #c7d2fe' }}>
                {p}
                {!isRunning && (
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
              
              <button onClick={handleStop} disabled={isActionPending} style={{ marginTop: '40px', width: '100%', padding: '16px', background: 'linear-gradient(135deg, #ef4444, #b91c1c)', color: 'white', border: 'none', borderRadius: '12px', fontWeight: 900, fontSize: '1rem', cursor: isActionPending ? 'not-allowed' : 'pointer', boxShadow: '0 10px 20px rgba(239,68,68,0.3)', transition: 'all 0.2s', textTransform: 'uppercase', letterSpacing: '1px', opacity: isActionPending ? 0.5 : 1 }}>
                <IconStop /> Stopper le processus
              </button>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%', justifyContent: 'center' }}>
              <p style={{ color: '#94a3b8', fontSize: '0.95rem', lineHeight: '1.6', marginBottom: '30px', textAlign: 'center', fontWeight: 500 }}>
                Le moteur va aspirer les périodes une par une. L'offset sera sauvegardé automatiquement pour chaque mois. Si vous relancez un mois déjà entamé, il reprendra exactement là où il s'est arrêté.
              </p>
              <button onClick={handleStart} disabled={isActionPending || selectedPeriods.length === 0} style={{ width: '100%', padding: '16px', background: selectedPeriods.length === 0 ? '#334155' : 'linear-gradient(135deg, #8b5cf6, #6d28d9)', color: selectedPeriods.length === 0 ? '#64748b' : 'white', border: 'none', borderRadius: '12px', fontWeight: 900, fontSize: '1rem', cursor: selectedPeriods.length === 0 ? 'not-allowed' : 'pointer', boxShadow: selectedPeriods.length === 0 ? 'none' : '0 10px 20px rgba(139,92,246,0.3)', transition: 'all 0.2s', textTransform: 'uppercase', letterSpacing: '1px' }}>
                <IconPlay /> DÉMARRER L'ASPIRATION
              </button>
            </div>
          )}
        </div>

      </div>
    </div>
  );
}