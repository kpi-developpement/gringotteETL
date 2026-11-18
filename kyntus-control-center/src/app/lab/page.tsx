'use client';

import { useState } from 'react';
import s from '../../components/cc/cc.module.css';
import fx from '../../components/cc/fx.module.css';
import Shell from '../../components/cc/Shell';
import { useLive } from '../../components/cc/hooks';
import { Card, CardHead, cx, StatusDot } from '../../components/cc/widgets';
import { IBolt, IFlask, IPlay, ITerminal } from '../../components/cc/icons';

const PROTOCOLS = [
  {
    id: 1,
    title: 'Protocole 1 · Filtre Période',
    param: '?periode=2025-M09',
    desc: 'Teste le filtrage natif par code période mensuel sur le proxy IONOS → Bouygues.',
    accent: 'cyan' as const,
  },
  {
    id: 2,
    title: 'Protocole 2 · Date Intervention',
    param: '?dateIntervention_gte=...',
    desc: 'Teste le contournement de pagination par fenêtre temporelle sur la date d’intervention.',
    accent: 'emerald' as const,
  },
  {
    id: 3,
    title: 'Protocole 3 · Date Ouverture',
    param: '?dateOuverture=2025-09',
    desc: 'Teste la segmentation par mois d’ouverture du ticket chez Bytel.',
    accent: 'violet' as const,
  },
];

export default function ApiLabPage() {
  const { stats, healer } = useLive();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<Record<string, unknown> | null>(null);
  const [activeTest, setActiveTest] = useState<number | null>(null);

  const testEndpoint = async (type: number) => {
    setLoading(true);
    setActiveTest(type);
    setResult(null);
    try {
      const host = typeof window !== 'undefined' ? window.location.hostname : 'localhost';
      const res = await fetch(`http://${host}:8117/api/dashboard/lab/test-endpoint?type=${type}`);
      const data = await res.json();
      setResult(data);
    } catch (err: unknown) {
      setResult({ error: err instanceof Error ? err.message : 'Erreur réseau' });
    }
    setLoading(false);
  };

  return (
    <Shell stats={stats} healer={healer}>
      <div className={fx.modeBar}>
        <div>
          <div className={fx.modeTitle}>API Lab · Sondes Bytel</div>
          <div className={fx.modeSub}>Banc d’essai interactif des paramètres Bouygues via le relais IONOS</div>
        </div>
        <span className={s.pill}>
          <StatusDot state={loading ? 'warn' : result ? (result.http_code === 200 ? 'live' : 'err') : 'off'} />
          {loading ? 'Sonde en vol…' : result ? `HTTP ${String(result.http_code || 'ERR')}` : 'Prêt'}
        </span>
      </div>

      <div className={s.kpiGrid}>
        {PROTOCOLS.map(p => (
          <Card key={p.id} accent={p.accent} glow={activeTest === p.id}>
            <CardHead
              icon={<IFlask />}
              title={p.title}
              sub={p.desc}
            />
            <div className={s.mono} style={{
              padding: '10px 12px',
              borderRadius: 10,
              background: 'rgba(2, 4, 10, 0.75)',
              border: '1px solid var(--line-2)',
              color: 'var(--cyan)',
              fontSize: '0.78rem',
              marginBottom: 14,
            }}>
              {p.param}
            </div>
            <button
              onClick={() => testEndpoint(p.id)}
              disabled={loading}
              className={cx(s.btn, s.btnSolid, s.btnBlock, p.accent === 'cyan' ? s.accCyan : p.accent === 'emerald' ? s.accEmerald : s.accViolet)}
            >
              <IPlay />
              <span>{loading && activeTest === p.id ? 'Sonde en cours…' : `Lancer le Protocole ${p.id}`}</span>
            </button>
          </Card>
        ))}
      </div>

      <Card accent="cyan" glow={!!result}>
        <CardHead
          icon={<ITerminal />}
          title="Réponse Brute Bouygues API"
          sub={result?.url_testee ? String(result.url_testee) : 'Inspection du payload JSON retourné par la sonde'}
          right={result && (
            <span className={cx(s.badge, result.http_code === 200 ? s.bSuccess : s.bError)}>
              HTTP {String(result.http_code || 'ERROR')}
            </span>
          )}
        />

        {loading ? (
          <div className={s.empty} style={{ color: 'var(--cyan)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
            <IBolt />
            <span>Envoi de la sonde à IONOS puis Bouygues…</span>
          </div>
        ) : result ? (
          <div className={fx.term}>
            <div className={fx.termBar}>
              <span className={fx.termDot} style={{ background: '#f43f5e' }} />
              <span className={fx.termDot} style={{ background: '#f59e0b' }} />
              <span className={fx.termDot} style={{ background: '#10b981' }} />
              <span style={{ marginLeft: 8 }}>{String(result.url_testee || 'payload.json')}</span>
            </div>
            <pre className={fx.termBody} style={{ maxHeight: 520 }}>
              {JSON.stringify(result.response || result, null, 2)}
            </pre>
          </div>
        ) : (
          <div className={s.empty}>
            Sélectionnez l&apos;un des 3 protocoles ci-dessus pour inspecter le payload JSON en direct.
          </div>
        )}
      </Card>
    </Shell>
  );
}
