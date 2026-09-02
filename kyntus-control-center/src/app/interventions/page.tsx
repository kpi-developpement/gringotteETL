'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { fetchInterventions, cleanDuplicates, trimDatabase, Intervention, PageResponse } from '../../services/api';
import styles from './page.module.css';

export default function InterventionsPage() {
  const [data, setData] = useState<PageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  
  // State pour le Modal
  const [selectedDetails, setSelectedDetails] = useState<string | null>(null);
  const [trimCount, setTrimCount] = useState<string>('711003');

  const loadData = async () => {
    setLoading(true);
    const result = await fetchInterventions(search, page);
    setData(result);
    setLoading(false);
  };

  useEffect(() => {
    loadData();
  }, [page]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    loadData();
  };

  const handleCleanDuplicates = async () => {
    if (confirm("Voulez-vous vraiment scanner et supprimer tous les doublons exacts ?")) {
      const msg = await cleanDuplicates();
      alert(msg);
      loadData();
    }
  };

  const handleTrimDatabase = async () => {
    const count = parseInt(trimCount);
    if (!isNaN(count) && count > 0) {
      if (confirm(`ATTENTION : Vous allez supprimer TOUTES les interventions après la ${count}ème. Continuer ?`)) {
        const msg = await trimDatabase(count);
        alert(msg);
        loadData();
      }
    }
  };

  // 🚀 L'FIX HNA : Rje3na l'JSON l'kham m-formati
  const openDetails = (jsonString: string) => {
    try {
      const parsed = JSON.parse(jsonString);
      setSelectedDetails(JSON.stringify(parsed, null, 2));
    } catch (e) {
      setSelectedDetails(jsonString || "Aucun détail disponible.");
    }
  };

  const extractInfo = (jsonString: string, key: string) => {
    try {
      const parsed = JSON.parse(jsonString);
      return parsed[key] || '-';
    } catch (e) { return '-'; }
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h1 className={styles.title}>Explorateur de Données</h1>
        <div style={{ display: 'flex', gap: '10px' }}>
          <Link href="/" className={styles.backBtn}>← Retour au Dashboard</Link>
        </div>
      </div>

      <div className={styles.toolsPanel}>
        <button onClick={handleCleanDuplicates} className={styles.cleanBtn}>
          🧹 Nettoyer les doublons exacts
        </button>
        <div className={styles.trimBox}>
          <span style={{ fontSize: '0.875rem', fontWeight: 600 }}>Garder uniquement les premiers :</span>
          <input 
            type="number" 
            value={trimCount} 
            onChange={(e) => setTrimCount(e.target.value)} 
            className={styles.trimInput}
          />
          <button onClick={handleTrimDatabase} className={styles.trimBtn}>✂️ Couper la base</button>
        </div>
      </div>

      <form onSubmit={handleSearch} className={styles.searchBar}>
        <input 
          type="text" 
          placeholder="Rechercher par ID EPS (ex: INC-12345)..." 
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className={styles.searchInput}
        />
        <button type="submit" className={styles.searchBtn}>Rechercher</button>
      </form>

      <div className={styles.tableContainer}>
        {loading ? (
          <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>Chargement...</div>
        ) : !data || data.content.length === 0 ? (
          <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>Aucune intervention trouvée.</div>
        ) : (
          <>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>ID Local</th>
                  <th>ID EPS</th>
                  <th>État</th>
                  <th>Type</th>
                  <th>Prestation</th>
                  <th>Nacelle</th>
                  <th>Date Modif</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((inv) => (
                  <tr key={inv.id}>
                    <td style={{ color: 'var(--text-muted)' }}>#{inv.id}</td>
                    <td style={{ fontWeight: 'bold', color: 'var(--kyntus-dark)' }}>{inv.id_intervention}</td>
                    <td><span className={styles.badge}>{inv.etat}</span></td>
                    <td>{inv.type_intervention || '-'}</td>
                    <td>{extractInfo(inv.detail_intervention, 'typePrestation')}</td>
                    <td>{extractInfo(inv.detail_intervention, 'presenceNacelle')}</td>
                    <td>{inv.date_modification_etat}</td>
                    <td>
                      <button onClick={() => openDetails(inv.detail_intervention)} className={styles.detailsBtn}>
                        Voir Détails
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className={styles.pagination}>
              <button disabled={data.number === 0} onClick={() => setPage(p => p - 1)} className={styles.pageBtn}>Précédent</button>
              <span>Page {data.number + 1} sur {data.totalPages} ({data.totalElements} résultats)</span>
              <button disabled={data.number >= data.totalPages - 1} onClick={() => setPage(p => p + 1)} className={styles.pageBtn}>Suivant</button>
            </div>
          </>
        )}
      </div>

      {/* 🚀 L'FIX HNA : Rje3na l'Modal l'khel li fih l'JSON kaml */}
      {selectedDetails && (
        <div className={styles.modalOverlay} onClick={() => setSelectedDetails(null)}>
          <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
            <div className={styles.modalHeader}>
              <h2>Détails Complets (JSON)</h2>
              <button onClick={() => setSelectedDetails(null)} className={styles.closeBtn}>×</button>
            </div>
            <pre className={styles.jsonView}>{selectedDetails}</pre>
          </div>
        </div>
      )}
    </div>
  );
}