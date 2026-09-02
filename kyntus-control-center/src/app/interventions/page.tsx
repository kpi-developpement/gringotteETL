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
  
  // States pour le Modal et le Trim
  const [selectedDetails, setSelectedDetails] = useState<any | null>(null);
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

  const openDetails = (jsonString: string) => {
    try {
      const parsed = JSON.parse(jsonString);
      setSelectedDetails(parsed);
    } catch (e) {
      setSelectedDetails({ error: "Aucun détail disponible ou format invalide." });
    }
  };

  // Helper pour extraire des infos du JSON pour le tableau
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

      {/* 🚀 NOUVEAU : Outils de nettoyage */}
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
                    {/* 🚀 NOUVEAU : On extrait les détails directement dans le tableau */}
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

      {/* 🚀 NOUVEAU : Modal Designé Proprement */}
      {selectedDetails && (
        <div className={styles.modalOverlay} onClick={() => setSelectedDetails(null)}>
          <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
            <div className={styles.modalHeader}>
              <h2>Détails de l'Intervention</h2>
              <button onClick={() => setSelectedDetails(null)} className={styles.closeBtn}>×</button>
            </div>
            <div className={styles.modalBody}>
              {selectedDetails.error ? (
                <p style={{ color: 'red' }}>{selectedDetails.error}</p>
              ) : (
                <div className={styles.detailsGrid}>
                  {Object.entries(selectedDetails).map(([key, value]) => {
                    if (typeof value === 'object') return null; // On ignore les sous-objets complexes pour l'instant
                    return (
                      <div key={key} className={styles.detailItem}>
                        <span className={styles.detailKey}>{key}</span>
                        <span className={styles.detailValue}>{String(value)}</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}