'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { ReactNode } from 'react';
import s from './cc.module.css';
import fx from './fx.module.css';
import { cx, StatusDot } from './widgets';
import { useClock, usePoll } from './hooks';
import { IBolt, IClock, IFlask, ILayers } from './icons';
import { fetchEvents, SyncStats, HealerInfo, SyncEvent } from '../../services/api';

const NAV = [
  { href: '/', label: 'Mission Control', icon: <IBolt /> },
  { href: '/time-machine', label: 'Moteurs', icon: <IClock /> },
  { href: '/interventions', label: 'Explorateur EPS', icon: <ILayers /> },
  { href: '/lab', label: 'API Lab', icon: <IFlask /> },
];

const fetchTicker = () => fetchEvents({ limit: 14 });

export default function Shell({ children, stats, healer }: { children: ReactNode; stats: SyncStats | null; healer: HealerInfo | null }) {
  const path = usePathname();
  const now = useClock();
  const { data: ticker } = usePoll<SyncEvent[]>(fetchTicker, 5000);

  const ap = stats?.autopilot;
  const tmState = stats?.is_running ? 'live' : 'off';
  const healState = healer?.running
    ? (healer.last_success_ago_s > 300 && healer.queue > 0 ? 'warn' : 'live')
    : 'off';
  const bytelState = !ap ? 'off' : ap.bytel_paused ? 'err' : (ap.throttle?.includes('Parallèle 1/') ? 'warn' : 'live');

  return (
    <div className={s.root} data-cc-root="">
      <div className={s.aurora}>
        <div className={cx(s.blob, s.blob1)} />
        <div className={cx(s.blob, s.blob2)} />
        <div className={cx(s.blob, s.blob3)} />
      </div>
      <div className={s.grid} />
      <div className={fx.grain} />
      <div className={fx.vignette} />

      <div className={s.container}>
        <header className={s.header}>
          <div className={s.brand}>
            <div className={s.logo}><IBolt s={18} /></div>
            <div>
              <div className={cx(s.brandTitle, fx.glitch)} data-text="GRINGOTTS">GRINGOTTS</div>
              <div className={s.brandSub}>Mission Control</div>
            </div>
          </div>

          <nav className={s.nav}>
            {NAV.map(n => (
              <Link key={n.href} href={n.href} className={cx(s.navLink, path === n.href && s.navActive)}>
                {n.icon}<span>{n.label}</span>
              </Link>
            ))}
          </nav>

          <div className={s.pills}>
            <span className={s.pill} title={ap?.status}>
              <StatusDot state={ap?.enabled ? 'live' : 'off'} />Auto {ap?.enabled ? `${ap.completeness ?? 0}%` : 'OFF'}
            </span>
            <span className={s.pill} title={stats?.time_machine_status}>
              <StatusDot state={tmState} />TM {stats?.is_running ? stats?.current_period : 'idle'}
            </span>
            <span className={s.pill} title={healer?.status}>
              <StatusDot state={healState} />Healer {healer?.running ? `${healer.rate_per_min}/min` : 'OFF'}
            </span>
            <span className={s.pill} title={ap?.throttle}>
              <StatusDot state={bytelState} />Bytel {!ap ? '—' : ap.bytel_paused ? 'PAUSE' : 'OK'}
            </span>
            <span className={s.clock}>{now ? now.toLocaleTimeString('fr-FR') : '--:--:--'}</span>
          </div>
        </header>

        <div className={s.ticker}>
          <div className={s.tickerTrack}>
            {(ticker || []).map(e => (
              <span key={e.id} className={s.tickItem}>
                <span className={s.tickTime}>{e.createdAt?.slice(11, 19)}</span>
                <StatusDot state={e.level === 'ERROR' ? 'err' : e.level === 'WARN' ? 'warn' : e.level === 'SUCCESS' ? 'live' : 'off'} />
                <span>{e.period ? `[${e.period}] ` : ''}{e.message}</span>
              </span>
            ))}
            {(!ticker || ticker.length === 0) && <span className={s.tickItem}>En attente des premiers événements…</span>}
          </div>
        </div>

        {children}
      </div>
    </div>
  );
}
