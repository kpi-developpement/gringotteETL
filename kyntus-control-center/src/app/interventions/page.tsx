'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import styles from './page.module.css';

const API_URL = process.env.NEXT_PUBLIC_API_URL || 'http://10.10.10.25:8117/api/dashboard';

interface Intervention {
  id: number;
  id_intervention: string;
  environment: string;
  etat: string;
  type_intervention: string;
  date_modification_etat: string;
}

export default function InterventionsPage() {
  const [interventions, setInterventions] = useState<Intervention[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchInterventions = async () => {
      try {
        const res = await fetch(`${API_URL}/interventions`);
        if (res.ok) {
          const data = await res.json();
          setInterventions(data);
        }
      } catch (error) {
        console.error("Erreur fetch interventions", error);
      } finally {
        setLoading(false);
      }
    };

    fetchInterventions();
  }, []);

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h1 className={styles.title}>Dernières Interventions Sauvegardées</h1>
        <Link href="/" className={styles.backBtn}>
          ← Retour au Dashboard
        </Link>
      </div>

      <div className={styles.tableContainer}>
        {loading ? (
          <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>Chargement...</div>
        ) : interventions.length === 0 ? (
          <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>Aucune intervention trouvée en local.</div>
        ) : (
          <table className={styles.table}>
            <thead>
              <tr>
                <th>ID Interne</th>
                <th>ID Bouygues</th>
                <th>Environnement</th>
                <th>État</th>
                <th>Type</th>
                <th>Date Modif</th>
              </tr>
            </thead>
            <tbody>
              {interventions.map((inv) => (
                <tr key={inv.id}>
                  <td>#{inv.id}</td>
                  <td style={{ fontWeight: 'bold' }}>{inv.id_intervention}</td>
                  <td><span className={styles.badge}>{inv.environment}</span></td>
                  <td>{inv.etat}</td>
                  <td>{inv.type_intervention || '-'}</td>
                  <td>{inv.date_modification_etat}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}