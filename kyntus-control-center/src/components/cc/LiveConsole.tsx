'use client';

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import s from './cc.module.css';
import fx from './fx.module.css';
import { Card, CardHead, cx, Num, StatusDot } from './widgets';
import { ITerminal } from './icons';
import { ActivityLine, fetchActivity } from '../../services/api';
import { copyText } from './clipboard';

const MAX = 2500;
const TAG: Record<string, [string, string]> = {
  TM: [fx.tTm, 'TMACH'], HEALER: [fx.tHealer, 'HEAL '], AUTO: [fx.tAuto, 'AUTO '], BYTEL: [fx.tBytel, 'BYTEL'], SYS: [fx.tSys, 'SYS  '],
};
const LVL: Record<string, string> = { OK: fx.tOk, WARN: fx.tWarn, ERROR: fx.tErr, INFO: fx.tInfo };

/** Un terminal (auto-scroll façon tail -f, bouton "nouvelles lignes" si on remonte). */
function Term({ title, lines, height, dot }: { title: string; lines: ActivityLine[]; height: number; dot: 'live' | 'warn' | 'err' | 'off' }) {
  const ref = useRef<HTMLDivElement>(null);
  const [stick, setStick] = useState(true);
  const [unseen, setUnseen] = useState(0);
  const prevLen = useRef(lines.length);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const added = lines.length - prevLen.current;
    prevLen.current = lines.length;
    if (stick) el.scrollTop = el.scrollHeight;
    else if (added > 0) setUnseen(u => u + added);
  }, [lines, stick]);

  const onScroll = () => {
    const el = ref.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    setStick(atBottom);
    if (atBottom) setUnseen(0);
  };

  return (
    <div className={fx.term} style={{ position: 'relative' }}>
      <div className={fx.termBar}>
        <span className={fx.termDot} style={{ background: '#f43f5e' }} />
        <span className={fx.termDot} style={{ background: '#f59e0b' }} />
        <span className={fx.termDot} style={{ background: '#10b981' }} />
        <span style={{ marginLeft: 8, flex: 1 }}>{title}</span>
        <StatusDot state={dot} />
      </div>
      <div ref={ref} onScroll={onScroll} className={fx.termBody} style={{ height, maxHeight: height }}>
        {lines.length === 0 && <span className={fx.tTime}>en attente d’activité…{'\n'}</span>}
        {lines.map(l => {
          const [tc, tl] = TAG[l.engine] || [fx.tSys, l.engine];
          return (
            <div key={l.seq} className={cx(fx.tLine, l.level === 'ERROR' && fx.tLineErr, l.level === 'WARN' && fx.tLineWarn)}>
              <span className={fx.tTime}>{l.at}</span>{'  '}
              <span className={tc}>{tl}</span>{'  '}
              <span className={LVL[l.level] || fx.tInfo}>{(l.level === 'OK' ? '✓' : l.level === 'ERROR' ? '✗' : l.level === 'WARN' ? '!' : '·')}</span>{'  '}
              <span>{l.msg}</span>
            </div>
          );
        })}
        <span className={fx.cursor} />
      </div>
      {!stick && unseen > 0 && (
        <button className={fx.newLines} onClick={() => { setStick(true); setUnseen(0); }}>↓ {unseen} nouvelle(s) ligne(s)</button>
      )}
    </div>
  );
}

export default function LiveConsole({ mode = 'merged', height = 420 }: { mode?: 'merged' | 'split'; height?: number }) {
  const [lines, setLines] = useState<ActivityLine[]>([]);
  const [paused, setPaused] = useState(false);
  const [errorsOnly, setErrorsOnly] = useState(false);
  const [copied, setCopied] = useState(false);
  const lastSeq = useRef(0);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  useEffect(() => {
    let alive = true;
    const tick = async () => {
      if (pausedRef.current || (typeof document !== 'undefined' && document.hidden)) return;
      const r = await fetchActivity(lastSeq.current, undefined, lastSeq.current === 0 ? 600 : 500);
      if (!alive || !r) return;
      if (r.last_seq < lastSeq.current) lastSeq.current = 0; // backend redémarré
      if (r.lines.length) {
        lastSeq.current = r.lines[r.lines.length - 1].seq;
        setLines(prev => {
          const next = prev.concat(r.lines);
          return next.length > MAX ? next.slice(next.length - MAX) : next;
        });
      }
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => { alive = false; clearInterval(id); };
  }, []);

  const visible = useMemo(() => errorsOnly ? lines.filter(l => l.level === 'ERROR' || l.level === 'WARN') : lines, [lines, errorsOnly]);
  const tmLines = useMemo(() => visible.filter(l => l.engine === 'TM' || l.engine === 'AUTO' || l.engine === 'BYTEL'), [visible]);
  const healLines = useMemo(() => visible.filter(l => l.engine === 'HEALER' || l.engine === 'SYS' || l.engine === 'BYTEL'), [visible]);

  // Statistiques de la dernière minute (horodatage HH:mm:ss.SSS du jour)
  const stats = useMemo(() => {
    const now = new Date();
    const nowSec = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds();
    const recent = lines.filter(l => {
      const [h, m, rest] = l.at.split(':');
      const sec = +h * 3600 + +m * 60 + parseInt(rest, 10);
      const d = nowSec - sec;
      return d >= 0 && d <= 60;
    });
    const pages = recent.filter(l => l.engine === 'TM' && l.level === 'OK' && l.msg.includes(' page ')).length;
    const eps = recent.filter(l => l.engine === 'TM' && l.level === 'OK').reduce((a, l) => a + (+(l.msg.match(/· (\d+) EPS reçus/)?.[1] || 0)), 0);
    const healed = recent.filter(l => l.engine === 'HEALER').reduce((a, l) => a + (+(l.msg.match(/· (\d+) détails ✓/)?.[1] || 0)), 0);
    const tmErr = recent.filter(l => (l.engine === 'TM' || l.engine === 'BYTEL') && l.level === 'ERROR').length;
    const healErr = recent.filter(l => l.engine === 'HEALER' && l.level === 'ERROR').length;
    return { pages, eps, healed, tmErr, healErr };
  }, [lines]);

  const copy = async () => {
    const txt = visible.map(l => `${l.at}\t${l.engine}\t${l.level}\t${l.msg}`).join('\n');
    if (await copyText(txt)) { setCopied(true); setTimeout(() => setCopied(false), 1500); }
  };

  return (
    <Card accent="cyan" glow={!paused}>
      <CardHead
        icon={<ITerminal />}
        title="Console live — Time Machine & Healer"
        sub="Chaque page aspirée, chaque lot enrichi, chaque erreur — en direct (mémoire, 2 500 dernières lignes)"
        right={
          <div className={s.row}>
            <button className={cx(s.filterChip, !paused && s.filterOn)} onClick={() => setPaused(!paused)}>{paused ? '❚❚ PAUSE' : '● LIVE'}</button>
            <button className={cx(s.filterChip, errorsOnly && s.filterOn)} onClick={() => setErrorsOnly(!errorsOnly)}>Erreurs seulement</button>
            <button className={cx(s.btn, fx.copyBtn, copied && fx.copied)} onClick={copy}>{copied ? 'Copié ✓' : 'Copier'}</button>
            <button className={s.btn} onClick={() => setLines([])}>Vider</button>
          </div>
        }
      />
      <div className={fx.consoleStats}>
        <div><span>Pages / min</span><b><Num value={stats.pages} /></b></div>
        <div><span>EPS aspirés / min</span><b><Num value={stats.eps} /></b></div>
        <div><span>Détails ✓ / min</span><b style={{ color: '#6ee7b7' }}><Num value={stats.healed} /></b></div>
        <div className={stats.tmErr ? fx.statErr : ''}><span>Erreurs TM / min</span><b><Num value={stats.tmErr} /></b></div>
        <div className={stats.healErr ? fx.statErr : ''}><span>Erreurs Healer / min</span><b><Num value={stats.healErr} /></b></div>
      </div>
      {mode === 'split' ? (
        <div className={fx.dual} style={{ gap: 14 }}>
          <Term title="time-machine.log" lines={tmLines} height={height} dot={stats.tmErr ? 'err' : tmLines.length ? 'live' : 'off'} />
          <Term title="healer.log" lines={healLines} height={height} dot={stats.healErr ? 'err' : healLines.length ? 'live' : 'off'} />
        </div>
      ) : (
        <Term title="gringotts@sync:~$ tail -f engine.log" lines={visible} height={height} dot={stats.tmErr + stats.healErr ? 'err' : visible.length ? 'live' : 'off'} />
      )}
    </Card>
  );
}
