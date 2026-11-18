'use client';

import { CSSProperties, ReactNode } from 'react';
import fx from './fx.module.css';
import { cx, Num } from './widgets';
import { IClock, IDb, IShield, IRadar } from './icons';
import { HealerInfo, PeriodRow, SyncStats } from '../../services/api';

const ISpark = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M12 2l2.4 7.4H22l-6.2 4.5 2.4 7.4L12 16.8l-6.2 4.5 2.4-7.4L2 9.4h7.6z" />
  </svg>
);

type LinkState = 'live' | 'idle' | 'err';

function Link({ i, state, color, label, speed = 1.8 }: { i: number; state: LinkState; color: string; label: ReactNode; speed?: number }) {
  return (
    <div className={cx(fx.link, state === 'live' && fx.linkLive, state === 'err' && fx.linkErr)}
      style={{ ['--i' as string]: i, ['--c' as string]: color } as CSSProperties}>
      <span className={fx.linkLabel} style={state === 'err' ? { color: '#fda4af', borderColor: 'rgba(244,63,94,.5)' } : undefined}>{label}</span>
      {state === 'live' && [0, 1, 2, 3].map(k => (
        <span key={k} className={fx.particle}
          style={{ ['--dur' as string]: `${speed}s`, ['--delay' as string]: `${(k * speed) / 4}s` } as CSSProperties} />
      ))}
    </div>
  );
}

function Node({ icon, label, value, sub, color, live, err }: {
  icon: ReactNode; label: string; value: ReactNode; sub: ReactNode; color: string; live: boolean; err?: boolean;
}) {
  return (
    <div className={fx.node}>
      <div className={cx(fx.orb, live && fx.orbLive, err && fx.orbErr)} style={{ ['--c' as string]: color } as CSSProperties}>{icon}</div>
      <div className={fx.nodeLabel}>{label}</div>
      <div className={fx.nodeValue}>{value}</div>
      <div className={fx.nodeSub}>{sub}</div>
    </div>
  );
}

export default function Pipeline({ stats, healer, periods: allPeriods }: { stats: SyncStats | null; healer: HealerInfo | null; periods: PeriodRow[] }) {
  const periods = allPeriods.filter(p => p.in_scope !== false);
  const paused = !!stats?.autopilot?.bytel_paused;
  const tmOn = !!stats?.is_running;
  const healOn = !!healer?.running;

  const remote = periods.reduce((a, r) => a + (r.remote_total || 0), 0);
  const rows = periods.reduce((a, r) => a + (r.local_rows || 0), 0);
  const missing = periods.reduce((a, r) => a + (r.missing_details || 0), 0);
  const phantoms = periods.reduce((a, r) => a + (r.phantoms || 0), 0);
  const enriched = Math.max(0, rows - missing - phantoms);
  const enrichedPct = rows > 0 ? Math.round((enriched / rows) * 100) : 0;

  const throttle = stats?.autopilot?.throttle || '';
  const delay = throttle.match(/Délai (\d+)ms/)?.[1];
  const par = throttle.match(/Parallèle (\d+\/\d+)/)?.[1];

  const tmLink: LinkState = paused ? 'err' : tmOn ? 'live' : 'idle';
  const healLink: LinkState = paused ? 'err' : healOn && (healer?.queue || 0) > 0 ? 'live' : 'idle';
  const rate = healer?.rate_per_min || 0;
  const healSpeed = rate > 0 ? Math.max(0.8, Math.min(3.5, 400 / rate)) : 2.5;

  return (
    <div className={fx.pipeScroll}>
      <div className={fx.pipe}>
        <Node icon={<IRadar />} label="Bytel" color={paused ? '#f43f5e' : '#3b82f6'} live={!paused && (tmOn || healOn)} err={paused}
          value={remote > 0 ? <Num value={remote} /> : '—'}
          sub={paused ? 'Protection : pause en cours' : delay ? `1 req / ${delay}ms · ${par} en parallèle` : 'Source officielle'} />
        <Node icon={<IClock />} label="Time Machine" color="#22d3ee" live={tmOn}
          value={tmOn ? stats?.current_period : 'IDLE'}
          sub={tmOn ? <>+{(stats?.period_inserted || 0).toLocaleString('fr-FR')} nouveaux · ↻{(stats?.period_updated || 0).toLocaleString('fr-FR')}</> : 'En attente de la prochaine passe'} />
        <Node icon={<IDb />} label="Base locale" color="#a855f7" live={tmOn || healOn}
          value={<Num value={stats?.total_interventions_local || 0} />}
          sub={`${periods.length} périodes dans le périmètre · ${stats?.autopilot?.completeness ?? 0}% complètes`} />
        <Node icon={<IShield />} label="Healer" color="#10b981" live={healOn && (healer?.queue || 0) > 0}
          value={<><Num value={rate} /><span style={{ fontSize: '.75rem', color: '#66708a' }}>/min</span></>}
          sub={healOn ? `${(healer?.queue || 0).toLocaleString('fr-FR')} en file · ${(healer?.recovered || 0).toLocaleString('fr-FR')} récupérés au 2e essai` : 'Arrêté'} />
        <Node icon={<ISpark />} label="Enrichis" color="#f59e0b" live={rate > 0}
          value={<><Num value={enrichedPct} />%</>}
          sub={`${enriched.toLocaleString('fr-FR')} EPS avec détails · ${(healer?.backoff || 0).toLocaleString('fr-FR')} en ré-essai · ${(healer?.definitive || 0).toLocaleString('fr-FR')} définitifs`} />

        <Link i={0} state={tmLink} color="#3b82f6" label={paused ? 'PAUSE' : tmOn ? '250 EPS / page' : 'idle'} />
        <Link i={1} state={paused ? 'idle' : tmOn ? 'live' : 'idle'} color="#22d3ee" speed={1.4}
          label={tmOn ? `+${(stats?.period_inserted || 0).toLocaleString('fr-FR')}` : '—'} />
        <Link i={2} state={healLink} color="#a855f7" label={`file ${(healer?.queue || 0).toLocaleString('fr-FR')}`} speed={healSpeed} />
        <Link i={3} state={paused ? 'idle' : healOn && rate > 0 ? 'live' : 'idle'} color="#10b981" label={`${rate}/min`} speed={healSpeed} />
      </div>
    </div>
  );
}
