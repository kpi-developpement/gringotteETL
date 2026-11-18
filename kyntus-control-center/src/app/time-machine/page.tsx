'use client';

import { useEffect, useState } from 'react';
import s from '../../components/cc/cc.module.css';
import fx from '../../components/cc/fx.module.css';
import Shell from '../../components/cc/Shell';
import EventsTable from '../../components/cc/EventsTable';
import Pipeline from '../../components/cc/Pipeline';
import { HealerPanel, TimeMachinePanel, useTmThroughput } from '../../components/cc/Engines';
import { PeriodMatrix } from '../../components/cc/Periods';
import { EpsInspector, PhaseBoard, ScopeCard } from '../../components/cc/Phases';
import { useLive, useToast } from '../../components/cc/hooks';
import { Card, CardHead, cx, Num, Progress, Segmented, Sparkline, Toast } from '../../components/cc/widgets';
import { IBolt, IClose, IDb, IGrid, IPlay, IPlus, IStop } from '../../components/cc/icons';
import { setAutopilot, startHealer, startPeriodSync, stopHealer, stopSync } from '../../services/api';

type Mode = 'merged' | 'split';

export default function EnginesPage() {
  const { stats, healer, periods, refreshAll } = useLive();
  const { toast, show } = useToast();
  const [mode, setMode] = useState<Mode>('merged');
  const [selected, setSelected] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [pick, setPick] = useState('');
  const [healerMode, setHealerMode] = useState('RADAR');

  useEffect(() => {
    try { const m = localStorage.getItem('engines-mode'); if (m === 'merged' || m === 'split') setMode(m); } catch { /* */ }
  }, []);
  const changeMode = (m: Mode) => { setMode(m); try { localStorage.setItem('engines-mode', m); } catch { /* */ } };

  const isRunning = !!stats?.is_running;
  const ap = stats?.autopilot;
  const periodNames = periods.map(p => p.period);
  const tmHist = useTmThroughput(stats);

  const onStopTm = async () => { await stopSync(); show('Time Machine stoppée (AutoPilot désactivé).'); refreshAll(); };
  const onRelaunch = async () => { await setAutopilot(true); show('AutoPilot réactivé.'); refreshAll(); };
  const onStartHealer = async () => { await startHealer(healerMode); show('Healer démarré.'); refreshAll(); };
  const onStopHealer = async () => { await stopHealer(); show('Healer stoppé (AutoPilot désactivé).'); refreshAll(); };

  const startManual = async () => {
    if (picked.length === 0) return show('Ajoutez au moins une période.');
    if (ap?.enabled && !window.confirm('L’AutoPilot est actif : il reprendra la boucle après votre lancement manuel. Continuer ?')) return;
    await startPeriodSync(picked.join(','));
    show(`Scan manuel lancé : ${picked.join(', ')}`);
    setPicked([]);
    refreshAll();
  };

  const tmProgress = stats && stats.period_total > 0 ? Math.min(100, Math.round((stats.period_offset / stats.period_total) * 100)) : 0;
  const lots = (healer?.recent_lots || []).slice(0, 30).reverse();
  const selRow = periods.find(p => p.period === selected);

  return (
    <Shell stats={stats} healer={healer}>
      <div className={fx.modeBar}>
        <div>
          <div className={fx.modeTitle}>Salle des moteurs</div>
          <div className={fx.modeSub}>Time Machine et Healer : ensemble dans un seul flux, ou séparés côte à côte</div>
        </div>
        <Segmented<Mode> value={mode} onChange={changeMode} options={[
          { value: 'merged', label: <>⚡ Fusionné</> },
          { value: 'split', label: <>◫ Séparé</> },
        ]} />
      </div>

      <div key={mode} className={fx.fadeSwap} style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
        {mode === 'merged' ? (
          <>
            <Card accent="violet" glow={isRunning || !!healer?.running}>
              <CardHead
                icon={<IBolt />}
                title="Time Machine × Healer"
                sub={`${stats?.time_machine_status || '—'}  ·  ${healer?.status || '—'}`}
                right={
                  <div className={s.row}>
                    {isRunning
                      ? <button className={cx(s.btn, s.accRose)} onClick={onStopTm}><IStop /> TM</button>
                      : !ap?.enabled && <button className={cx(s.btn, s.btnSolid, s.accViolet)} onClick={onRelaunch}><IPlay /> AutoPilot</button>}
                    {healer?.running
                      ? <button className={cx(s.btn, s.accRose)} onClick={onStopHealer}><IStop /> Healer</button>
                      : <button className={cx(s.btn, s.btnSolid, s.accEmerald)} onClick={onStartHealer}><IPlay /> Healer</button>}
                  </div>
                }
              />
              <Pipeline stats={stats} healer={healer} periods={periods} />

              <div className={s.split} style={{ marginTop: 18 }}>
                <div className={cx(s.scanline, !isRunning && s.scanIdle)}>
                  <div className={s.row} style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
                    <div>
                      <div className={s.tileLabel}>Time Machine</div>
                      <div className={s.bigPeriod} style={{ fontSize: '1.9rem' }}>{isRunning ? stats?.current_period : 'STANDBY'}</div>
                    </div>
                    <div style={{ textAlign: 'right' }} className={s.mono}>
                      <div style={{ color: '#6ee7b7', fontWeight: 800 }}>+<Num value={stats?.period_inserted || 0} /></div>
                      <div style={{ color: '#93c5fd', fontWeight: 800 }}>↻<Num value={stats?.period_updated || 0} /></div>
                    </div>
                  </div>
                  <div style={{ marginTop: 12 }}><Progress value={isRunning ? tmProgress : 0} /></div>
                  <div className={s.progressMeta}>
                    <span>{(stats?.period_offset || 0).toLocaleString('fr-FR')} / {(stats?.period_total || 0).toLocaleString('fr-FR')}</span>
                    <span>ETA {isRunning ? stats?.eta : '—'}</span>
                  </div>
                  <Sparkline data={tmHist} color="#22d3ee" height={44} />
                </div>

                <div className={cx(s.scanline, !healer?.running && s.scanIdle)} style={{ borderColor: 'rgba(16,185,129,.22)', background: 'linear-gradient(135deg, rgba(16,185,129,.08), rgba(34,211,238,.04))' }}>
                  <div className={s.row} style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
                    <div>
                      <div className={s.tileLabel}>Healer</div>
                      <div className={s.bigPeriod} style={{ fontSize: '1.9rem', textShadow: '0 0 30px rgba(16,185,129,.45)' }}>
                        <Num value={healer?.rate_per_min || 0} /><span style={{ fontSize: '.9rem', color: 'var(--txt-3)' }}> /min</span>
                      </div>
                    </div>
                    <div style={{ textAlign: 'right' }} className={s.mono}>
                      <div style={{ color: '#6ee7b7', fontWeight: 800 }}>✓ <Num value={healer?.enriched || 0} /></div>
                      <div style={{ color: '#fcd34d', fontWeight: 800 }}>∅ <Num value={healer?.not_found || 0} /></div>
                    </div>
                  </div>
                  <div style={{ marginTop: 12 }}>
                    <Progress healer value={healer && healer.queue + healer.enriched > 0 ? (healer.enriched / (healer.queue + healer.enriched)) * 100 : 100} />
                  </div>
                  <div className={s.progressMeta}>
                    <span>file {(healer?.queue || 0).toLocaleString('fr-FR')}</span>
                    <span>sauvés 2e essai {(healer?.recovered || 0).toLocaleString('fr-FR')}</span>
                  </div>
                  <Sparkline data={lots.map(l => l.ok)} color="#10b981" height={44} />
                </div>
              </div>
            </Card>

            <EventsTable title="Journal unifié — Time Machine + Healer" sources={['TIME_MACHINE', 'HEALER', 'BYTEL']}
              periods={periodNames} period={selected} onPeriodClear={() => setSelected('')} defaultView="terminal" storageKey="merged" />
          </>
        ) : (
          <>
            <div className={s.engines}>
              <TimeMachinePanel stats={stats} autopilotOn={!!ap?.enabled} onStop={onStopTm} onRelaunch={onRelaunch} />
              <HealerPanel healer={healer} mode={healerMode} setMode={setHealerMode} onStart={onStartHealer} onStop={onStopHealer} />
            </div>
            <div className={fx.dual}>
              <EventsTable title="Journal Time Machine" sources={['TIME_MACHINE']} accent="cyan" compact
                periods={periodNames} period={selected} onPeriodClear={() => setSelected('')} storageKey="split-tm" />
              <EventsTable title="Journal Healer" sources={['HEALER']} accent="emerald" compact storageKey="split-healer" />
            </div>
          </>
        )}
      </div>

      {/* ================= PÉRIODES ================= */}
      <div className={s.sectionTitle}>Périodes</div>
      <div className={s.split}>
        <Card accent="emerald">
          <CardHead icon={<IGrid />} title="Matrice des périodes" sub="Cliquez une case pour filtrer les journaux" />
          <PeriodMatrix rows={periods} selected={selected} onSelect={p => setSelected(p === selected ? '' : p)} />
        </Card>

        <Card accent="violet">
          <CardHead icon={<IPlay />} title="Scan manuel" sub="Forcer un passage immédiat sur des périodes précises" />
          <div className={s.row}>
            <select className={cx(s.select, s.grow)} value={pick || periodNames[0] || ''} onChange={e => setPick(e.target.value)} disabled={isRunning}>
              {periodNames.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
            <button className={s.btn} disabled={isRunning} onClick={() => {
              const p = pick || periodNames[0];
              if (p && !picked.includes(p)) setPicked([...picked, p]);
            }}><IPlus /> Ajouter</button>
          </div>
          <div className={s.chips} style={{ margin: '14px 0', minHeight: 32 }}>
            {picked.length === 0 && <span style={{ color: 'var(--txt-3)', fontSize: '0.78rem' }}>Aucune période sélectionnée</span>}
            {picked.map(p => (
              <span key={p} className={cx(s.chip, s.chipActive)} style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                {p}
                <button onClick={() => setPicked(picked.filter(x => x !== p))} style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', display: 'flex' }}><IClose /></button>
              </span>
            ))}
          </div>
          <button className={cx(s.btn, s.btnSolid, s.accViolet, s.btnBlock)} disabled={isRunning || picked.length === 0} onClick={startManual}>
            <IPlay /> Lancer le scan
          </button>
          <p style={{ color: 'var(--txt-3)', fontSize: '0.74rem', marginTop: 12, lineHeight: 1.5 }}>
            {isRunning
              ? 'Un scan est déjà en cours. Stoppez-le pour lancer un scan manuel.'
              : 'Chaque période reprend à son offset sauvegardé. L’AutoPilot reprend sa boucle infinie dès que la file est vide.'}
          </p>

          {selRow && (
            <div className={s.callout} style={{ marginTop: 14, borderColor: 'rgba(34,211,238,0.3)', background: 'rgba(34,211,238,0.06)', color: 'var(--txt)' }}>
              <div>
                <b className={s.mono}>{selRow.period}</b> — base {selRow.local_distinct.toLocaleString('fr-FR')} / Bytel {selRow.remote_total ? selRow.remote_total.toLocaleString('fr-FR') : '?'}
                {selRow.state === 'ACTIVE'
                  ? <span style={{ color: '#67e8f9' }}> · scan en cours ({selRow.progress}%)</span>
                  : selRow.gap > 0 && <span style={{ color: '#fcd34d' }}> · {selRow.gap.toLocaleString('fr-FR')} manquants</span>}
                <br />Scans : {selRow.scans} · dernier {selRow.last_scan || 'jamais'} · {selRow.missing_details.toLocaleString('fr-FR')} sans détails · {selRow.phantoms.toLocaleString('fr-FR')} fantômes
              </div>
            </div>
          )}
        </Card>
      </div>

      <Card accent="cyan">
        <CardHead icon={<IDb />} title="Phases par période"
          sub="① Collecte (chaque page contrôlée en base) → ② Enrichissement (ré-essais espacés, puis définitif) → ③ Vérification (total Bytel vs base + orphelins)" />
        <PhaseBoard rows={periods} selected={selected} onSelect={p => setSelected(p === selected ? '' : p)} notify={show} refresh={refreshAll} />
      </Card>

      <div className={s.split}>
        <EpsInspector notify={show} />
        <ScopeCard ap={ap} notify={show} refresh={refreshAll} />
      </div>

      <Toast msg={toast} />
    </Shell>
  );
}
