'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { fetchInterventions, cleanDuplicates, trimDatabase, exportInterventionsExcel, PageResponse } from '../../services/api';
import styles from './page.module.css';

export default function InterventionsPage() {
  const [data, setData] = useState<PageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  
  // 🚀 STATES DES FILTRES
  const [search, setSearch] = useState('');
  const [sourceFilter, setSourceFilter] = useState('ALL');
  const [period, setPeriod] = useState(''); 
  
  // State pour le Modal JSON
  const [selectedDetails, setSelectedDetails] = useState<string | null>(null);
  const [trimCount, setTrimCount] = useState<string>('711003');

  // 🚀 STATES POUR L'EXPORT
  const [isExportModalOpen, setIsExportModalOpen] = useState(false);
  const [exportSource, setExportSource] = useState('ALL');
  const [exportPeriod, setExportPeriod] = useState('');
  const [isExporting, setIsExporting] = useState(false);

  const availableYears = ['2026', '2025', '2024'];
  const availableMonths = ['M01', 'M02', 'M03', 'M04', 'M05', 'M06', 'M07', 'M08', 'M09', 'M10', 'M11', 'M12'];

  const loadData = async () => {
    setLoading(true);
    const result = await fetchInterventions(search, sourceFilter, period, page);
    setData(result);
    setLoading(false);
  };

  useEffect(() => {
    loadData();
  }, [page]);

  const handleApplyFilters = (e: React.FormEvent) => {
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

  // 🛡️ L'FIX HNA : Lancement de l'Export
  const handleExport = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsExporting(true);
    await exportInterventionsExcel(exportSource, exportPeriod);
    setIsExporting(false);
    setIsExportModalOpen(false);
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
    <div className={styles.pageWrapper}>
      <div className={styles.container}>
        <div className={styles.header}>
          <h1 className={styles.title}>Explorateur de Données</h1>
          <Link href="/" className={styles.backBtn}>← Retour au Dashboard</Link>
        </div>

        <div className={styles.toolsPanel}>
          <div style={{ display: 'flex', gap: '15px' }}>
            <button onClick={handleCleanDuplicates} className={styles.cleanBtn}>
              🧹 Nettoyer les doublons
            </button>
            <button onClick={() => setIsExportModalOpen(true)} className={styles.exportBtn}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
              Exporter (Excel)
            </button>
          </div>
          
          <div className={styles.trimBox}>
            <span style={{ fontSize: '0.875rem', fontWeight: 800, color: '#94a3b8' }}>Garder uniquement les premiers :</span>
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
              <option value="INCONNUE">INCONNUE (Anciennes données)</option>
            </select>
          </div>

          <div className={styles.filterGroup}>
            <label>Période (Mois/Année)</label>
            <select 
              value={period} 
              onChange={(e) => setPeriod(e.target.value)} 
              className={styles.selectInput}
            >
              <option value="">Toutes les périodes</option>
              {availableYears.map(year => (
                <optgroup key={year} label={`Année ${year}`}>
                  {availableMonths.map(month => (
                    <option key={`${year}-${month}`} value={`${year}-${month}`}>
                      {year} — {month}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>

          <button type="submit" className={styles.btnFilter}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"></polygon></svg>
            Filtrer
          </button>
        </form>

        <div className={styles.tableContainer}>
          {loading ? (
            <div style={{ padding: '60px', textAlign: 'center', color: '#38bdf8', fontWeight: '900', fontSize: '1.2rem', letterSpacing: '2px' }}>SCAN EN COURS...</div>
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
                  {data.content.map((inv, index) => (
                    <tr key={inv.id} className={styles.tableRow} style={{ animationDelay: `${index * 0.05}s` }}>
                      <td style={{ color: '#64748b', fontWeight: 800 }}>#{inv.id}</td>
                      <td style={{ fontWeight: '900', color: '#f8fafc' }}>{inv.id_intervention}</td>
                      <td><span className={`${styles.badge} ${styles.badgeState}`}>{inv.etat}</span></td>
                      <td>{inv.type_intervention || '-'}</td>
                      <td>{extractInfo(inv.detail_intervention, 'typePrestation')}</td>
                      <td style={{ color: '#94a3b8' }}>{inv.date_modification_etat}</td>
                      <td>
                        {inv.source_ingestion ? (
                          <span className={`${styles.sourceBadge} ${inv.source_ingestion === 'RADAR' ? styles.sourceRadar : styles.sourceTimeMachine}`}>
                            {inv.source_ingestion.replace('_', ' ')}
                          </span>
                        ) : (
                          <span className={`${styles.sourceBadge} ${styles.sourceUnknown}`}>INCONNUE</span>
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
                <span>Page {data.number + 1} sur {data.totalPages} <span style={{ color: '#38bdf8' }}>({data.totalElements} résultats)</span></span>
                <button disabled={data.number >= data.totalPages - 1} onClick={() => setPage(p => p + 1)} className={styles.pageBtn}>Suivant</button>
              </div>
            </>
          )}
        </div>

        {/* 🚀 MODAL EXPORT EXCEL */}
        {isExportModalOpen && (
          <div className={styles.modalOverlay} onClick={() => !isExporting && setIsExportModalOpen(false)}>
            <div className={styles.modalExportContent} onClick={e => e.stopPropagation()}>
              <div className={styles.modalHeader}>
                <h2>Générer un Export Excel</h2>
                <button onClick={() => !isExporting && setIsExportModalOpen(false)} className={styles.closeBtn}>×</button>
              </div>
              <form onSubmit={handleExport} className={styles.exportForm}>
                
                <div className={styles.filterGroup}>
                  <label>Source d'Ingestion</label>
                  <select value={exportSource} onChange={(e) => setExportSource(e.target.value)} className={styles.selectInput}>
                    <option value="ALL">Toutes les sources</option>
                    <option value="RADAR">RADAR (Flux Continu)</option>
                    <option value="TIME_MACHINE">TIME MACHINE (Historique)</option>
                    <option value="INCONNUE">INCONNUE (Anciennes données)</option>
                  </select>
                </div>

                <div className={styles.filterGroup}>
                  <label>Période (Mois/Année)</label>
                  <select value={exportPeriod} onChange={(e) => setExportPeriod(e.target.value)} className={styles.selectInput}>
                    <option value="">Toutes les périodes</option>
                    {availableYears.map(year => (
                      <optgroup key={year} label={`Année ${year}`}>
                        {availableMonths.map(month => (
                          <option key={`${year}-${month}`} value={`${year}-${month}`}>
                            {year} — {month}
                          </option>
                        ))}
                      </optgroup>
                    ))}
                  </select>
                </div>

                <div className={styles.modalActions}>
                  <button type="button" className={styles.btnCancel} onClick={() => setIsExportModalOpen(false)} disabled={isExporting}>Annuler</button>
                  <button type="submit" className={styles.exportBtn} disabled={isExporting}>
                    {isExporting ? 'Génération en cours...' : 'Télécharger le fichier'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {/* 🚀 MODAL JSON DETAILS */}
        {selectedDetails && (
          <div className={styles.modalOverlay} onClick={() => setSelectedDetails(null)}>
            <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
              <div className={styles.modalHeader}>
                <h2>CONSOLE JSON</h2>
                <button onClick={() => setSelectedDetails(null)} className={styles.closeBtn}>×</button>
              </div>
              <pre className={styles.jsonView}>{selectedDetails}</pre>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}