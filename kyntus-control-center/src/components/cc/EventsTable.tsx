'use client';

import { useEffect, useRef, useState } from 'react';
import s from './cc.module.css';
import { Card, CardHead, cx, LevelBadge, SourceBadge, SOURCES, StatusDot } from './widgets';
import { ITerminal, ISearch } from './icons';
import { fetchEvents, SyncEvent } from '../../services/api';

const LEVELS = ['ALL', 'ERROR', 'WARN', 'SUCCESS', 'INFO'];
const LEVEL_LABEL: Record<string, string> = { ALL: 'Tous', ERROR: 'Erreurs', WARN: 'Alertes', SUCCESS: 'Succès', INFO: 'Infos' };

export default function EventsTable({ title = 'Journal des opérations', fixedSource, periods = [], period: periodProp, onPeriodClear }: {
  title?: string;
  fixedSource?: string;
  periods?: string[];
  period?: string;
  onPeriodClear?: () => void;
}) {
  const [source, setSource] = useState(fixedSource || 'ALL');
  const [level, setLevel] = useState('ALL');
  const [period, setPeriod] = useState(periodProp || '');
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');
  const [limit, setLimit] = useState(100);
  const [rows, setRows] = useState<SyncEvent[]>([]);
  const [live, setLive] = useState(true);
  const [loading, setLoading] = useState(false);

  const seen = useRef<Set<number>>(new Set());
  const [fresh, setFresh] = useState<Set<number>>(new Set());
  const firstLoad = useRef(true);

  useEffect(() => { if (periodProp !== undefined) setPeriod(periodProp); }, [periodProp]);
  useEffect(() => { const t = setTimeout(() => setDebounced(search), 350); return () => clearTimeout(t); }, [search]);

  // Reset du "déjà vu" quand les filtres changent (pas d'animation flash en masse)
  useEffect(() => { firstLoad.current = true; seen.current = new Set(); }, [source, level, period, debounced]);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      if (typeof document !== 'undefined' && document.hidden) return;
      setLoading(true);
      const data = await fetchEvents({ source, level, period, search: debounced, limit });
      if (!alive) return;
      setLoading(false);
      const newIds = new Set<number>();
      if (!firstLoad.current) data.forEach(e => { if (!seen.current.has(e.id)) newIds.add(e.id); });
      data.forEach(e => seen.current.add(e.id));
      firstLoad.current = false;
      setFresh(newIds);
      setRows(data);
    };
    load();
    if (!live) return () => { alive = false; };
    const id = setInterval(load, 3000);
    return () => { alive = false; clearInterval(id); };
  }, [source, level, period, debounced, limit, live]);

  const counts = rows.reduce<Record<string, number>>((a, e) => { a[e.level] = (a[e.level] || 0) + 1; return a; }, {});

  return (
    <Card accent="violet">
      <CardHead
        icon={<ITerminal />}
        title={title}
        sub={`${rows.length} événements affichés · rafraîchi toutes les 3s`}
        right={
          <div className={s.row}>
            <span className={s.counter} style={{ color: '#fda4af' }}><StatusDot state="err" />{counts.ERROR || 0}</span>
            <span className={s.counter} style={{ color: '#fcd34d' }}><StatusDot state="warn" />{counts.WARN || 0}</span>
            <span className={s.counter} style={{ color: '#6ee7b7' }}><StatusDot state="live" />{counts.SUCCESS || 0}</span>
            <button className={cx(s.filterChip, live && s.filterOn)} onClick={() => setLive(!live)}>
              {live ? '● LIVE' : '❚❚ PAUSE'}
            </button>
          </div>
        }
      />

      <div className={s.row} style={{ marginBottom: 12 }}>
        {!fixedSource && ['ALL', ...Object.keys(SOURCES)].map(src => (
          <button key={src} className={cx(s.filterChip, source === src && s.filterOn)} onClick={() => setSource(src)}>
            {src === 'ALL' ? 'Toutes sources' : SOURCES[src][1]}
          </button>
        ))}
      </div>

      <div className={s.row} style={{ marginBottom: 14 }}>
        {LEVELS.map(l => (
          <button key={l} className={cx(s.filterChip, level === l && s.filterOn)} onClick={() => setLevel(l)}>{LEVEL_LABEL[l]}</button>
        ))}
        <select className={s.select} value={period} onChange={e => { setPeriod(e.target.value); if (!e.target.value) onPeriodClear?.(); }}>
          <option value="">Toutes périodes</option>
          {periods.map(p => <option key={p} value={p}>{p}</option>)}
        </select>
        <div className={cx(s.row, s.grow)} style={{ position: 'relative', minWidth: 180 }}>
          <span style={{ position: 'absolute', left: 11, color: 'var(--txt-3)', display: 'flex' }}><ISearch /></span>
          <input className={cx(s.input, s.grow)} style={{ paddingLeft: 32 }} placeholder="Rechercher dans les messages…" value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      </div>

      <div className={s.tableWrap}>
        <table className={s.table}>
          <thead>
            <tr>
              <th style={{ width: 150 }}>Horodatage</th>
              <th style={{ width: 120 }}>Source</th>
              <th style={{ width: 90 }}>Niveau</th>
              <th style={{ width: 100 }}>Période</th>
              <th>Ce qui s&apos;est passé</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(e => (
              <tr key={e.id} className={cx(fresh.has(e.id) && s.rowNew)}>
                <td className={s.mono}>
                  <span style={{ color: 'var(--txt-3)' }}>{e.createdAt?.slice(8, 10)}/{e.createdAt?.slice(5, 7)} </span>
                  <span className={s.strong}>{e.createdAt?.slice(11, 19)}</span>
                </td>
                <td><SourceBadge source={e.source} /></td>
                <td><LevelBadge level={e.level} /></td>
                <td className={s.mono}>
                  {e.period
                    ? <button className={s.filterChip} style={{ padding: '2px 8px' }} onClick={() => setPeriod(e.period || '')}>{e.period}</button>
                    : <span style={{ color: 'var(--txt-3)' }}>—</span>}
                </td>
                <td style={{ color: e.level === 'ERROR' ? '#fecdd3' : e.level === 'WARN' ? '#fde68a' : 'var(--txt)' }}>{e.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <div className={s.empty}>{loading ? 'Chargement…' : 'Aucun événement pour ces filtres.'}</div>}
      </div>

      {rows.length >= limit && limit < 500 && (
        <div style={{ marginTop: 12, textAlign: 'center' }}>
          <button className={s.btn} onClick={() => setLimit(Math.min(500, limit + 100))}>Charger plus d&apos;historique</button>
        </div>
      )}
    </Card>
  );
}
