'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { fetchInterventions, cleanDuplicates, Intervention, PageResponse } from '../../services/api';
import styles from './page.module.css';

export default function InterventionsPage() {
  const [data, setData] = useState<PageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  
  // State pour le Modal
  const [selectedDetails, setSelectedDetails] = useState<string | null>(null);

  const loadData = async () => {
    setLoading(true);
    const result = await fetchInterventions(search, page);
    setData(result);
    setLoading(false);
  };

  useEffect(() => {
    loadData();
  }, [page]); // Recharge quand la page change

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0); // Reset à la page 0 quand on cherche
    loadData();
  };

  const handleCleanDuplicates = async () => {
    if (confirm("Voulez-vous vraiment scanner et supprimer tous les doublons dans la base locale ?")) {
      const msg = await cleanDuplicates();
      alert(msg);
      loadData();
    }
  };

  const openDetails = (jsonString: string) => {
    try {
      const parsed = JSON.parse(jsonString);
      setSelectedDetails(JSON.stringify(parsed, null, 2));
    } catch (e) {
      setSelectedDetails(jsonString || "Aucun détail disponible.");
    }
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h1 className={styles.title}>Explorateur de Données</h1>
        <div style={{ display: 'flex', gap: '10px' }}>
          <button onClick={handleCleanDuplicates} className={styles.cleanBtn}>
            🧹 Nettoyer les doublons
          </button>
          <Link href="/" className={styles.backBtn}>
            ← Retour au Dashboard
          </Link>
        </div>
      </div>

      {/* Barre de recherche */}
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
                  <th>ID EPS (Bouygues)</th>
                  <th>Environnement</th>
                  <th>État</th>
                  <th>Type</th>
                  <th>Date Modif</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((inv) => (
                  <tr key={inv.id}>
                    <td>#{inv.id}</td>
                    <td style={{ fontWeight: 'bold' }}>{inv.id_intervention}</td>
                    <td><span className={styles.badge}>{inv.environment}</span></td>
                    <td>{inv.etat}</td>
                    <td>{inv.type_intervention || '-'}</td>
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

            {/* Pagination */}
            <div className={styles.pagination}>
              <button 
                disabled={data.number === 0} 
                onClick={() => setPage(p => p - 1)}
                className={styles.pageBtn}
              >
                Précédent
              </button>
              <span>Page {data.number + 1} sur {data.totalPages} ({data.totalElements} résultats)</span>
              <button 
                disabled={data.number >= data.totalPages - 1} 
                onClick={() => setPage(p => p + 1)}
                className={styles.pageBtn}
              >
                Suivant
              </button>
            </div>
          </>
        )}
      </div>

      {/* Modal pour afficher les détails JSON */}
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