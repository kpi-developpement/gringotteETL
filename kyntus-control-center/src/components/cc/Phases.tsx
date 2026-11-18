'use client';

import { useMemo, useState } from 'react';
import s from './cc.module.css';
import fx from './fx.module.css';
import { Card, CardHead, cx, Segmented, Num } from './widgets';
import { IDb, IGrid, IRadar, IRefresh, ISearch, IShield } from './icons';
import {
  AutoPilotInfo, checkEps, EpsCheck, PeriodRow, recollectPeriod, ScopeMode, setScope, verifyPeriod,
} from '../../services/api';

const fmt = (n: number | null | undefined) => (n ?? 0).toLocaleString('fr-FR');

type StepState = 'done' | 'active' | 'todo' | 'warn' | 'err' | 'wait';
const STEP_CLS: Record<StepState, string> = {
  done: fx.sDone, active: fx.sActive, todo: fx.sTodo, warn: fx.sWarn, err: fx.sErr, wait: fx.sWait,
};

function collectStep(r: PeriodRow): { st: StepState; label: string; sub: string } {
  const c = r.collect;
  switch (c.status) {
    case 'ACTIVE': return { st: 'active', label: 'Collecte', sub: `${fmt(c.offset)} / ${fmt(c.remote_total)} · ${c.progress}%` };
    case 'QUEUED': return { st: 'active', label: 'En file', sub: 'collecte imminente' };
    case 'PARTIAL': return { st: 'warn', label: 'À reprendre', sub: `${fmt(c.offset)} / ${fmt(c.remote_total)}` };
    case 'GAP': return { st: 'err', label: 'Trou → re-collecte', sub: 'programmée' };
    case 'EMPTY': return { st: 'done', label: 'Vide chez Bytel', sub: c.last_scan || '' };
    case 'DONE': return { st: 'done', label: 'Collecté', sub: `${fmt(r.local_distinct)} EPS · ${c.last_scan || ''}` };
    default: return { st: 'todo', label: 'À collecter', sub: 'jamais collecté' };
  }
}

function enrichStep(r: PeriodRow): { st: StepState; label: string; sub: string } {
  const e = r.enrich;
  switch (e.status) {
    case 'DONE': return { st: 'done', label: 'Enrichi', sub: `${e.pct}%${e.definitive ? ` · ${fmt(e.definitive)} définitifs` : ''}` };
    case 'RETRY': return { st: 'warn', label: 'Ré-essais', sub: `${fmt(e.retry)} programmés · ${e.pct}%` };
    case 'ACTIVE': return { st: 'active', label: 'Enrichissement', sub: `${fmt(e.fresh)} restants · ${e.pct}%` };
    case 'TODO': return { st: 'todo', label: 'À enrichir', sub: `${fmt(e.fresh)} EPS` };
    default: return { st: 'wait', label: 'En attente', sub: 'après collecte' };
  }
}

function verifyStep(r: PeriodRow): { st: StepState; label: string; sub: string } {
  const v = r.verify;
  switch (v.status) {
    case 'DONE': return { st: 'done', label: 'Vérifié', sub: `${fmt(v.local)} / ${fmt(v.remote)}${v.orphans && v.orphans > 0 ? ` · ${fmt(v.orphans)} orphelins` : ''}` };
    case 'GAP': return { st: 'err', label: 'Trou détecté', sub: `−${fmt((v.remote ?? 0) - (v.local ?? 0))} EPS` };
    case 'GAP_PERSISTENT': return { st: 'warn', label: 'Écart persistant', sub: `${fmt(v.local)} / ${fmt(v.remote)}` };
    case 'TODO': return { st: 'todo', label: 'À vérifier', sub: v.at ? `dernier ${v.at}` : 'prochain cycle' };
    default: return { st: 'wait', label: 'En attente', sub: 'après collecte' };
  }
}

const OVERALL: Record<string, [string, string]> = {
  COMPLETE: [s.bSuccess, '✓ Complet'],
  IN_PROGRESS: [s.bTm, 'En cours'],
  ANOMALY: [s.bWarn, 'Anomalie'],
  EMPTY: [s.bSys, 'Vide chez Bytel'],
  OUT_OF_SCOPE: [s.bSys, 'Hors périmètre'],
};

function Step({ n, st, label, sub, last = false }: { n: number; st: StepState; label: string; sub: string; last?: boolean }) {
  return (
    <div className={cx(fx.step, last && fx.stepLast)}>
      <div className={cx(fx.stepDot, STEP_CLS[st])}>
        {st === 'done' ? '✓' : st === 'err' ? '!' : st === 'warn' ? '↻' : n}
      </div>
      <div className={fx.stepTxt}>
        <div className={fx.stepLabel}>{label}</div>
        <div className={fx.stepSub}>{sub}</div>
      </div>
    </div>
  );
}

/** Tableau des 3 phases par période. */
export function PhaseBoard({ rows, selected, onSelect, notify, refresh }: {
  rows: PeriodRow[]; selected?: string; onSelect?: (p: string) => void; notify: (m: string) => void; refresh: () => void;
}) {
  const [showOut, setShowOut] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const shown = rows.filter(r => r.in_scope || showOut);
  const outCount = rows.filter(r => !r.in_scope).length;

  const act = async (key: string, fn: () => Promise<{ error?: string; message?: string }>, ok: string) => {
    setBusy(key);
    const res = await fn();
    setBusy(null);
    notify(res?.error ? `Erreur : ${res.error}` : (res?.message || ok));
    refresh();
  };

  return (
    <div>
      <div className={fx.phaseHead}>
        <span>Période</span>
        <span>① Collecte</span>
        <span>② Enrichissement</span>
        <span>③ Vérification</span>
        <span>État</span>
        <span style={{ textAlign: 'right' }}>
          {outCount > 0 && (
            <button className={cx(s.filterChip, showOut && s.filterOn)} onClick={() => setShowOut(!showOut)}>
              {showOut ? 'Masquer' : 'Voir'} hors périmètre ({outCount})
            </button>
          )}
        </span>
      </div>
      <div className={fx.phaseList}>
        {shown.map((r, i) => {
          const [ocls, olabel] = OVERALL[r.overall] || OVERALL.IN_PROGRESS;
          const c = collectStep(r), e = enrichStep(r), v = verifyStep(r);
          return (
            <div key={r.period} className={cx(fx.phaseRow, !r.in_scope && fx.phaseOut, selected === r.period && fx.phaseSel, r.collect.status === 'ACTIVE' && fx.phaseLive)}
              style={{ animationDelay: `${i * 30}ms` }} onClick={() => onSelect?.(r.period)}>
              <div className={cx(s.mono, s.strong)}>{r.period}</div>
              <Step n={1} {...c} />
              <Step n={2} {...e} />
              <Step n={3} {...v} last />
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'flex-start' }}>
                <span className={cx(s.badge, ocls)}>{olabel}</span>
                {r.verify.volume_low && (
                  <span className={cx(s.badge, s.bWarn)} title={`Bytel n'expose que ${fmt(r.collect.remote_total)} EPS pour ce mois, contre ~${fmt(r.verify.volume_median)} pour un mois habituel. Notre base contient tout ce que Bytel renvoie.`}>
                    ⚠ Volume Bytel {r.verify.volume_ratio}%
                  </span>
                )}
              </div>
              <div className={s.row} style={{ justifyContent: 'flex-end', flexWrap: 'nowrap' }} onClick={ev => ev.stopPropagation()}>
                {r.in_scope && <>
                  <button className={s.btn} style={{ padding: '6px 10px' }} disabled={busy !== null} title="1 appel léger à Bytel : total vs base"
                    onClick={() => act(`v-${r.period}`, () => verifyPeriod(r.period), `${r.period} vérifiée.`)}>
                    {busy === `v-${r.period}` ? '…' : <><ISearch /> Vérifier</>}
                  </button>
                  <button className={s.btn} style={{ padding: '6px 10px' }} disabled={busy !== null} title="Re-télécharger toute la période"
                    onClick={() => { if (window.confirm(`Re-collecter entièrement ${r.period} ?`)) act(`r-${r.period}`, () => recollectPeriod(r.period), 'Collecte programmée.'); }}>
                    <IRefresh /> Collecter
                  </button>
                </>}
              </div>
            </div>
          );
        })}
        {shown.length === 0 && <div className={s.empty}>Chargement des périodes…</div>}
      </div>
    </div>
  );
}

/** Résumé : combien de périodes dans chaque phase. */
export function PhaseSummary({ ap }: { ap: AutoPilotInfo | undefined }) {
  const pc = ap?.phase_counts;
  const items = [
    { k: 'collect', label: 'Collecte', icon: <IDb />, v: pc?.collect ?? 0, hint: 'périodes à collecter' },
    { k: 'enrich', label: 'Enrichissement', icon: <IShield />, v: pc?.enrich ?? 0, hint: 'périodes à enrichir' },
    { k: 'verify', label: 'Vérification', icon: <IRadar />, v: pc?.verify ?? 0, hint: 'périodes à vérifier' },
    { k: 'complete', label: 'Complètes', icon: <IGrid />, v: pc?.complete ?? 0, hint: `sur ${ap?.periods_total ?? 0}` },
  ];
  return (
    <div className={fx.phaseSummary}>
      {items.map((it, i) => (
        <div key={it.k} className={cx(fx.phaseChip, it.k === 'complete' && fx.phaseChipDone, it.k !== 'complete' && it.v > 0 && fx.phaseChipBusy)}>
          <div className={fx.phaseChipIcon}>{it.icon}</div>
          <div>
            <div className={fx.phaseChipLabel}>{i < 3 ? `${i + 1}. ` : ''}{it.label}</div>
            <div className={fx.phaseChipValue}><Num value={it.v} /></div>
            <div className={fx.phaseChipHint}>{it.hint}</div>
          </div>
          {i < 3 && <span className={fx.phaseArrow}>→</span>}
        </div>
      ))}
    </div>
  );
}

/** Sélecteur de périmètre (années récupérées). */
export function ScopeCard({ ap, notify, refresh }: { ap: AutoPilotInfo | undefined; notify: (m: string) => void; refresh: () => void }) {
  const [custom, setCustom] = useState('');
  const options = useMemo(() => {
    const out: string[] = [];
    const d = new Date();
    for (let i = 0; i < 36; i++) {
      const dt = new Date(d.getFullYear(), d.getMonth() - i, 1);
      out.push(`${dt.getFullYear()}_M${String(dt.getMonth() + 1).padStart(2, '0')}`);
    }
    return out;
  }, []);

  const apply = async (mode: ScopeMode, start?: string) => {
    const res = await setScope(mode, start);
    notify(res?.error ? `Erreur : ${res.error}` : `Périmètre : ${res.scope_label} (${res.scope_start} → aujourd'hui)`);
    refresh();
  };

  const mode = ap?.scope_mode || 'CURRENT_YEAR';
  const r = ap?.refresh;
  return (
    <Card accent="amber">
      <CardHead icon={<IGrid />} title="Périmètre"
        sub={ap ? `${ap.scope_start} → aujourd'hui · ${ap.periods_total} périodes suivies` : '…'}
        right={
          <Segmented<ScopeMode> value={mode} onChange={m => m === 'CUSTOM' ? apply('CUSTOM', custom || ap?.scope_start || options[options.length - 1]) : apply(m)} options={[
            { value: 'CURRENT_YEAR', label: 'Année en cours' },
            { value: 'LAST_12', label: '12 derniers mois' },
            { value: 'CUSTOM', label: 'Depuis…' },
          ]} />
        } />
      {mode === 'CUSTOM' && (
        <div className={s.row} style={{ marginBottom: 12 }}>
          <span style={{ fontSize: '.78rem', color: 'var(--txt-2)' }}>Depuis</span>
          <select className={s.select} value={custom || ap?.scope_start || ''} onChange={e => { setCustom(e.target.value); apply('CUSTOM', e.target.value); }}>
            {options.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </div>
      )}
      <div className={fx.policy}>
        <div><b>Mois courant</b><span>re-collecte toutes les {r?.current_min ?? '–'} min</span></div>
        <div><b>Mois précédent</b><span>toutes les {r?.previous_h ?? '–'} h</span></div>
        <div><b>Mois clos</b><span>{r && r.closed_h > 0 ? `toutes les ${r.closed_h} h` : 'jamais (vérifiés seulement)'}</span></div>
        <div><b>Vérification</b><span>toutes les {r?.verify_h ?? '–'} h · 1 appel / période</span></div>
      </div>
      <p className={fx.policyNote}>Hors périmètre : les données restent en base mais ne sont plus ni re-collectées ni enrichies.</p>
    </Card>
  );
}

const VERDICT: Record<string, [string, string, (c: EpsCheck) => string]> = {
  OK: [s.calloutOk, 'Présent et complet', () => 'L’EPS est en base avec son détail.'],
  ABSENT_BASE: [s.calloutErr, 'Absent de la base', () => 'Introuvable en base. S’il existe chez Bytel, sa période n’est pas encore collectée ou est hors périmètre.'],
  DOUBLON: [s.calloutWarn, 'En double', c => `Présent ${c.duplicates + 1} fois en base → lancez « Dédoublonner » dans l’atelier.`],
  NON_ENRICHI: [s.calloutWarn, 'Pas encore enrichi', () => 'En base, détail pas encore récupéré : il est dans la file du Healer.'],
  EN_REESSAI: [s.calloutWarn, 'Ré-essai programmé', c => `Bytel n’a pas donné de détail (essai ${c.rows[0]?.heal_attempts}). Prochain essai : ${c.rows[0]?.heal_next_at?.replace('T', ' ').slice(0, 16) || '—'}.`],
  DEFINITIF: [s.calloutErr, 'Sans détail (définitif)', c => `Aucun détail après ${c.rows[0]?.heal_attempts} essais espacés — classé définitif.`],
};

/** Inspecteur : vérifier un EPS précis en base et (option) en direct chez Bytel. */
export function EpsInspector({ notify }: { notify: (m: string) => void }) {
  const [id, setId] = useState('');
  const [res, setRes] = useState<EpsCheck | null>(null);
  const [loading, setLoading] = useState(false);

  const run = async (live: boolean) => {
    if (!id.trim()) return;
    setLoading(true);
    const r = await checkEps(id, live);
    setLoading(false);
    if (!r) return notify('Inspecteur injoignable.');
    setRes(r);
    if (r.live?.repaired) notify(`EPS réparé : détail récupéré chez Bytel et enregistré.`);
  };

  const v = res ? VERDICT[res.verdict] || VERDICT.OK : null;
  return (
    <Card accent="cyan">
      <CardHead icon={<ISearch />} title="Inspecteur d’EPS" sub="Vérifier une intervention précise : en base, et en direct chez Bytel (répare si besoin)" />
      <div className={s.row}>
        <input className={cx(s.input, s.grow)} placeholder="id_intervention (ex : EPS…)" value={id}
          onChange={e => setId(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') run(false); }} />
        <button className={s.btn} disabled={loading || !id.trim()} onClick={() => run(false)}><IDb /> En base</button>
        <button className={cx(s.btn, s.btnSolid, s.accCyan)} disabled={loading || !id.trim()} onClick={() => run(true)}><IRadar /> Base + Bytel live</button>
      </div>

      {loading && <div className={s.empty}>Vérification…</div>}
      {res && v && !loading && (
        <div className={fx.fadeSwap} style={{ marginTop: 14, display: 'grid', gap: 12 }}>
          <div className={cx(s.callout, v[0])}>
            <div><b>{v[1]}</b> — <span className={s.mono}>{res.id_intervention}</span><br />{v[2](res)}</div>
          </div>
          {res.rows.length > 0 && (
            <div className={s.tableWrap} style={{ maxHeight: 220 }}>
              <table className={s.table}>
                <thead><tr><th>Ligne</th><th>Période</th><th>État</th><th>Type</th><th>Détail</th><th>Essais</th><th>Vu chez Bytel</th></tr></thead>
                <tbody>
                  {res.rows.map(r => (
                    <tr key={r.id}>
                      <td className={s.mono}>#{r.id}</td>
                      <td className={s.mono}>{r.periode}{!r.in_scope && <span style={{ color: 'var(--txt-3)' }}> (hors)</span>}</td>
                      <td>{r.etat || '—'}</td>
                      <td>{r.type || '—'}</td>
                      <td><span className={cx(s.badge, r.detail_status === 'OK' ? s.bSuccess : r.detail_status === 'DEFINITIF' ? s.bError : s.bWarn)}>{r.detail_status.replace('_', ' ')}</span> <span className={s.mono} style={{ color: 'var(--txt-3)' }}>{r.detail_size} car.</span></td>
                      <td className={s.mono}>{r.heal_attempts}</td>
                      <td className={s.mono}>{r.last_seen_at ? r.last_seen_at.replace('T', ' ').slice(0, 16) : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {res.live && (
            <div className={cx(s.callout, res.live.reachable ? (res.live.has_detail ? s.calloutOk : s.calloutWarn) : s.calloutErr)}>
              <div>
                <b>Bytel en direct :</b>{' '}
                {!res.live.reachable ? `injoignable (${res.live.error})`
                  : res.live.has_detail ? `détail disponible (${res.live.latency_ms} ms)${res.live.repaired ? ` — ${res.live.repaired} ligne(s) réparée(s) ✓` : ''}`
                  : `aucun détail renvoyé pour cet EPS (${res.live.latency_ms} ms)`}
                {res.live.preview && <pre className={fx.preview}>{res.live.preview}</pre>}
              </div>
            </div>
          )}
        </div>
      )}
    </Card>
  );
}
