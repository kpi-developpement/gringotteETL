'use client';

import { useState } from 'react';
import s from '../components/cc/cc.module.css';
import Shell from '../components/cc/Shell';
import EventsTable from '../components/cc/EventsTable';
import Maintenance from '../components/cc/Maintenance';
import { PeriodMatrix } from '../components/cc/Periods';
import { useLive, useToast } from '../components/cc/hooks';
import { Card, CardHead, cx, Donut, fmtDur, Kpi, Num, Progress, Ring, StatusDot, Toast } from '../components/cc/widgets';
import { IBolt, IClock, IDb, IGrid, IPlay, IRadar, IRobot, IShield, IStop } from '../components/cc/icons';
import { setAutopilot, startHealer, stopHealer, stopSync, HealerInfo } from '../services/api';

export default function MissionControl() {
  const { stats, healer, periods, refreshAll } = useLive();
  const { toast, show } = useToast();
  const [selected, setSelected] = useState<string>('');
  const [healerMode, setHealerMode] = useState('RADAR');

  const ap = stats?.autopilot;
  const isRunning = !!stats?.is_running;
  const passPct = ap && ap.pass_total > 0 ? Math.round((ap.pass_done / ap.pass_total) * 100) : 0;
  const totalGap = periods.reduce((a, r) => a + (r.gap || 0), 0);
  const gapPeriods = periods.filter(r => r.gap > 0).length;
  const alerts24 = (stats?.event_counts_24h?.WARN || 0) + (stats?.event_counts_24h?.ERROR || 0);
  const tmProgress = stats && stats.period_total > 0 ? Math.min(100, Math.round((stats.period_offset / stats.period_total) * 100)) : 0;
  const periodNames = periods.map(p => p.period);

  const toggleAuto = async () => {
    const res = await setAutopilot(!ap?.enabled);
    show(res?.enabled ? 'AutoPilot activé — prise en main dans moins de 30s.' : 'AutoPilot désactivé.');
    refreshAll();
  };

  return (
    <Shell stats={stats} healer={healer}>
      {/* ================= HERO ================= */}
      <div className={s.heroGrid}>
        <Card accent="violet" glow>
          <CardHead
            icon={<IRobot />}
            title="AutoPilot"
            sub={ap?.status || 'Connexion…'}
            right={<button aria-label="AutoPilot" className={cx(s.toggle, ap?.enabled && s.toggleOn)} onClick={toggleAuto} />}
          />
          <div className={s.core}>
            <div className={cx(s.orbit, s.orbit1)}><span className={cx(s.satellite, !isRunning && s.satOff)} style={{ ['--sat' as string]: 'var(--cyan)' }} /></div>
            <div className={cx(s.orbit, s.orbit2)}><span className={cx(s.satellite, !healer?.running && s.satOff)} style={{ ['--sat' as string]: 'var(--emerald)' }} /></div>
            <div className={cx(s.orbit, s.orbit3)}><span className={cx(s.satellite, !ap?.enabled && s.satOff)} style={{ ['--sat' as string]: 'var(--violet)' }} /></div>
            <div className={s.coreRing}><Ring value={passPct} /></div>
            <div className={s.coreInner}>
              <div className={s.coreLabel}>PASSE</div>
              <div className={s.coreValue}>#{ap?.pass ?? '–'}</div>
              <div className={s.coreHint}>{ap ? `${ap.pass_done} / ${ap.pass_total} périodes · ${passPct}%` : '…'}</div>
            </div>
          </div>
          <div className={s.coreLegend}>
            <span className={s.legendItem}><span className={s.legendSwatch} style={{ background: 'var(--cyan)' }} />Time Machine</span>
            <span className={s.legendItem}><span className={s.legendSwatch} style={{ background: 'var(--emerald)' }} />Healer</span>
            <span className={s.legendItem}><span className={s.legendSwatch} style={{ background: 'var(--violet)' }} />AutoPilot</span>
          </div>
          <div className={s.progressMeta} style={{ marginTop: 14 }}>
            <span>Début passe : {ap?.pass_started || '—'}</span>
            <span>Tick : {ap?.last_tick || '—'}</span>
          </div>
        </Card>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div className={s.kpiGrid}>
            <Kpi label="Base locale" value={stats?.total_interventions_local || 0} icon={<IDb />} accent="cyan" sub="interventions stockées" delay={0} />
            <Kpi label="Périodes à jour" value={ap?.pass_done || 0} suffix={`/ ${ap?.pass_total || 0}`} icon={<IGrid />} accent="violet" sub={`Passe #${ap?.pass ?? '–'} en cours`} delay={60} />
            <Kpi label="EPS manquants" value={totalGap} icon={<IRadar />} accent={totalGap > 0 ? 'amber' : 'emerald'}
              sub={gapPeriods > 0 ? `${gapPeriods} période(s) avec trou` : 'Aucun trou détecté'} delay={120} />
            <Kpi label="Healer" value={healer?.rate_per_min || 0} suffix="/min" icon={<IShield />} accent="emerald"
              sub={healer ? `${healer.enriched.toLocaleString('fr-FR')} enrichis depuis démarrage` : '…'} delay={180} />
            <Kpi label="File Healer" value={healer?.queue || 0} icon={<IShield />} accent="cyan"
              sub={healer && healer.rate_per_min > 0 && healer.queue > 0 ? `vidée dans ~${fmtDur(Math.round((healer.queue / healer.rate_per_min) * 60))}` : 'EPS sans détails'} delay={240} />
            <Kpi label="Alertes 24h" value={alerts24} icon={<IBolt />} accent={alerts24 > 0 ? 'rose' : 'emerald'}
              sub={`${stats?.event_counts_24h?.ERROR || 0} erreurs · ${stats?.event_counts_24h?.WARN || 0} alertes`} delay={300} />
          </div>

          <Card accent="rose" style={{ padding: 16 }}>
            <div className={s.row} style={{ justifyContent: 'space-between' }}>
              <div className={s.row}>
                <StatusDot state={ap?.bytel_paused ? 'err' : 'live'} />
                <span style={{ fontWeight: 800, fontSize: '0.82rem' }}>Bouclier Bytel</span>
              </div>
              <span className={s.mono} style={{ fontSize: '0.76rem', color: ap?.bytel_paused ? '#fda4af' : 'var(--txt-2)' }}>{ap?.throttle || '—'}</span>
            </div>
          </Card>
        </div>
      </div>

      {/* ================= ENGINES ================= */}
      <div className={s.sectionTitle}>Moteurs</div>
      <div className={s.engines}>
        <Card accent="cyan" glow={isRunning}>
          <CardHead
            icon={<IClock />}
            title="Time Machine"
            sub={stats?.time_machine_status || '—'}
            right={isRunning
              ? <button className={cx(s.btn, s.accRose)} onClick={async () => { await stopSync(); show('Time Machine stoppée (AutoPilot désactivé).'); refreshAll(); }}><IStop /> Stopper</button>
              : !ap?.enabled && <button className={cx(s.btn, s.btnSolid, s.accViolet)} onClick={toggleAuto}><IPlay /> Relancer l&apos;AutoPilot</button>}
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
            <div className={s.tile}><div className={s.tileLabel}>Aspirés (session)</div><div className={s.tileValue}><Num value={stats?.period_processed_total || 0} /></div></div>
          </div>

          <div className={s.tileLabel} style={{ marginBottom: 8 }}>File d&apos;attente ({stats?.queued_periods?.length || 0})</div>
          <div className={s.chips}>
            {isRunning && stats?.current_period && <span className={cx(s.chip, s.chipActive)}>▶ {stats.current_period}</span>}
            {(stats?.queued_periods || []).slice(0, 24).map(p => <span key={p} className={s.chip}>{p}</span>)}
            {(stats?.queued_periods?.length || 0) > 24 && <span className={s.chip}>+{(stats?.queued_periods?.length || 0) - 24}</span>}
            {!isRunning && <span className={s.chip}>vide</span>}
          </div>
        </Card>

        <HealerPanel healer={healer} mode={healerMode} setMode={setHealerMode} notify={show} refresh={refreshAll} />
      </div>

      {/* ================= MATRIX ================= */}
      <div className={s.sectionTitle}>Couverture des périodes</div>
      <Card accent="emerald">
        <CardHead icon={<IGrid />} title="Matrice des périodes" sub="Cliquez une case pour filtrer le journal sur cette période" />
        <PeriodMatrix rows={periods} selected={selected} onSelect={p => setSelected(p === selected ? '' : p)} />
      </Card>

      {/* ================= JOURNAL ================= */}
      <div className={s.sectionTitle}>Journal</div>
      <EventsTable periods={periodNames} period={selected} onPeriodClear={() => setSelected('')} />

      <Maintenance periods={periodNames} isRunning={isRunning} currentOffset={stats?.period_offset || 0} notify={show} refresh={refreshAll} />

      <Toast msg={toast} />
    </Shell>
  );
}

function HealerPanel({ healer, mode, setMode, notify, refresh }: {
  healer: HealerInfo | null; mode: string; setMode: (m: string) => void; notify: (m: string) => void; refresh: () => void;
}) {
  const lots = (healer?.recent_lots || []).slice(0, 30).reverse();
  const maxLot = Math.max(1, ...lots.map(l => l.size));
  const totalSeen = (healer?.enriched || 0) + (healer?.not_found || 0) + (healer?.failed || 0);
  const okPct = totalSeen > 0 ? Math.round(((healer?.enriched || 0) / totalSeen) * 100) : 0;

  let health: { cls: string; text: string };
  if (!healer) health = { cls: s.calloutWarn, text: 'Connexion au Healer…' };
  else if (!healer.running) health = { cls: s.calloutErr, text: 'Healer arrêté. L’AutoPilot le relance automatiquement s’il est activé.' };
  else if (healer.bytel_paused) health = { cls: s.calloutWarn, text: `Bytel en pause de protection — le Healer attend (${healer.throttle}).` };
  else if (healer.queue === 0) health = { cls: s.calloutOk, text: 'Tous les EPS ont leurs détails. Le Healer surveille les nouveaux arrivants.' };
  else if (healer.last_success_ago_s > 300 || (healer.last_success_ago_s < 0 && healer.uptime_s > 300)) health = { cls: s.calloutErr, text: `Aucun EPS enrichi depuis ${fmtDur(healer.last_success_ago_s < 0 ? healer.uptime_s : healer.last_success_ago_s)}. Dernière erreur : ${healer.last_error || 'aucune'}.` };
  else if (healer.failed > 0 && healer.failed / Math.max(1, totalSeen) > 0.2) health = { cls: s.calloutWarn, text: `Taux d’échec réseau élevé (${Math.round((healer.failed / totalSeen) * 100)}%). Les échecs deviennent fantômes et sont réessayés toutes les 6h.` };
  else health = { cls: s.calloutOk, text: `En bonne santé : dernier succès il y a ${fmtDur(healer.last_success_ago_s)}, ~${healer.rate_per_min}/min, ${okPct}% enrichis avec succès.` };

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
              ? <button className={cx(s.btn, s.accRose)} onClick={async () => { await stopHealer(); notify('Healer stoppé (AutoPilot désactivé).'); refresh(); }}><IStop /> Stop</button>
              : <button className={cx(s.btn, s.btnSolid, s.accEmerald)} onClick={async () => { await startHealer(mode); notify('Healer démarré.'); refresh(); }}><IPlay /> Start</button>}
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
          center={<div><div className={s.mono} style={{ fontSize: '1.6rem', fontWeight: 900 }}>{okPct}%</div><div className={s.coreLabel}>SUCCÈS</div></div>}
        />
        <div>
          <div className={s.tiles} style={{ margin: 0 }}>
            <div className={s.tile}><div className={s.tileLabel}>Enrichis</div><div className={s.tileValue} style={{ color: '#6ee7b7' }}><Num value={healer?.enriched || 0} /></div></div>
            <div className={s.tile}><div className={s.tileLabel}>Sans détail Bytel</div><div className={s.tileValue} style={{ color: '#fcd34d' }}><Num value={healer?.not_found || 0} /></div></div>
            <div className={s.tile}><div className={s.tileLabel}>Échecs réseau</div><div className={s.tileValue} style={{ color: '#fda4af' }}><Num value={healer?.failed || 0} /></div></div>
            <div className={s.tile}><div className={s.tileLabel}>En file</div><div className={s.tileValue}><Num value={healer?.queue || 0} /></div></div>
            <div className={s.tile}><div className={s.tileLabel}>Fantômes</div><div className={s.tileValue}><Num value={healer?.phantoms || 0} /></div></div>
            <div className={s.tile}><div className={s.tileLabel}>Dernier succès</div><div className={s.tileValue}>{healer && healer.last_success_ago_s >= 0 ? fmtDur(healer.last_success_ago_s) : '—'}</div></div>
          </div>
        </div>
      </div>

      <div className={s.tileLabel} style={{ margin: '18px 0 8px' }}>30 derniers lots (vert = OK · orange = sans détail · rouge = échecs)</div>
      <div className={s.bars}>
        {lots.length === 0 && <span className={s.empty} style={{ padding: 10 }}>Pas encore de lot</span>}
        {lots.map((l, i) => (
          <div key={i} title={`${l.at} — ${l.ok} OK, ${l.not_found} sans détail, ${l.failed} échecs (${(l.duration_ms / 1000).toFixed(1)}s)`}
            className={cx(s.bar, l.failed > 0 ? s.barErr : l.not_found > l.ok ? s.barWarn : '')}
            style={{ height: `${Math.max(8, ((l.ok + l.not_found + l.failed) / maxLot) * 100)}%`, animationDelay: `${i * 20}ms` }} />
        ))}
      </div>
      <div style={{ marginTop: 12 }}>
        <Progress healer value={healer && healer.queue + healer.enriched > 0 ? (healer.enriched / (healer.queue + healer.enriched)) * 100 : 100} />
        <div className={s.progressMeta}><span>Progression session</span><span>{healer?.throttle}</span></div>
      </div>
    </Card>
  );
}
