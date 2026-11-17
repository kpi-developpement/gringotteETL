'use client';

import Link from 'next/link';
import { useState } from 'react';
import s from '../components/cc/cc.module.css';
import Shell from '../components/cc/Shell';
import EventsTable from '../components/cc/EventsTable';
import Maintenance from '../components/cc/Maintenance';
import Pipeline from '../components/cc/Pipeline';
import { PeriodMatrix } from '../components/cc/Periods';
import { useLive, useToast } from '../components/cc/hooks';
import { Card, CardHead, cx, fmtDur, Kpi, Ring, StatusDot, Toast } from '../components/cc/widgets';
import { IBolt, IClock, IDb, IGrid, IRadar, IRobot, IShield } from '../components/cc/icons';
import { setAutopilot } from '../services/api';

export default function MissionControl() {
  const { stats, healer, periods, refreshAll } = useLive();
  const { toast, show } = useToast();
  const [selected, setSelected] = useState<string>('');

  const ap = stats?.autopilot;
  const isRunning = !!stats?.is_running;
  const passPct = ap && ap.pass_total > 0 ? Math.round((ap.pass_done / ap.pass_total) * 100) : 0;
  const totalGap = periods.reduce((a, r) => a + (r.gap || 0), 0);
  const gapPeriods = periods.filter(r => r.gap > 0).length;
  const alerts24 = (stats?.event_counts_24h?.WARN || 0) + (stats?.event_counts_24h?.ERROR || 0);
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
              sub={gapPeriods > 0 ? `${gapPeriods} période(s) · constat après scan complet` : 'Aucun trou détecté'} delay={120} />
            <Kpi label="Healer" value={healer?.rate_per_min || 0} suffix="/min" icon={<IShield />} accent="emerald"
              sub={healer ? `${healer.enriched.toLocaleString('fr-FR')} enrichis · ${(healer.recovered || 0).toLocaleString('fr-FR')} sauvés au 2e essai` : '…'} delay={180} />
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

      {/* ================= FLUX ================= */}
      <div className={s.sectionTitle}>Flux en direct</div>
      <Card accent="cyan" glow={isRunning || !!healer?.running}>
        <CardHead
          icon={<IBolt />}
          title="Pipeline de synchronisation"
          sub="Bytel → Time Machine → Base → Healer → EPS enrichis"
          right={<Link href="/time-machine" className={cx(s.btn, s.btnSolid, s.accViolet)}><IClock /> Salle des moteurs</Link>}
        />
        <Pipeline stats={stats} healer={healer} periods={periods} />
      </Card>

      {/* ================= MATRIX ================= */}
      <div className={s.sectionTitle}>Couverture des périodes</div>
      <Card accent="emerald">
        <CardHead icon={<IGrid />} title="Matrice des périodes" sub="Cliquez une case pour filtrer le journal sur cette période" />
        <PeriodMatrix rows={periods} selected={selected} onSelect={p => setSelected(p === selected ? '' : p)} />
      </Card>

      {/* ================= JOURNAL ================= */}
      <div className={s.sectionTitle}>Journal</div>
      <EventsTable periods={periodNames} period={selected} onPeriodClear={() => setSelected('')} storageKey="home" />

      <Maintenance periods={periodNames} isRunning={isRunning} currentOffset={stats?.period_offset || 0} notify={show} refresh={refreshAll} />

      <Toast msg={toast} />
    </Shell>
  );
}
