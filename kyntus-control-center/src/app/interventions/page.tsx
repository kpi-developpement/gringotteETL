'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import s from '../../components/cc/cc.module.css';
import fx from '../../components/cc/fx.module.css';
import ex from '../../components/cc/explorer.module.css';
import Shell from '../../components/cc/Shell';
import { useLive, useToast } from '../../components/cc/hooks';
import { Card, cx, Num, Toast } from '../../components/cc/widgets';
import { IChevron, IClose, ISearch, IRadar } from '../../components/cc/icons';
import { copyText } from '../../components/cc/clipboard';
import {
  checkEps, explorerExportUrl, ExplorerDim, ExplorerFilters, ExplorerRow, Facets, fetchExplorer, fetchExplorerItem, fetchFacets,
} from '../../services/api';

const CAT_COLOR: Record<string, string> = { RACC: '#22d3ee', SAV: '#a855f7', RZO: '#f59e0b', AUTRE: '#94a3b8' };
const CAT_LABEL: Record<string, string> = { RACC: 'Raccordement', SAV: 'SAV', RZO: 'Réseau', AUTRE: 'Autre' };
const DETAIL_LABEL: Record<string, [string, string]> = {
  OK: [s.bSuccess, 'Enrichi'], NON_ENRICHI: [s.bInfo, 'À enrichir'], EN_REESSAI: [s.bWarn, 'Ré-essai'], DEFINITIF: [s.bError, 'Sans détail'],
};

const FACET_GROUPS: { key: ExplorerDim | 'detail'; title: string; search?: boolean; open?: boolean }[] = [
  { key: 'periode', title: 'Période', open: true },
  { key: 'categorie', title: 'Catégorie', open: true },
  { key: 'type', title: 'Type de presta', open: true },
  { key: 'etat', title: 'État qualification' },
  { key: 'detail', title: 'Statut du détail' },
  { key: 'cloture', title: 'Code clôture', search: true },
  { key: 'sous_traitant', title: 'Sous-traitant', search: true },
  { key: 'departement', title: 'Département', search: true },
  { key: 'oi', title: 'OI', search: true },
  { key: 'raccordement', title: 'Type raccordement' },
];

const EMPTY: ExplorerFilters = { dims: {} };

/** Lecture / écriture des filtres dans l'URL (lien partageable). */
function readUrl(): { f: ExplorerFilters; page: number; sort: string } {
  if (typeof window === 'undefined') return { f: EMPTY, page: 0, sort: 'date_desc' };
  const p = new URLSearchParams(window.location.search);
  const dims: ExplorerFilters['dims'] = {};
  FACET_GROUPS.forEach(g => { if (g.key !== 'detail' && p.get(g.key)) dims[g.key as ExplorerDim] = p.get(g.key)!.split(','); });
  return {
    f: { dims, detail: p.get('detail') || undefined, q: p.get('q') || undefined, from: p.get('from') || undefined, to: p.get('to') || undefined },
    page: +(p.get('page') || 0), sort: p.get('sort') || 'date_desc',
  };
}

export default function ExplorerPage() {
  const { stats, healer } = useLive();
  const { toast, show } = useToast();
  const [filters, setFilters] = useState<ExplorerFilters>(EMPTY);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [sort, setSort] = useState('date_desc');
  const [search, setSearch] = useState('');
  const [rows, setRows] = useState<ExplorerRow[]>([]);
  const [total, setTotal] = useState(0);
  const [facets, setFacets] = useState<Facets | null>(null);
  const [loading, setLoading] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);
  const [ready, setReady] = useState(false);
  const reqId = useRef(0);

  // Init depuis l'URL
  useEffect(() => {
    const u = readUrl();
    setFilters(u.f); setPage(u.page); setSort(u.sort); setSearch(u.f.q || '');
    setReady(true);
  }, []);

  // Recherche texte (debounce)
  useEffect(() => {
    if (!ready) return;
    const t = setTimeout(() => {
      setFilters(f => (f.q || undefined) === (search || undefined) ? f : { ...f, q: search || undefined });
      setPage(p => (filters.q || '') === search ? p : 0);
    }, 400);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search, ready]);

  const load = useCallback(async () => {
    const my = ++reqId.current;
    setLoading(true);
    const [r, fc] = await Promise.all([fetchExplorer(filters, page, size, sort), fetchFacets(filters)]);
    if (my !== reqId.current) return; // réponse périmée : un filtre plus récent a été appliqué
    setLoading(false);
    if (r) { setRows(r.rows); setTotal(r.total); }
    if (fc) setFacets(fc);
  }, [filters, page, size, sort]);

  useEffect(() => {
    if (!ready) return;
    load();
    const p = new URLSearchParams();
    Object.entries(filters.dims).forEach(([k, v]) => { if (v?.length) p.set(k, v.join(',')); });
    ['detail', 'q', 'from', 'to'].forEach(k => { const v = filters[k as keyof ExplorerFilters] as string | undefined; if (v) p.set(k, v); });
    if (page) p.set('page', String(page));
    if (sort !== 'date_desc') p.set('sort', sort);
    window.history.replaceState(null, '', `${window.location.pathname}${p.toString() ? '?' + p : ''}`);
  }, [filters, page, sort, size, load, ready]);

  const toggle = (key: ExplorerDim | 'detail', value: string) => {
    setPage(0);
    setFilters(f => {
      if (key === 'detail') return { ...f, detail: f.detail === value ? undefined : value };
      const cur = f.dims[key] || [];
      const next = cur.includes(value) ? cur.filter(v => v !== value) : [...cur, value];
      return { ...f, dims: { ...f.dims, [key]: next } };
    });
  };
  const clearKey = (key: string) => {
    setPage(0);
    setFilters(f => {
      if (key === 'detail' || key === 'q' || key === 'from' || key === 'to') {
        if (key === 'q') setSearch('');
        return { ...f, [key]: undefined };
      }
      const d = { ...f.dims }; delete d[key as ExplorerDim]; return { ...f, dims: d };
    });
  };
  const reset = () => { setFilters(EMPTY); setSearch(''); setPage(0); };

  const catFacet = facets?.categorie || [];
  const catTotal = catFacet.reduce((a, c) => a + c.n, 0) || 1;
  const pages = Math.max(1, Math.ceil(total / size));
  const activeCount = Object.values(filters.dims).reduce((a, v) => a + (v?.length || 0), 0) + (filters.detail ? 1 : 0) + (filters.q ? 1 : 0) + (filters.from || filters.to ? 1 : 0);

  const exportXlsx = () => {
    if (total > 200000) return show('Trop de lignes (> 200 000) : affinez les filtres.');
    const a = document.createElement('a');
    a.href = explorerExportUrl(filters);
    a.download = '';
    document.body.appendChild(a); a.click(); a.remove();
    show(`Export Excel de ${total.toLocaleString('fr-FR')} EPS lancé…`);
  };

  return (
    <Shell stats={stats} healer={healer}>
      {/* ================= HERO ================= */}
      <Card accent="violet" glow>
        <div className={ex.hero}>
          <div className={ex.total}>
            <span className={ex.totalLabel}>EPS correspondants</span>
            <span className={ex.totalValue}><Num value={total} /></span>
            <span className={ex.totalSub}>{activeCount ? `${activeCount} filtre(s) actif(s)` : 'Aucun filtre — toute la base'}{loading ? ' · chargement…' : ''}</span>
          </div>
          <div className={ex.catBar}>
            {['RACC', 'SAV', 'RZO'].map(c => {
              const n = catFacet.find(x => x.v === c)?.n || 0;
              const on = (filters.dims.categorie || []).includes(c);
              return (
                <button key={c} className={cx(ex.cat, on && ex.catOn)} style={{ ['--c' as string]: CAT_COLOR[c] }} onClick={() => toggle('categorie', c)}>
                  <span>{c}</span>
                  <b><Num value={n} /></b>
                  <small style={{ fontSize: '.66rem', color: '#a5b0c8' }}>{CAT_LABEL[c]} · {Math.round((n / catTotal) * 100)}%</small>
                  <i style={{ width: `${(n / catTotal) * 100}%` }} />
                </button>
              );
            })}
          </div>
          <div className={s.row} style={{ flexDirection: 'column', alignItems: 'stretch', minWidth: 260 }}>
            <div style={{ position: 'relative', display: 'flex' }}>
              <span style={{ position: 'absolute', left: 11, top: 10, color: 'var(--txt-3)' }}><ISearch /></span>
              <input className={cx(s.input, s.grow)} style={{ paddingLeft: 32 }} placeholder="Rechercher un EPS ou un FYT…" value={search} onChange={e => setSearch(e.target.value)} />
            </div>
            <div className={s.row}>
              <button className={cx(s.btn, s.btnSolid, s.accEmerald, s.grow)} onClick={exportXlsx} disabled={!total}>⬇ Excel ({total.toLocaleString('fr-FR')})</button>
              <button className={s.btn} onClick={reset} disabled={!activeCount}>Réinitialiser</button>
            </div>
          </div>
        </div>
      </Card>

      <div className={ex.layout}>
        {/* ================= FILTRES ================= */}
        <aside className={ex.side}>
          <div className={ex.facet}>
            <div className={ex.facetTitle} style={{ marginBottom: 8 }}>Date d’intervention</div>
            <div className={s.row} style={{ flexWrap: 'nowrap' }}>
              <input type="date" className={s.input} style={{ flex: 1, minWidth: 0, colorScheme: 'dark' }} value={filters.from || ''} onChange={e => { setPage(0); setFilters(f => ({ ...f, from: e.target.value || undefined })); }} />
              <input type="date" className={s.input} style={{ flex: 1, minWidth: 0, colorScheme: 'dark' }} value={filters.to || ''} onChange={e => { setPage(0); setFilters(f => ({ ...f, to: e.target.value || undefined })); }} />
            </div>
          </div>
          {FACET_GROUPS.map(g => (
            <FacetGroup key={g.key} title={g.title} search={g.search} defaultOpen={g.open}
              values={facets?.[g.key] || []}
              selected={g.key === 'detail' ? (filters.detail ? [filters.detail] : []) : (filters.dims[g.key as ExplorerDim] || [])}
              labelOf={v => g.key === 'detail' ? (DETAIL_LABEL[v]?.[1] || v) : g.key === 'categorie' ? `${v} · ${CAT_LABEL[v] || ''}` : v}
              onToggle={v => toggle(g.key, v)} />
          ))}
        </aside>

        {/* ================= RÉSULTATS ================= */}
        <Card accent="cyan" style={{ padding: 18 }}>
          <div className={s.row} style={{ justifyContent: 'space-between', marginBottom: 12 }}>
            <div className={ex.activeBar}>
              {Object.entries(filters.dims).flatMap(([k, vs]) => (vs || []).map(v => (
                <span key={k + v} className={ex.activeChip}><b>{FACET_GROUPS.find(g => g.key === k)?.title}</b> {v === '∅' ? '(vide)' : v}
                  <button onClick={() => toggle(k as ExplorerDim, v)}>×</button></span>
              )))}
              {filters.detail && <span className={ex.activeChip}><b>Détail</b> {DETAIL_LABEL[filters.detail]?.[1]}<button onClick={() => clearKey('detail')}>×</button></span>}
              {filters.q && <span className={ex.activeChip}><b>Recherche</b> {filters.q}<button onClick={() => clearKey('q')}>×</button></span>}
              {(filters.from || filters.to) && <span className={ex.activeChip}><b>Date</b> {filters.from || '…'} → {filters.to || '…'}<button onClick={() => { clearKey('from'); clearKey('to'); }}>×</button></span>}
              {!activeCount && <span style={{ fontSize: '.76rem', color: 'var(--txt-3)' }}>Cliquez dans les filtres à gauche ou sur RACC / SAV / RZO.</span>}
            </div>
            <div className={s.row}>
              <select className={s.select} value={sort} onChange={e => { setSort(e.target.value); setPage(0); }}>
                <option value="date_desc">Date ↓ (récentes)</option>
                <option value="date_asc">Date ↑ (anciennes)</option>
                <option value="id_desc">Dernières importées</option>
              </select>
              <select className={s.select} value={size} onChange={e => { setSize(+e.target.value); setPage(0); }}>
                {[25, 50, 100, 200].map(n => <option key={n} value={n}>{n} / page</option>)}
              </select>
            </div>
          </div>

          <div className={s.tableWrap} style={{ maxHeight: 'calc(100vh - 330px)', minHeight: 420 }}>
            <table className={s.table}>
              <thead>
                <tr>
                  <th>Date interv.</th><th>EPS</th><th>Période</th><th>Catégorie</th><th>Type</th><th>État</th><th>Clôture</th><th>Sous-traitant</th><th>Dép.</th><th>Détail</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => {
                  const [dcls, dlabel] = DETAIL_LABEL[r.detail_status] || DETAIL_LABEL.OK;
                  return (
                    <tr key={r.id} className={cx(ex.row, openId === r.id && ex.rowSel)} style={{ animationDelay: `${Math.min(i, 30) * 12}ms` }} onClick={() => setOpenId(r.id)}>
                      <td className={s.mono}>{r.date_intervention ? String(r.date_intervention).slice(0, 10) : '—'}</td>
                      <td><div className={ex.epsId}>{r.id_intervention}</div><div className={ex.fyt}>{r.fyt || ''}</div></td>
                      <td className={s.mono}>{r.periode || '—'}</td>
                      <td>{r.categorie ? <span className={ex.catBadge} style={{ ['--c' as string]: CAT_COLOR[r.categorie] || '#94a3b8' }}>{r.categorie}</span> : <span style={{ color: 'var(--txt-3)' }}>—</span>}</td>
                      <td className={s.mono}>{r.type_presta || '—'}</td>
                      <td style={{ fontSize: '.7rem' }}>{r.etat_qualif ? r.etat_qualif.replaceAll('_', ' ').toLowerCase() : '—'}</td>
                      <td className={s.mono}>{r.code_cloture || '—'}</td>
                      <td style={{ maxWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.sous_traitant || '—'}</td>
                      <td className={s.mono}>{r.departement || '—'}</td>
                      <td><span className={cx(s.badge, dcls)}>{dlabel}</span></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {rows.length === 0 && <div className={s.empty}>{loading ? 'Chargement…' : 'Aucun EPS pour ces filtres.'}</div>}
          </div>

          <div className={ex.pager}>
            <span style={{ fontSize: '.74rem', color: 'var(--txt-3)' }}>
              {total ? `${(page * size + 1).toLocaleString('fr-FR')}–${Math.min(total, (page + 1) * size).toLocaleString('fr-FR')} sur ${total.toLocaleString('fr-FR')}` : '0 résultat'}
            </span>
            <div className={ex.pageBtns}>
              <button className={s.btn} disabled={page === 0} onClick={() => setPage(0)}>«</button>
              <button className={s.btn} disabled={page === 0} onClick={() => setPage(p => p - 1)}>‹ Préc.</button>
              <span className={s.mono} style={{ fontSize: '.78rem', padding: '0 8px' }}>{page + 1} / {pages.toLocaleString('fr-FR')}</span>
              <button className={s.btn} disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)}>Suiv. ›</button>
              <button className={s.btn} disabled={page + 1 >= pages} onClick={() => setPage(pages - 1)}>»</button>
            </div>
          </div>
        </Card>
      </div>

      {openId !== null && <Drawer id={openId} onClose={() => setOpenId(null)} notify={show} />}
      <Toast msg={toast} />
    </Shell>
  );
}

function FacetGroup({ title, values, selected, onToggle, labelOf, search, defaultOpen }: {
  title: string; values: { v: string | null; n: number }[]; selected: string[]; onToggle: (v: string) => void;
  labelOf: (v: string) => string; search?: boolean; defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(!!defaultOpen || selected.length > 0);
  const [q, setQ] = useState('');
  const [all, setAll] = useState(false);
  const max = Math.max(1, ...values.map(v => v.n));
  const list = values
    .map(v => ({ key: v.v ?? '∅', label: v.v === null ? '(vide)' : labelOf(v.v), n: v.n }))
    .filter(v => !q || v.label.toLowerCase().includes(q.toLowerCase()));
  const shown = all ? list : list.slice(0, 8);
  // une valeur sélectionnée qui n'a plus de résultat reste affichée pour pouvoir la décocher
  selected.filter(sv => !list.some(l => l.key === sv)).forEach(sv => shown.unshift({ key: sv, label: sv === '∅' ? '(vide)' : labelOf(sv), n: 0 }));

  return (
    <div className={ex.facet}>
      <div className={ex.facetHead} onClick={() => setOpen(!open)}>
        <span className={ex.facetTitle}>{title}{selected.length > 0 && <span className={ex.facetCount}>{selected.length}</span>}</span>
        <span className={fx.stepSub} style={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform .25s', display: 'flex' }}><IChevron /></span>
      </div>
      {open && (
        <div className={ex.facetBody}>
          {search && values.length > 8 && <input className={cx(s.input, ex.facetSearch)} placeholder="Filtrer…" value={q} onChange={e => setQ(e.target.value)} />}
          {shown.map(v => {
            const on = selected.includes(v.key);
            return (
              <button key={v.key} className={cx(ex.opt, on && ex.optOn)} onClick={() => onToggle(v.key)} title={v.label}>
                <span className={ex.optBar} style={{ width: `${(v.n / max) * 100}%` }} />
                <span className={ex.optBox}>{on ? '✓' : ''}</span>
                <span className={ex.optLabel}>{v.label}</span>
                <span className={ex.optN}>{v.n.toLocaleString('fr-FR')}</span>
              </button>
            );
          })}
          {values.length === 0 && <span style={{ fontSize: '.72rem', color: 'var(--txt-3)', padding: 6 }}>Aucune valeur</span>}
          {list.length > 8 && <button className={ex.more} onClick={() => setAll(!all)}>{all ? 'Voir moins' : `Voir les ${list.length} valeurs`}</button>}
        </div>
      )}
    </div>
  );
}

interface Qualif { _type?: string; date?: string; typeIntervention?: string; etat?: string; coutIntervention?: number; typeRaccordement?: string }

function Drawer({ id, onClose, notify }: { id: number; onClose: () => void; notify: (m: string) => void }) {
  const [item, setItem] = useState<Record<string, unknown> | null>(null);
  const [live, setLive] = useState<string | null>(null);

  useEffect(() => { setItem(null); setLive(null); fetchExplorerItem(id).then(setItem); }, [id]);
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [onClose]);

  const detail = useMemo(() => {
    const raw = item?.detail_json as string | undefined;
    if (!raw) return null;
    try { return JSON.parse(raw) as Record<string, unknown>; } catch { return null; }
  }, [item]);
  const pretty = useMemo(() => detail ? JSON.stringify(detail, null, 2) : (item?.detail_json as string) || '', [detail, item]);
  const quals = ((detail?.qualificationInterventions as Qualif[]) || []).slice().sort((a, b) => String(b.date).localeCompare(String(a.date)));
  const cat = item?.categorie as string | undefined;

  const runLive = async () => {
    setLive('Vérification chez Bytel…');
    const r = await checkEps(String(item?.id_intervention), true);
    if (!r?.live) return setLive('Inspecteur injoignable.');
    setLive(!r.live.reachable ? `Bytel injoignable (${r.live.error})`
      : r.live.has_detail ? `✓ Bytel a bien le détail (${r.live.latency_ms} ms)${r.live.repaired ? ' — base réparée' : ''}` : '✗ Bytel ne renvoie aucun détail pour cet EPS');
    if (r.live.repaired) fetchExplorerItem(id).then(setItem);
  };

  const kv: [string, unknown][] = item ? [
    ['Période', item.periode], ['Date intervention', item.date_intervention ? String(item.date_intervention).slice(0, 16).replace('T', ' ') : null],
    ['Type de presta', item.type_presta], ['Type raccordement', item.type_raccordement],
    ['État qualification', item.etat_qualif], ['Code clôture', item.code_cloture],
    ['Sous-traitant', item.sous_traitant], ['Département', item.departement],
    ['OI', item.oi], ['FYT', item.fyt],
    ['Vu chez Bytel', item.last_seen_at ? String(item.last_seen_at).slice(0, 16).replace('T', ' ') : null], ['Essais Healer', item.heal_attempts ?? 0],
  ] : [];

  return createPortal(
    <>
      <div className={ex.drawerBack} onClick={onClose} />
      <aside className={ex.drawer}>
        {!item ? <div className={s.empty}>Chargement…</div> : (
          <>
            <div className={ex.drawerHead}>
              <div>
                <div className={ex.drawerId}>{String(item.id_intervention)}</div>
                <div className={s.row} style={{ marginTop: 8 }}>
                  {cat && <span className={ex.catBadge} style={{ ['--c' as string]: CAT_COLOR[cat] || '#94a3b8' }}>{cat} · {CAT_LABEL[cat]}</span>}
                  <span className={cx(s.badge, s.bSys)}>ligne #{String(item.id)}</span>
                  <span className={cx(s.badge, s.bSys)}>{String(item.source_ingestion || '—')}</span>
                </div>
              </div>
              <button className={s.btn} onClick={onClose}><IClose /></button>
            </div>

            <div className={ex.kv}>
              {kv.map(([k, v]) => <div key={k}><span>{k}</span><b title={String(v ?? '')}>{v === null || v === undefined || v === '' ? '—' : String(v)}</b></div>)}
            </div>

            <div className={s.row} style={{ marginBottom: 18 }}>
              <button className={cx(s.btn, s.btnSolid, s.accCyan)} onClick={runLive}><IRadar /> Vérifier chez Bytel</button>
              <button className={s.btn} onClick={async () => { if (await copyText(pretty)) notify('Détail JSON copié.'); }}>Copier le JSON</button>
              {live && <span style={{ fontSize: '.76rem', color: live.startsWith('✓') ? '#6ee7b7' : live.startsWith('✗') ? '#fda4af' : 'var(--txt-2)' }}>{live}</span>}
            </div>

            {quals.length > 0 && <>
              <div className={ex.sectionLabel}>Historique des qualifications</div>
              <div className={ex.timeline}>
                {quals.map((q, i) => {
                  const c = q._type === 'QualificationSAV' ? CAT_COLOR.SAV : q._type === 'QualificationReseau' ? CAT_COLOR.RZO : CAT_COLOR.RACC;
                  return (
                    <div key={i} className={ex.tlItem} style={{ ['--c' as string]: c }}>
                      <span className={ex.tlDot} />
                      <div className={ex.tlTitle}>{q.typeIntervention || q._type} · {q.etat?.replaceAll('_', ' ').toLowerCase()}</div>
                      <div className={ex.tlSub}>{String(q.date || '').slice(0, 16).replace('T', ' ')}{q.coutIntervention !== undefined ? ` · coût ${q.coutIntervention} €` : ''}{q.typeRaccordement ? ` · ${q.typeRaccordement}` : ''}</div>
                    </div>
                  );
                })}
              </div>
            </>}

            <div className={ex.sectionLabel}>Détail Bytel brut</div>
            {pretty ? <pre className={ex.json}>{pretty}</pre> : <div className={s.empty}>Pas encore de détail (dans la file du Healer).</div>}
          </>
        )}
      </aside>
    </>,
    document.querySelector('[data-cc-root]') || document.body
  );
}
