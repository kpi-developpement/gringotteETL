'use client';

import { useState } from 'react';
import s from '../../components/cc/cc.module.css';
import Shell from '../../components/cc/Shell';
import EventsTable from '../../components/cc/EventsTable';
import { PeriodMatrix, PeriodsTable } from '../../components/cc/Periods';
import { useLive, useToast } from '../../components/cc/hooks';
import { Card, CardHead, cx, Kpi, Num, Progress, Toast } from '../../components/cc/widgets';
import { IClock, IClose, IGrid, IPlay, IPlus, IRadar, IStop, IDb } from '../../components/cc/icons';
import { startPeriodSync, stopSync } from '../../services/api';

export default function TimeMachinePage() {
  const { stats, healer, periods, refreshAll } = useLive();
  const { toast, show } = useToast();
  const [selected, setSelected] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [pick, setPick] = useState('');

  const isRunning = !!stats?.is_running;
  const ap = stats?.autopilot;
  const periodNames = periods.map(p => p.period);
  const tmProgress = stats && stats.period_total > 0 ? Math.min(100, Math.round((stats.period_offset / stats.period_total) * 100)) : 0;

  const totalRemote = periods.reduce((a, r) => a + (r.remote_total || 0), 0);
  const totalLocal = periods.reduce((a, r) => a + (r.local_distinct || 0), 0);
  const totalGap = periods.reduce((a, r) => a + (r.gap || 0), 0);
  const coverage = totalRemote > 0 ? Math.min(100, (totalLocal / totalRemote) * 100) : 0;
  const scansTotal = periods.reduce((a, r) => a + (r.scans || 0), 0);

  const startManual = async () => {
    if (picked.length === 0) return show('Ajoutez au moins une période.');
    if (ap?.enabled && !window.confirm('L’AutoPilot est actif : il reprendra la boucle après votre lancement manuel. Continuer ?')) return;
    await startPeriodSync(picked.join(','));
    show(`Scan manuel lancé : ${picked.join(', ')}`);
    setPicked([]);
    refreshAll();
  };

  const selRow = periods.find(p => p.period === selected);

  return (
    <Shell stats={stats} healer={healer}>
      <div className={s.kpiGrid} style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))' }}>
        <Kpi label="Couverture globale" value={coverage} decimals={1} suffix="%" icon={<IRadar />} accent="cyan" sub={`${totalLocal.toLocaleString('fr-FR')} / ${totalRemote.toLocaleString('fr-FR')} EPS`} />
        <Kpi label="EPS manquants" value={totalGap} icon={<IRadar />} accent={totalGap > 0 ? 'amber' : 'emerald'} sub={`${periods.filter(p => p.gap > 0).length} période(s) concernée(s)`} delay={60} />
        <Kpi label="Passe actuelle" value={ap?.pass || 0} icon={<IGrid />} accent="violet" sub={`${ap?.pass_done || 0} / ${ap?.pass_total || 0} périodes faites`} delay={120} />
        <Kpi label="Scans cumulés" value={scansTotal} icon={<IDb />} accent="emerald" sub={`sur ${periods.length} périodes`} delay={180} />
      </div>

      <div className={s.split}>
        <Card accent="cyan" glow={isRunning}>
          <CardHead
            icon={<IClock />}
            title="Scan en direct"
            sub={stats?.time_machine_status || '—'}
            right={isRunning && <button className={cx(s.btn, s.accRose)} onClick={async () => { await stopSync(); show('Time Machine stoppée (AutoPilot désactivé).'); refreshAll(); }}><IStop /> Stopper</button>}
          />
          <div className={cx(s.scanline, !isRunning && s.scanIdle)}>
            <div className={s.tileLabel}>{isRunning ? 'Période en cours' : 'Aucun scan'}</div>
            <div className={s.bigPeriod}>{isRunning ? stats?.current_period : 'STANDBY'}</div>
            <div style={{ marginTop: 14 }}><Progress value={isRunning ? tmProgress : 0} /></div>
            <div className={s.progressMeta}>
              <span>{(stats?.period_offset || 0).toLocaleString('fr-FR')} / {(stats?.period_total || 0).toLocaleString('fr-FR')}</span>
              <span>ETA {isRunning ? stats?.eta : '—'}</span>
            </div>
          </div>
          <div className={s.tiles}>
            <div className={s.tile}><div className={s.tileLabel}>Nouveaux</div><div className={s.tileValue} style={{ color: '#6ee7b7' }}>+<Num value={stats?.period_inserted || 0} /></div></div>
            <div className={s.tile}><div className={s.tileLabel}>Mis à jour</div><div className={s.tileValue} style={{ color: '#93c5fd' }}>↻<Num value={stats?.period_updated || 0} /></div></div>
            <div className={s.tile}><div className={s.tileLabel}>En file</div><div className={s.tileValue}>{stats?.queued_periods?.length || 0}</div></div>
          </div>
          <div className={s.chips}>
            {(stats?.queued_periods || []).slice(0, 30).map(p => <span key={p} className={s.chip}>{p}</span>)}
          </div>
        </Card>

        <Card accent="violet">
          <CardHead icon={<IPlay />} title="Scan manuel" sub="Forcer un passage immédiat sur des périodes précises" />
          <div className={s.row}>
            <select className={cx(s.select, s.grow)} value={pick || periodNames[0] || ''} onChange={e => setPick(e.target.value)} disabled={isRunning}>
              {periodNames.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
            <button className={s.btn} disabled={isRunning} onClick={() => {
              const p = pick || periodNames[0];
              if (p && !picked.includes(p)) setPicked([...picked, p]);
            }}><IPlus /> Ajouter</button>
          </div>
          <div className={s.chips} style={{ margin: '14px 0', minHeight: 32 }}>
            {picked.length === 0 && <span style={{ color: 'var(--txt-3)', fontSize: '0.78rem' }}>Aucune période sélectionnée</span>}
            {picked.map(p => (
              <span key={p} className={cx(s.chip, s.chipActive)} style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                {p}
                <button onClick={() => setPicked(picked.filter(x => x !== p))} style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', display: 'flex' }}><IClose /></button>
              </span>
            ))}
          </div>
          <button className={cx(s.btn, s.btnSolid, s.accViolet, s.btnBlock)} disabled={isRunning || picked.length === 0} onClick={startManual}>
            <IPlay /> Lancer le scan
          </button>
          <p style={{ color: 'var(--txt-3)', fontSize: '0.74rem', marginTop: 12, lineHeight: 1.5 }}>
            {isRunning
              ? 'Un scan est déjà en cours. Stoppez-le pour lancer un scan manuel.'
              : 'Chaque période reprend à son offset sauvegardé. L’AutoPilot reprend sa boucle infinie dès que la file est vide.'}
          </p>

          {selRow && (
            <div className={s.callout} style={{ marginTop: 14, borderColor: 'rgba(34,211,238,0.3)', background: 'rgba(34,211,238,0.06)', color: 'var(--txt)' }}>
              <div>
                <b className={s.mono}>{selRow.period}</b> — base {selRow.local_distinct.toLocaleString('fr-FR')} / Bytel {selRow.remote_total ? selRow.remote_total.toLocaleString('fr-FR') : '?'}
                {selRow.gap > 0 && <span style={{ color: '#fcd34d' }}> · {selRow.gap.toLocaleString('fr-FR')} manquants</span>}
                <br />Scans : {selRow.scans} · dernier {selRow.last_scan || 'jamais'} · {selRow.missing_details} sans détails · {selRow.phantoms} fantômes
              </div>
            </div>
          )}
        </Card>
      </div>

      <Card accent="emerald">
        <CardHead icon={<IGrid />} title="Matrice des périodes" sub="Cliquez une case pour voir son historique" />
        <PeriodMatrix rows={periods} selected={selected} onSelect={p => setSelected(p === selected ? '' : p)} />
      </Card>

      <Card accent="cyan">
        <CardHead icon={<IDb />} title="État détaillé par période" sub="Base locale vs Bytel, trous, détails manquants, historique des passes" />
        <PeriodsTable rows={periods} selected={selected} onSelect={p => setSelected(p === selected ? '' : p)} />
      </Card>

      <EventsTable title="Historique Time Machine" fixedSource="TIME_MACHINE" periods={periodNames} period={selected} onPeriodClear={() => setSelected('')} />

      <Toast msg={toast} />
    </Shell>
  );
}
