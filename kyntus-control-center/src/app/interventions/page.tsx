'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { fetchInterventions, cleanDuplicates, trimDatabase, PageResponse } from '../../services/api';
import styles from './page.module.css';

export default function InterventionsPage() {
  const [data, setData] = useState<PageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  
  // 🚀 STATES DES FILTRES
  const [search, setSearch] = useState('');
  const [sourceFilter, setSourceFilter] = useState('ALL');
  // 🛡️ L'FIX HNA : Un seul champ pour le mois et l'année
  const [period, setPeriod] = useState(''); 
  
  // State pour le Modal
  const [selectedDetails, setSelectedDetails] = useState<string | null>(null);
  const [trimCount, setTrimCount] = useState<string>('711003');

  const loadData = async () => {
    setLoading(true);
    const result = await fetchInterventions(search, sourceFilter, period, page);
    setData(result);
    setLoading(false);
  };

  useEffect(() => {
    loadData();
  }, [page]); // On recharge quand la page change

  const handleApplyFilters = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0); // Retour à la première page lors d'un nouveau filtre
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
        <Link href="/" className={styles.backBtn}>← Retour au Dashboard</Link>
      </div>

      <div className={styles.toolsPanel}>
        <button onClick={handleCleanDuplicates} className={styles.cleanBtn}>
          🧹 Nettoyer les doublons exacts
        </button>
        <div className={styles.trimBox}>
          <span style={{ fontSize: '0.875rem', fontWeight: 600, color: '#475569' }}>Garder uniquement les premiers :</span>
          <input 
            type="number" 
            value={trimCount} 
            onChange={(e) => setTrimCount(e.target.value)} 
            className={styles.trimInput}
          />
          <button onClick={handleTrimDatabase} className={styles.trimBtn}>✂️ Couper la base</button>
        </div>
      </div>

      {/* 🚀 MOTEUR DE FILTRAGE */}
      <form onSubmit={handleApplyFilters} className={styles.filtersWrapper}>
        <div className={styles.filterGroup}>
          <label>Recherche EPS</label>
          <input 
            type="text" 
            placeholder="Ex: INC-12345..." 
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className={styles.searchInput}
          />
        </div>

        <div className={styles.filterGroup}>
          <label>Source d'Ingestion</label>
          <select 
            value={sourceFilter} 
            onChange={(e) => setSourceFilter(e.target.value)} 
            className={styles.selectInput}
          >
            <option value="ALL">Toutes les sources</option>
            <option value="RADAR">RADAR (Flux Continu)</option>
            <option value="TIME_MACHINE">TIME MACHINE (Historique)</option>
          </select>
        </div>

        {/* 🛡️ L'FIX HNA : Input type="month" */}
        <div className={styles.filterGroup}>
          <label>Période (Mois/Année)</label>
          <input 
            type="month" 
            className={styles.dateInput} 
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
          />
        </div>

        <button type="submit" className={styles.btnFilter}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"></polygon></svg>
          Filtrer
        </button>
      </form>

      <div className={styles.tableContainer}>
        {loading ? (
          <div style={{ padding: '60px', textAlign: 'center', color: '#64748b', fontWeight: 'bold' }}>Recherche dans la base de données...</div>
        ) : !data || data.content.length === 0 ? (
          <div style={{ padding: '60px', textAlign: 'center', color: '#64748b', fontWeight: 'bold' }}>Aucune intervention trouvée pour ces critères.</div>
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
                  <th>Date Modif</th>
                  <th>Source</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((inv) => (
                  <tr key={inv.id}>
                    <td style={{ color: '#94a3b8' }}>#{inv.id}</td>
                    <td style={{ fontWeight: '800', color: '#0f172a' }}>{inv.id_intervention}</td>
                    <td><span className={`${styles.badge} ${styles.badgeState}`}>{inv.etat}</span></td>
                    <td>{inv.type_intervention || '-'}</td>
                    <td>{extractInfo(inv.detail_intervention, 'typePrestation')}</td>
                    <td>{inv.date_modification_etat}</td>
                    <td>
                      {inv.source_ingestion ? (
                        <span className={`${styles.sourceBadge} ${inv.source_ingestion === 'RADAR' ? styles.sourceRadar : styles.sourceTimeMachine}`}>
                          {inv.source_ingestion.replace('_', ' ')}
                        </span>
                      ) : (
                        <span style={{ color: '#94a3b8', fontSize: '12px', fontWeight: 'bold' }}>INCONNUE</span>
                      )}
                    </td>
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