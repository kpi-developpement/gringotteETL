'use client';

import s from './cc.module.css';
import { cx } from './widgets';
import { PeriodRow } from '../../services/api';

const MONTHS = ['JAN', 'FÉV', 'MAR', 'AVR', 'MAI', 'JUN', 'JUL', 'AOÛ', 'SEP', 'OCT', 'NOV', 'DÉC'];

const coverage = (r: PeriodRow) => {
  if (r.state === 'ACTIVE') return r.progress;
  if (r.remote_total > 0) return Math.min(100, Math.round((r.local_distinct / r.remote_total) * 100));
  return r.local_distinct > 0 ? 100 : 0;
};

const STATE_LABEL: Record<string, string> = { ACTIVE: 'En scan', QUEUED: 'En file', DONE: 'À jour', PENDING: 'À faire' };

export function PeriodMatrix({ rows, selected, onSelect }: { rows: PeriodRow[]; selected?: string; onSelect?: (p: string) => void }) {
  const byPeriod = new Map(rows.map(r => [r.period, r]));
  const now = new Date();
  const curY = now.getFullYear();
  const curM = now.getMonth() + 1;
  const years = Array.from(new Set(rows.map(r => parseInt(r.period.slice(0, 4), 10)).filter(y => !isNaN(y)).concat(curY))).sort((a, b) => b - a);

  return (
    <div>
      <div className={s.matrix}>
        <span />
        {MONTHS.map(m => <span key={m} className={s.matrixHead}>{m}</span>)}
        {years.map((y, yi) => (
          <Row key={y} y={y} yi={yi} curY={curY} curM={curM} byPeriod={byPeriod} selected={selected} onSelect={onSelect} />
        ))}
      </div>
      <div className={s.coreLegend} style={{ justifyContent: 'flex-start', marginTop: 16 }}>
        <Legend color="var(--cyan)" label="En scan" />
        <Legend color="var(--violet)" label="En file" />
        <Legend color="var(--emerald)" label="Complet (passe actuelle)" />
        <Legend color="var(--amber)" label="EPS manquants" />
        <Legend color="#334155" label="À faire" />
        <span className={s.legendItem} style={{ color: 'var(--txt-3)' }}>Hauteur = couverture base / Bytel</span>
      </div>
    </div>
  );
}

function Row({ y, yi, curY, curM, byPeriod, selected, onSelect }: {
  y: number; yi: number; curY: number; curM: number; byPeriod: Map<string, PeriodRow>; selected?: string; onSelect?: (p: string) => void;
}) {
  return (
    <>
      <span className={s.matrixYear}>{y}</span>
      {MONTHS.map((_, i) => {
        const p = `${y}_M${String(i + 1).padStart(2, '0')}`;
        const r = byPeriod.get(p);
        const future = y > curY || (y === curY && i + 1 > curM);
        if (!r || future) {
          return <div key={p} className={cx(s.cell, s.cellFuture)} style={{ animationDelay: `${(yi * 12 + i) * 18}ms` }}><span className={s.cellLabel}>{String(i + 1).padStart(2, '0')}</span></div>;
        }
        const cls = !r.in_scope ? s.cellFuture
          : r.state === 'ACTIVE' ? s.cellActive
          : r.state === 'QUEUED' ? s.cellQueued
          : r.gap > 0 ? s.cellGap
          : r.state === 'DONE' ? s.cellDone : s.cellPending;
        const cov = coverage(r);
        const tip = [
          `${p} — ${!r.in_scope ? 'Hors périmètre' : r.overall === 'COMPLETE' ? 'Complet ✓' : STATE_LABEL[r.state]}`,
          r.in_scope ? `Collecte: ${r.collect?.status} · Enrichissement: ${r.enrich?.status} (${r.enrich?.pct}%) · Vérification: ${r.verify?.status}` : '',
          `Base: ${r.local_distinct.toLocaleString('fr-FR')} EPS / Bytel: ${r.remote_total ? r.remote_total.toLocaleString('fr-FR') : '?'}`,
          r.gap > 0 ? `⚠ ${r.gap.toLocaleString('fr-FR')} EPS manquants` : '',
          `Sans détails: ${r.missing_details.toLocaleString('fr-FR')} · Fantômes: ${r.phantoms.toLocaleString('fr-FR')}`,
          `Scans: ${r.scans} · Dernier: ${r.last_scan || 'jamais'}`,
        ].filter(Boolean).join('\n');
        return (
          <div key={p} title={tip} onClick={() => onSelect?.(p)}
            className={cx(s.cell, cls, selected === p && s.cellSelected)}
            style={{ animationDelay: `${(yi * 12 + i) * 18}ms` }}>
            <div className={s.cellFill} style={{ height: `${Math.max(6, cov)}%` }} />
            <span className={s.cellLabel}>{r.state === 'ACTIVE' ? `${r.progress}%` : `${cov}%`}</span>
          </div>
        );
      })}
    </>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return <span className={s.legendItem}><span className={s.legendSwatch} style={{ background: color, boxShadow: `0 0 8px ${color}` }} />{label}</span>;
}

export function PeriodsTable({ rows, selected, onSelect }: { rows: PeriodRow[]; selected?: string; onSelect?: (p: string) => void }) {
  const fmt = (n: number) => n.toLocaleString('fr-FR');
  return (
    <div className={s.tableWrap} style={{ maxHeight: 620 }}>
      <table className={s.table}>
        <thead>
          <tr>
            <th>Période</th>
            <th>État</th>
            <th>Scan (offset / total)</th>
            <th style={{ textAlign: 'right' }}>Bytel</th>
            <th style={{ textAlign: 'right' }}>Base</th>
            <th style={{ textAlign: 'right' }}>Manquants</th>
            <th style={{ textAlign: 'right' }}>Sans détails</th>
            <th style={{ textAlign: 'right' }}>Fantômes</th>
            <th style={{ textAlign: 'right' }}>Scans</th>
            <th>Dernier scan</th>
            <th>Dernier résultat</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => {
            const badge = r.state === 'ACTIVE' ? s.bTm : r.state === 'QUEUED' ? s.bAuto : r.state === 'DONE' ? s.bSuccess : s.bSys;
            return (
              <tr key={r.period} onClick={() => onSelect?.(r.period)} style={{ cursor: 'pointer' }}
                className={cx(r.state === 'ACTIVE' && s.rowActive, selected === r.period && s.rowActive)}>
                <td className={cx(s.mono, s.strong)}>{r.period}</td>
                <td><span className={cx(s.badge, badge)}>{STATE_LABEL[r.state]}</span></td>
                <td>
                  <div className={s.row} style={{ flexWrap: 'nowrap' }}>
                    <div className={s.miniBar} style={{ flex: 1 }}><div className={s.miniFill} style={{ width: `${r.progress}%` }} /></div>
                    <span className={s.mono} style={{ fontSize: '0.7rem', whiteSpace: 'nowrap' }}>{fmt(r.offset)} / {r.remote_total ? fmt(r.remote_total) : '?'}</span>
                  </div>
                </td>
                <td className={s.num}>{r.remote_total ? fmt(r.remote_total) : '—'}</td>
                <td className={cx(s.num, s.strong)}>{fmt(r.local_distinct)}</td>
                <td className={s.num} style={{ color: r.state === 'ACTIVE' ? '#67e8f9' : r.gap > 0 ? '#fcd34d' : 'var(--txt-3)', fontWeight: r.gap > 0 ? 800 : 500 }}
                  title={r.state === 'ACTIVE' ? 'Calculé à la fin du scan' : r.gap_source === 'last_scan' ? 'Constat du dernier scan complet' : r.gap_source === 'live' ? 'Base vs Bytel après scan complet' : 'Pas encore de scan complet'}>
                  {r.state === 'ACTIVE' ? 'en scan…' : r.gap > 0 ? `−${fmt(r.gap)}${r.gap_source === 'last_scan' ? '*' : ''}` : r.gap_source === 'unknown' ? '—' : '0'}
                </td>
                <td className={s.num} style={{ color: r.missing_details > 0 ? '#93c5fd' : 'var(--txt-3)' }}>{fmt(r.missing_details)}</td>
                <td className={s.num} style={{ color: r.phantoms > 0 ? '#fda4af' : 'var(--txt-3)' }}>{fmt(r.phantoms)}</td>
                <td className={s.num}>{r.scans}</td>
                <td className={s.mono} style={{ whiteSpace: 'nowrap' }}>{r.last_scan || '—'}</td>
                <td style={{ whiteSpace: 'nowrap' }}>
                  {r.last_result
                    ? r.last_result.empty
                      ? <span style={{ color: 'var(--txt-3)' }}>Vide chez Bytel</span>
                      : <span className={s.mono} style={{ fontSize: '0.72rem' }}>
                          <span style={{ color: '#6ee7b7' }}>+{fmt(r.last_result.inserted)}</span>{' '}
                          <span style={{ color: '#93c5fd' }}>↻{fmt(r.last_result.updated)}</span>{' '}
                          <span style={{ color: 'var(--txt-3)' }}>· {r.last_result.duration}</span>
                        </span>
                    : <span style={{ color: 'var(--txt-3)' }}>—</span>}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {rows.length === 0 && <div className={s.empty}>Chargement des périodes…</div>}
    </div>
  );
}
