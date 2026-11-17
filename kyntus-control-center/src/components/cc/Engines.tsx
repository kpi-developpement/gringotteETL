'use client';

import { useEffect, useRef, useState } from 'react';
import s from './cc.module.css';
import fx from './fx.module.css';
import { Card, CardHead, cx, Donut, fmtDur, Num, Progress, Sparkline } from './widgets';
import { IClock, IPlay, IShield, IStop } from './icons';
import { HealerInfo, SyncStats } from '../../services/api';

/** Débit Time Machine (EPS/min) calculé côté client sur une fenêtre glissante de 60s. */
export function useTmThroughput(stats: SyncStats | null) {
  const samples = useRef<{ t: number; v: number }[]>([]);
  const [hist, setHist] = useState<number[]>([]);
  useEffect(() => {
    if (!stats) return;
    const now = Date.now();
    const v = stats.period_processed_total || 0;
    const arr = samples.current;
    if (arr.length && v < arr[arr.length - 1].v) arr.length = 0; // reset de session
    arr.push({ t: now, v });
    while (arr.length && now - arr[0].t > 60_000) arr.shift();
    const first = arr[0];
    const rate = arr.length > 1 && now - first.t > 4000 ? ((v - first.v) / (now - first.t)) * 60_000 : 0;
    setHist(h => [...h.slice(-89), Math.round(stats.is_running ? rate : 0)]);
  }, [stats]);
  return hist;
}

export function TimeMachinePanel({ stats, autopilotOn, onStop, onRelaunch, compact = false }: {
  stats: SyncStats | null; autopilotOn: boolean; onStop: () => void; onRelaunch: () => void; compact?: boolean;
}) {
  const isRunning = !!stats?.is_running;
  const tmProgress = stats && stats.period_total > 0 ? Math.min(100, Math.round((stats.period_offset / stats.period_total) * 100)) : 0;
  const hist = useTmThroughput(stats);
  const current = hist[hist.length - 1] || 0;

  return (
    <Card accent="cyan" glow={isRunning}>
      <CardHead
        icon={<IClock />}
        title="Time Machine"
        sub={stats?.time_machine_status || '—'}
        right={isRunning
          ? <button className={cx(s.btn, s.accRose)} onClick={onStop}><IStop /> Stopper</button>
          : !autopilotOn && <button className={cx(s.btn, s.btnSolid, s.accViolet)} onClick={onRelaunch}><IPlay /> Relancer l&apos;AutoPilot</button>}
      />

      <div className={cx(s.scanline, !isRunning && s.scanIdle)}>
        <div className={s.row} style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
          <div>
            <div className={s.tileLabel}>{isRunning ? 'Scan en cours' : 'En attente'}</div>
            <div className={s.bigPeriod}>{isRunning ? stats?.current_period : 'STANDBY'}</div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div className={s.tileLabel}>ETA période</div>
            <div className={s.mono} style={{ fontSize: '1.2rem', fontWeight: 800 }}>{isRunning ? stats?.eta : '—'}</div>
          </div>
        </div>
        <div style={{ marginTop: 14 }}><Progress value={isRunning ? tmProgress : 0} /></div>
        <div className={s.progressMeta}>
          <span>offset {(stats?.period_offset || 0).toLocaleString('fr-FR')}</span>
          <span>{tmProgress}%</span>
          <span>total {(stats?.period_total || 0).toLocaleString('fr-FR')}</span>
        </div>
      </div>

      <div className={s.tiles}>
        <div className={s.tile}><div className={s.tileLabel}>Nouveaux</div><div className={s.tileValue} style={{ color: '#6ee7b7' }}>+<Num value={stats?.period_inserted || 0} /></div></div>
        <div className={s.tile}><div className={s.tileLabel}>Mis à jour</div><div className={s.tileValue} style={{ color: '#93c5fd' }}>↻<Num value={stats?.period_updated || 0} /></div></div>
        <div className={s.tile}><div className={s.tileLabel}>Débit</div><div className={s.tileValue}><Num value={current} /><span style={{ fontSize: '.7rem', color: 'var(--txt-3)' }}> /min</span></div></div>
      </div>

      <div className={s.tileLabel} style={{ marginBottom: 4 }}>Débit d&apos;aspiration (3 dernières minutes)</div>
      <Sparkline data={hist} color="#22d3ee" />

      {!compact && <>
        <div className={s.tileLabel} style={{ margin: '14px 0 8px' }}>File d&apos;attente ({stats?.queued_periods?.length || 0})</div>
        <div className={s.chips}>
          {isRunning && stats?.current_period && <span className={cx(s.chip, s.chipActive)}>▶ {stats.current_period}</span>}
          {(stats?.queued_periods || []).slice(0, 24).map(p => <span key={p} className={s.chip}>{p}</span>)}
          {(stats?.queued_periods?.length || 0) > 24 && <span className={s.chip}>+{(stats?.queued_periods?.length || 0) - 24}</span>}
          {!isRunning && <span className={s.chip}>vide</span>}
        </div>
      </>}
    </Card>
  );
}

export function HealerPanel({ healer, mode, setMode, onStart, onStop }: {
  healer: HealerInfo | null; mode: string; setMode: (m: string) => void; onStart: () => void; onStop: () => void;
}) {
  const lots = (healer?.recent_lots || []).slice(0, 30).reverse();
  const maxLot = Math.max(1, ...lots.map(l => l.ok + l.not_found + l.failed));
  const totalSeen = (healer?.enriched || 0) + (healer?.not_found || 0) + (healer?.failed || 0);
  const okPct = totalSeen > 0 ? Math.round(((healer?.enriched || 0) / totalSeen) * 100) : 0;
  const nfPct = totalSeen > 0 ? Math.round(((healer?.not_found || 0) / totalSeen) * 100) : 0;

  let health: { cls: string; text: string };
  if (!healer) health = { cls: s.calloutWarn, text: 'Connexion au Healer…' };
  else if (!healer.running) health = { cls: s.calloutErr, text: 'Healer arrêté. L’AutoPilot le relance automatiquement s’il est activé.' };
  else if (healer.bytel_paused) health = { cls: s.calloutWarn, text: `Bytel en pause de protection — le Healer attend (${healer.throttle}).` };
  else if (healer.queue === 0) health = { cls: s.calloutOk, text: 'Tous les EPS ont leurs détails. Le Healer surveille les nouveaux arrivants.' };
  else if (healer.last_success_ago_s > 300 || (healer.last_success_ago_s < 0 && healer.uptime_s > 300)) health = { cls: s.calloutErr, text: `Aucun EPS enrichi depuis ${fmtDur(healer.last_success_ago_s < 0 ? healer.uptime_s : healer.last_success_ago_s)}. Dernière erreur : ${healer.last_error || 'aucune'}.` };
  else if (healer.failed > 0 && healer.failed / Math.max(1, totalSeen) > 0.2) health = { cls: s.calloutWarn, text: `Taux d’échec réseau élevé (${Math.round((healer.failed / totalSeen) * 100)}%). Les échecs deviennent fantômes et sont réessayés toutes les 6h.` };
  else if (nfPct > 50) health = { cls: s.calloutWarn, text: `${nfPct}% des EPS n’ont pas de détail chez Bytel, même après un 2e essai. Ils sont marqués fantômes et réessayés toutes les 6h (souvent des EPS trop récents côté Bytel).` };
  else health = { cls: s.calloutOk, text: `En bonne santé : dernier succès il y a ${fmtDur(healer.last_success_ago_s)}, ~${healer.rate_per_min}/min, ${okPct}% enrichis.` };

  return (
    <Card accent="emerald" glow={!!healer?.running}>
      <CardHead
        icon={<IShield />}
        title="Healer"
        sub={healer?.status || '—'}
        right={
          <div className={s.row}>
            <select className={s.select} value={mode} onChange={e => setMode(e.target.value)} disabled={healer?.running}>
              <option value="RADAR">Récents d&apos;abord</option>
              <option value="TIME_MACHINE">Anciens d&apos;abord</option>
            </select>
            {healer?.running
              ? <button className={cx(s.btn, s.accRose)} onClick={onStop}><IStop /> Stop</button>
              : <button className={cx(s.btn, s.btnSolid, s.accEmerald)} onClick={onStart}><IPlay /> Start</button>}
          </div>
        }
      />

      <div className={cx(s.callout, health.cls)} style={{ marginBottom: 16 }}>{health.text}</div>

      <div className={s.healerViz}>
        <Donut
          parts={[
            { value: healer?.enriched || 0, color: '#10b981', label: 'Enrichis' },
            { value: healer?.not_found || 0, color: '#f59e0b', label: 'Sans détail' },
            { value: healer?.failed || 0, color: '#f43f5e', label: 'Échecs' },
          ]}
          center={<div><div className={s.mono} style={{ fontSize: '1.6rem', fontWeight: 900 }}>{okPct}%</div><div className={s.coreLabel}>ENRICHIS</div></div>}
        />
        <div className={s.tiles} style={{ margin: 0 }}>
          <div className={s.tile}><div className={s.tileLabel}>Enrichis</div><div className={s.tileValue} style={{ color: '#6ee7b7' }}><Num value={healer?.enriched || 0} /></div></div>
          <div className={s.tile}><div className={s.tileLabel}>Sauvés au 2e essai</div><div className={s.tileValue} style={{ color: '#5eead4' }}><Num value={healer?.recovered || 0} /></div></div>
          <div className={s.tile}><div className={s.tileLabel}>Sans détail Bytel</div><div className={s.tileValue} style={{ color: '#fcd34d' }}><Num value={healer?.not_found || 0} /></div></div>
          <div className={s.tile}><div className={s.tileLabel}>Échecs réseau</div><div className={s.tileValue} style={{ color: '#fda4af' }}><Num value={healer?.failed || 0} /></div></div>
          <div className={s.tile}><div className={s.tileLabel}>En file</div><div className={s.tileValue}><Num value={healer?.queue || 0} /></div></div>
          <div className={s.tile}><div className={s.tileLabel}>Dernier succès</div><div className={s.tileValue}>{healer && healer.last_success_ago_s >= 0 ? fmtDur(healer.last_success_ago_s) : '—'}</div></div>
        </div>
      </div>

      <div className={s.row} style={{ justifyContent: 'space-between', margin: '18px 0 8px' }}>
        <span className={s.tileLabel}>30 derniers lots</span>
        <span className={s.coreLegend} style={{ margin: 0 }}>
          <span className={s.legendItem}><span className={s.legendSwatch} style={{ background: '#10b981' }} />OK</span>
          <span className={s.legendItem}><span className={s.legendSwatch} style={{ background: '#f59e0b' }} />Sans détail</span>
          <span className={s.legendItem}><span className={s.legendSwatch} style={{ background: '#f43f5e' }} />Échec</span>
        </span>
      </div>
      <div className={fx.sbars}>
        {lots.length === 0 && <span className={s.empty} style={{ padding: 10 }}>Pas encore de lot</span>}
        {lots.map((l, i) => {
          const tot = l.ok + l.not_found + l.failed;
          return (
            <div key={i} className={fx.sbar} style={{ height: `${Math.max(8, (tot / maxLot) * 100)}%`, animationDelay: `${i * 18}ms` }}
              title={`${l.at} — ${l.ok} OK, ${l.not_found} sans détail, ${l.failed} échecs (${(l.duration_ms / 1000).toFixed(1)}s) · file ${l.queue}`}>
              <div className={fx.segOk} style={{ flex: l.ok }} />
              <div className={fx.segNf} style={{ flex: l.not_found }} />
              <div className={fx.segFail} style={{ flex: l.failed }} />
            </div>
          );
        })}
      </div>
      <div className={s.tileLabel} style={{ margin: '14px 0 4px' }}>EPS enrichis par lot</div>
      <Sparkline data={lots.map(l => l.ok)} color="#10b981" height={44} />
    </Card>
  );
}
