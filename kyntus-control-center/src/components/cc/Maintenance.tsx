'use client';

import { useState } from 'react';
import s from './cc.module.css';
import { Card, cx } from './widgets';
import { IChevron, IRefresh, ISearch, ITrash, IWrench } from './icons';
import {
  cleanDuplicates, fixPeriods, purgePeriod, rescanPeriod, resetSync, retryFailedHeals, setManualOffset,
} from '../../services/api';

export default function Maintenance({ periods, isRunning, currentOffset, notify, refresh }: {
  periods: string[];
  isRunning: boolean;
  currentOffset: number;
  notify: (m: string) => void;
  refresh: () => void;
}) {
  const first = periods[0] || '';
  const [fixSel, setFixSel] = useState(first);
  const [rescanSel, setRescanSel] = useState(first);
  const [purgeSel, setPurgeSel] = useState(first);

  const run = async (confirmMsg: string | null, fn: () => Promise<unknown>) => {
    if (confirmMsg && !window.confirm(confirmMsg)) return;
    const res = await fn();
    if (typeof res === 'string') notify(res);
    refresh();
  };

  const PeriodSelect = ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <select className={cx(s.select, s.grow)} value={value || first} onChange={e => onChange(e.target.value)}>
      {periods.map(p => <option key={p} value={p}>{p}</option>)}
    </select>
  );

  return (
    <Card accent="amber">
      <details className={s.details}>
        <summary>
          <div className={s.cardHead} style={{ marginBottom: 0 }}>
            <div className={s.cardTitle}>
              <div className={s.cardIcon}><IWrench /></div>
              <div>
                <div>Atelier de maintenance</div>
                <div className={s.cardSub}>Outils manuels : rescan, purge, intrus, fantômes, offset</div>
              </div>
            </div>
            <span className={s.chev}><IChevron /></span>
          </div>
        </summary>

        <div className={s.toolsGrid}>
          <div className={s.tool}>
            <div className={s.toolTitle}>Smart Rescan</div>
            <div className={s.toolDesc}>Repasse une période depuis l&apos;offset 0 sans rien supprimer.</div>
            <div className={s.row}>
              <PeriodSelect value={rescanSel} onChange={setRescanSel} />
              <button className={s.btn} disabled={isRunning} title={isRunning ? 'Stoppez la Time Machine' : ''}
                onClick={() => run(`Smart Rescan de ${rescanSel || first} ?`, () => rescanPeriod(rescanSel || first))}>
                <ISearch /> Rescan
              </button>
            </div>
          </div>

          <div className={s.tool}>
            <div className={s.toolTitle}>Nettoyer les intrus</div>
            <div className={s.toolDesc}>Déplace les EPS rangés dans le mauvais mois vers leur vraie période.</div>
            <div className={s.row}>
              <PeriodSelect value={fixSel} onChange={setFixSel} />
              <button className={s.btn} onClick={() => run(`Vérifier ${fixSel || first} et déplacer les intrus ?`, () => fixPeriods(fixSel || first))}>
                <IRefresh /> Corriger
              </button>
            </div>
          </div>

          <div className={s.tool}>
            <div className={s.toolTitle}>EPS fantômes</div>
            <div className={s.toolDesc}>Remet les EPS « {'{}'} » dans la file du Healer (fait aussi automatiquement toutes les 6h).</div>
            <button className={s.btn} onClick={() => run('Remettre les fantômes en file ?', retryFailedHeals)}><IRefresh /> Réessayer</button>
          </div>

          <div className={s.tool}>
            <div className={s.toolTitle}>Doublons exacts</div>
            <div className={s.toolDesc}>Supprime les lignes en double (même id_intervention) en gardant la meilleure.</div>
            <button className={s.btn} onClick={() => run('Lancer le dédoublonnage ?', async () => { await cleanDuplicates(); return 'Dédoublonnage lancé en arrière-plan.'; })}>
              <IRefresh /> Dédoublonner
            </button>
          </div>

          <div className={s.tool}>
            <div className={s.toolTitle}>Forcer l&apos;offset</div>
            <div className={s.toolDesc}>Change l&apos;offset de la période en cours de scan (actuel : {currentOffset.toLocaleString('fr-FR')}).</div>
            <button className={s.btn} disabled={!isRunning} onClick={() => {
              const v = prompt(`Nouvel offset (actuel ${currentOffset}) :`, String(currentOffset));
              if (v === null) return;
              const n = parseInt(v, 10);
              if (isNaN(n) || n < 0) return notify('Valeur invalide.');
              run(null, async () => { await setManualOffset(n); return `Offset forcé à ${n}.`; });
            }}>
              <IWrench /> Forcer
            </button>
          </div>

          <div className={s.tool} style={{ borderColor: 'rgba(244,63,94,0.3)' }}>
            <div className={s.toolTitle} style={{ color: '#fda4af' }}>Zone dangereuse</div>
            <div className={s.toolDesc}>Purge = suppression définitive. L&apos;AutoPilot re-scannera la période à la prochaine passe.</div>
            <div className={s.row}>
              <PeriodSelect value={purgeSel} onChange={setPurgeSel} />
              <button className={cx(s.btn, s.accRose)} onClick={() => run(`Supprimer TOUTES les données de ${purgeSel || first} ?`, () => purgePeriod(purgeSel || first))}>
                <ITrash /> Purger
              </button>
            </div>
            <button className={cx(s.btn, s.btnSolid, s.accRose)}
              onClick={() => run('PURGE TOTALE (IONOS + Java) — irréversible. Confirmer ?', async () => { await resetSync(); return 'Purge totale lancée. AutoPilot désactivé.'; })}>
              <ITrash /> Reset &amp; purge totale
            </button>
          </div>
        </div>
      </details>
    </Card>
  );
}
