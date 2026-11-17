'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import s from './cc.module.css';
import fx from './fx.module.css';
import { Card, CardHead, cx, LevelBadge, Segmented, SourceBadge, SOURCES, StatusDot, Accent } from './widgets';
import { ITerminal, ISearch } from './icons';
import { fetchEvents, eventsExportUrl, SyncEvent } from '../../services/api';
import { copyText, eventToLine, eventsToText, fmtEventTime } from './clipboard';

const LEVELS = ['ALL', 'ERROR', 'WARN', 'SUCCESS', 'INFO'];
const LEVEL_LABEL: Record<string, string> = { ALL: 'Tous', ERROR: 'Erreurs', WARN: 'Alertes', SUCCESS: 'Succès', INFO: 'Infos' };
const T_SRC: Record<string, string> = { TIME_MACHINE: fx.tTm, HEALER: fx.tHealer, AUTOPILOT: fx.tAuto, BYTEL: fx.tBytel, MAINTENANCE: fx.tMaint, SYSTEM: fx.tSys };
const T_LVL: Record<string, string> = { INFO: fx.tInfo, SUCCESS: fx.tOk, WARN: fx.tWarn, ERROR: fx.tErr };
const T_SRC_SHORT: Record<string, string> = { TIME_MACHINE: 'TMACH', HEALER: 'HEAL ', AUTOPILOT: 'AUTO ', BYTEL: 'BYTEL', MAINTENANCE: 'MAINT', SYSTEM: 'SYS  ' };
const T_LVL_SHORT: Record<string, string> = { INFO: 'INFO ', SUCCESS: 'OK   ', WARN: 'WARN ', ERROR: 'ERROR' };

const CopyIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </svg>
);
const DlIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" />
  </svg>
);

type View = 'table' | 'terminal';

export default function EventsTable({
  title = 'Journal des opérations', sources, periods = [], period: periodProp, onPeriodClear,
  compact = false, accent = 'violet', defaultView = 'table', storageKey,
}: {
  title?: string;
  /** Sources imposées (ex: ['TIME_MACHINE','HEALER']). Sinon : filtre libre. */
  sources?: string[];
  periods?: string[];
  period?: string;
  onPeriodClear?: () => void;
  compact?: boolean;
  accent?: Accent;
  defaultView?: View;
  storageKey?: string;
}) {
  const fixed = sources && sources.length > 0;
  const [source, setSource] = useState('ALL');
  const [level, setLevel] = useState('ALL');
  const [period, setPeriod] = useState(periodProp || '');
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');
  const [limit, setLimit] = useState(compact ? 60 : 100);
  const [rows, setRows] = useState<SyncEvent[]>([]);
  const [live, setLive] = useState(true);
  const [loading, setLoading] = useState(false);
  const [view, setView] = useState<View>(defaultView);
  const [copied, setCopied] = useState<string | null>(null);

  const seen = useRef<Set<number>>(new Set());
  const [fresh, setFresh] = useState<Set<number>>(new Set());
  const firstLoad = useRef(true);

  // Vue mémorisée par journal
  useEffect(() => {
    if (!storageKey) return;
    try { const v = localStorage.getItem(`ev-view-${storageKey}`); if (v === 'table' || v === 'terminal') setView(v); } catch { /* */ }
  }, [storageKey]);
  const changeView = (v: View) => {
    setView(v);
    if (storageKey) try { localStorage.setItem(`ev-view-${storageKey}`, v); } catch { /* */ }
  };

  useEffect(() => { if (periodProp !== undefined) setPeriod(periodProp); }, [periodProp]);
  useEffect(() => { const t = setTimeout(() => setDebounced(search), 350); return () => clearTimeout(t); }, [search]);

  const sourceParam = fixed ? (source === 'ALL' ? sources!.join(',') : source) : source;
  useEffect(() => { firstLoad.current = true; seen.current = new Set(); }, [sourceParam, level, period, debounced]);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      if (typeof document !== 'undefined' && document.hidden) return;
      setLoading(true);
      const data = await fetchEvents({ source: sourceParam, level, period, search: debounced, limit });
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
  }, [sourceParam, level, period, debounced, limit, live]);

  const flash = (key: string) => { setCopied(key); setTimeout(() => setCopied(null), 1600); };

  // Si le navigateur refuse le presse-papier : fenêtre avec le texte pré-sélectionné (Ctrl+C).
  const [manual, setManual] = useState<string | null>(null);
  const manualRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => { if (manual !== null) setTimeout(() => manualRef.current?.select(), 30); }, [manual]);

  const copyAll = async () => {
    const txt = eventsToText(rows);
    if (await copyText(txt)) flash('all'); else setManual(txt);
  };
  const copyRow = async (e: SyncEvent) => {
    const txt = eventToLine(e);
    if (await copyText(txt)) flash(`row-${e.id}`); else setManual(txt);
  };

  const counts = rows.reduce<Record<string, number>>((a, e) => { a[e.level] = (a[e.level] || 0) + 1; return a; }, {});
  const srcChoices = fixed ? ['ALL', ...sources!] : ['ALL', ...Object.keys(SOURCES)];

  return (
    <Card accent={accent}>
      <CardHead
        icon={<ITerminal />}
        title={title}
        sub={`${rows.length} événements · ${live ? 'live 3s' : 'en pause'}`}
        right={
          <div className={s.row}>
            {!compact && <>
              <span className={s.counter} style={{ color: '#fda4af' }}><StatusDot state="err" />{counts.ERROR || 0}</span>
              <span className={s.counter} style={{ color: '#fcd34d' }}><StatusDot state="warn" />{counts.WARN || 0}</span>
              <span className={s.counter} style={{ color: '#6ee7b7' }}><StatusDot state="live" />{counts.SUCCESS || 0}</span>
            </>}
            <Segmented<View> value={view} onChange={changeView} options={[
              { value: 'table', label: 'Table' },
              { value: 'terminal', label: 'Terminal' },
            ]} />
          </div>
        }
      />

      {/* Barre d'actions : copier / exporter */}
      <div className={s.row} style={{ marginBottom: 12 }}>
        <button className={cx(s.btn, fx.copyBtn, copied === 'all' && fx.copied)} onClick={copyAll} disabled={rows.length === 0}
          title="Copie les lignes affichées (format tableau, colle direct dans Excel / Teams)">
          <CopyIcon /> {copied === 'all' ? `${rows.length} lignes copiées ✓` : `Copier (${rows.length})`}
        </button>
        <button className={s.btn} title="Télécharge jusqu'à 5000 lignes avec les filtres actuels"
          onClick={() => {
            // URL construite au clic (l'hôte API dépend de window.location : sinon mismatch d'hydratation)
            const a = document.createElement('a');
            a.href = eventsExportUrl({ source: sourceParam, level, period, search: debounced, limit: 5000 });
            a.download = '';
            document.body.appendChild(a);
            a.click();
            a.remove();
          }}>
          <DlIcon /> Télécharger .tsv
        </button>
        <button className={cx(s.filterChip, live && s.filterOn)} onClick={() => setLive(!live)}>
          {live ? '● LIVE' : '❚❚ PAUSE'}
        </button>
        <span style={{ fontSize: '0.7rem', color: 'var(--txt-3)' }}>
          {view === 'terminal' ? 'Astuce : sélectionnez du texte à la souris pour copier une partie.' : 'Survolez une ligne pour la copier seule.'}
        </span>
      </div>

      <div className={s.row} style={{ marginBottom: 10 }}>
        {srcChoices.map(src => (
          <button key={src} className={cx(s.filterChip, source === src && s.filterOn)} onClick={() => setSource(src)}>
            {src === 'ALL' ? (fixed ? 'Tout' : 'Toutes sources') : SOURCES[src]?.[1] || src}
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
        {!compact && (
          <div className={cx(s.row, s.grow)} style={{ position: 'relative', minWidth: 180 }}>
            <span style={{ position: 'absolute', left: 11, color: 'var(--txt-3)', display: 'flex' }}><ISearch /></span>
            <input className={cx(s.input, s.grow)} style={{ paddingLeft: 32 }} placeholder="Rechercher dans les messages…" value={search} onChange={e => setSearch(e.target.value)} />
          </div>
        )}
      </div>

      <div key={view} className={fx.fadeSwap}>
        {view === 'terminal' ? (
          <div className={fx.term}>
            <div className={fx.termBar}>
              <span className={fx.termDot} style={{ background: '#f43f5e' }} />
              <span className={fx.termDot} style={{ background: '#f59e0b' }} />
              <span className={fx.termDot} style={{ background: '#10b981' }} />
              <span style={{ marginLeft: 8 }}>gringotts@sync:~$ tail -f events {period ? `--period=${period}` : ''}</span>
            </div>
            <pre className={fx.termBody} style={{ maxHeight: compact ? 380 : 520 }}>
              {rows.length === 0 && <span className={fx.tTime}>{loading ? 'chargement…' : 'aucun événement'}</span>}
              {rows.map(e => (
                <span key={e.id}>
                  <span className={fx.tTime}>{fmtEventTime(e.createdAt)}</span>{'  '}
                  <span className={T_SRC[e.source] || fx.tSys}>{T_SRC_SHORT[e.source] || e.source}</span>{'  '}
                  <span className={T_LVL[e.level] || fx.tInfo}>{T_LVL_SHORT[e.level] || e.level}</span>{'  '}
                  <span className={fx.tPeriod}>{(e.period || '—').padEnd(8)}</span>{'  '}
                  <span>{e.message}</span>{'\n'}
                </span>
              ))}
              <span className={fx.cursor} />
            </pre>
          </div>
        ) : (
          <div className={s.tableWrap} style={{ maxHeight: compact ? 420 : 560 }}>
            <table className={s.table}>
              <thead>
                <tr>
                  <th style={{ width: 130 }}>Horodatage</th>
                  {(!fixed || sources!.length > 1) && <th style={{ width: 110 }}>Source</th>}
                  <th style={{ width: 80 }}>Niveau</th>
                  <th style={{ width: 96 }}>Période</th>
                  <th>Ce qui s&apos;est passé</th>
                  <th style={{ width: 40 }} />
                </tr>
              </thead>
              <tbody>
                {rows.map(e => (
                  <tr key={e.id} className={cx(fresh.has(e.id) && s.rowNew)}>
                    <td className={s.mono}>
                      <span style={{ color: 'var(--txt-3)' }}>{e.createdAt?.slice(8, 10)}/{e.createdAt?.slice(5, 7)} </span>
                      <span className={s.strong}>{e.createdAt?.slice(11, 19)}</span>
                    </td>
                    {(!fixed || sources!.length > 1) && <td><SourceBadge source={e.source} /></td>}
                    <td><LevelBadge level={e.level} /></td>
                    <td className={s.mono}>
                      {e.period
                        ? <button className={s.filterChip} style={{ padding: '2px 8px' }} onClick={() => setPeriod(e.period || '')}>{e.period}</button>
                        : <span style={{ color: 'var(--txt-3)' }}>—</span>}
                    </td>
                    <td style={{ color: e.level === 'ERROR' ? '#fecdd3' : e.level === 'WARN' ? '#fde68a' : 'var(--txt)', userSelect: 'text' }}>{e.message}</td>
                    <td>
                      <button className={cx(fx.rowCopy, copied === `row-${e.id}` && fx.copied)} style={copied === `row-${e.id}` ? { opacity: 1 } : undefined}
                        onClick={() => copyRow(e)} title="Copier cette ligne">
                        {copied === `row-${e.id}` ? '✓' : <CopyIcon />}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length === 0 && <div className={s.empty}>{loading ? 'Chargement…' : 'Aucun événement pour ces filtres.'}</div>}
          </div>
        )}
      </div>

      {manual !== null && typeof document !== 'undefined' && createPortal(
        <div className={fx.modalBack} onClick={() => setManual(null)}>
          <div className={fx.modal} onClick={e => e.stopPropagation()}>
            <div className={s.row} style={{ justifyContent: 'space-between', marginBottom: 10 }}>
              <b>Copier les logs</b>
              <span style={{ fontSize: '0.74rem', color: 'var(--txt-3)' }}>Le texte est sélectionné : <kbd className={fx.kbd}>Ctrl</kbd> + <kbd className={fx.kbd}>C</kbd></span>
            </div>
            <textarea ref={manualRef} className={fx.modalText} readOnly value={manual} onFocus={e => e.currentTarget.select()} />
            <div className={s.row} style={{ justifyContent: 'flex-end', marginTop: 10 }}>
              <button className={s.btn} onClick={() => setManual(null)}>Fermer</button>
            </div>
          </div>
        </div>,
        // Portail hors de la carte : backdrop-filter piège les position:fixed
        document.querySelector('[data-cc-root]') || document.body
      )}

      {rows.length >= limit && limit < 500 && (
        <div style={{ marginTop: 12, textAlign: 'center' }}>
          <button className={s.btn} onClick={() => setLimit(Math.min(500, limit + 100))}>Charger plus d&apos;historique</button>
        </div>
      )}
    </Card>
  );
}
