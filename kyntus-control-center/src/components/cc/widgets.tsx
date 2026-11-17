'use client';

import { ReactNode } from 'react';
import s from './cc.module.css';
import { useAnimatedNumber } from './hooks';

export type Accent = 'cyan' | 'violet' | 'emerald' | 'amber' | 'rose';

export const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ');

const ACC: Record<Accent, string> = {
  cyan: s.accCyan, violet: s.accViolet, emerald: s.accEmerald, amber: s.accAmber, rose: s.accRose,
};

export function Num({ value, decimals = 0 }: { value: number; decimals?: number }) {
  const v = useAnimatedNumber(value || 0);
  return <>{v.toLocaleString('fr-FR', { maximumFractionDigits: decimals, minimumFractionDigits: decimals })}</>;
}

export function Card({ children, accent = 'cyan', glow = false, className, style }: {
  children: ReactNode; accent?: Accent; glow?: boolean; className?: string; style?: React.CSSProperties;
}) {
  return <section className={cx(s.card, ACC[accent], glow && s.cardGlow, className)} style={style}>{children}</section>;
}

export function CardHead({ icon, title, sub, right }: { icon: ReactNode; title: ReactNode; sub?: ReactNode; right?: ReactNode }) {
  return (
    <div className={s.cardHead}>
      <div className={s.cardTitle}>
        <div className={s.cardIcon}>{icon}</div>
        <div>
          <div>{title}</div>
          {sub && <div className={s.cardSub}>{sub}</div>}
        </div>
      </div>
      {right}
    </div>
  );
}

export function Kpi({ label, value, sub, icon, accent = 'cyan', delay = 0, decimals = 0, suffix }: {
  label: string; value: number; sub?: ReactNode; icon?: ReactNode; accent?: Accent; delay?: number; decimals?: number; suffix?: string;
}) {
  return (
    <div className={cx(s.kpi, ACC[accent])} style={{ animationDelay: `${delay}ms` }}>
      <div className={s.kpiLabel}><span>{label}</span><span style={{ color: 'var(--accent)' }}>{icon}</span></div>
      <div className={s.kpiValue}>
        <Num value={value} decimals={decimals} />
        {suffix && <span style={{ fontSize: '0.9rem', color: 'var(--txt-3)', marginLeft: 4 }}>{suffix}</span>}
      </div>
      {sub && <div className={s.kpiSub}>{sub}</div>}
    </div>
  );
}

export function Progress({ value, healer = false }: { value: number; healer?: boolean }) {
  return (
    <div className={s.progress}>
      <div className={cx(s.progressFill, healer && s.fillHealer)} style={{ width: `${Math.max(0, Math.min(100, value))}%` }} />
    </div>
  );
}

/** Anneau SVG animé. */
export function Ring({ value, size = 160, stroke = 10 }: { value: number; size?: number; stroke?: number }) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const v = Math.max(0, Math.min(100, value));
  return (
    <svg width="100%" height="100%" viewBox={`0 0 ${size} ${size}`} style={{ transform: 'rotate(-90deg)' }}>
      <defs>
        <linearGradient id="ringGrad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#a855f7" />
          <stop offset="100%" stopColor="#22d3ee" />
        </linearGradient>
      </defs>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth={stroke} />
      <circle
        className={s.donutArc}
        cx={size / 2} cy={size / 2} r={r} fill="none" stroke="url(#ringGrad)" strokeWidth={stroke} strokeLinecap="round"
        strokeDasharray={`${(v / 100) * c} ${c}`}
        style={{ filter: 'drop-shadow(0 0 8px rgba(34,211,238,0.6))' }}
      />
    </svg>
  );
}

/** Donut multi-segments. */
export function Donut({ parts, size = 150, stroke = 16, center }: {
  parts: { value: number; color: string; label: string }[]; size?: number; stroke?: number; center?: ReactNode;
}) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const total = parts.reduce((a, p) => a + p.value, 0);
  let acc = 0;
  return (
    <div style={{ position: 'relative', width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ transform: 'rotate(-90deg)' }}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth={stroke} />
        {total > 0 && parts.map((p, i) => {
          const len = (p.value / total) * c;
          const el = (
            <circle key={i} className={s.donutArc} cx={size / 2} cy={size / 2} r={r} fill="none"
              stroke={p.color} strokeWidth={stroke}
              strokeDasharray={`${Math.max(0, len - 2)} ${c}`} strokeDashoffset={-acc}
              style={{ filter: `drop-shadow(0 0 6px ${p.color})` }} />
          );
          acc += len;
          return el;
        })}
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', textAlign: 'center' }}>{center}</div>
    </div>
  );
}

export function LevelBadge({ level }: { level: string }) {
  const map: Record<string, string> = { INFO: s.bInfo, SUCCESS: s.bSuccess, WARN: s.bWarn, ERROR: s.bError };
  const label: Record<string, string> = { INFO: 'Info', SUCCESS: 'OK', WARN: 'Alerte', ERROR: 'Erreur' };
  return <span className={cx(s.badge, map[level] || s.bInfo)}>{label[level] || level}</span>;
}

export const SOURCES: Record<string, [string, string]> = {
  TIME_MACHINE: [s.bTm, 'Time Machine'],
  HEALER: [s.bHealer, 'Healer'],
  AUTOPILOT: [s.bAuto, 'AutoPilot'],
  BYTEL: [s.bBytel, 'Bytel'],
  MAINTENANCE: [s.bMaint, 'Maintenance'],
  SYSTEM: [s.bSys, 'Système'],
};

export function SourceBadge({ source }: { source: string }) {
  const [cls, label] = SOURCES[source] || [s.bSys, source];
  return <span className={cx(s.badge, cls)}>{label}</span>;
}

export function Toast({ msg }: { msg: string | null }) {
  if (!msg) return null;
  return <div className={s.toast}>{msg}</div>;
}

export function StatusDot({ state }: { state: 'live' | 'warn' | 'err' | 'off' }) {
  return <span className={cx(s.dot, state === 'live' && s.dotLive, state === 'warn' && s.dotWarn, state === 'err' && s.dotErr)} />;
}

export const fmtDur = (sec: number) => {
  if (sec < 0) return '—';
  if (sec < 60) return `${sec}s`;
  const m = Math.floor(sec / 60);
  if (m < 60) return `${m}m ${sec % 60}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
};
