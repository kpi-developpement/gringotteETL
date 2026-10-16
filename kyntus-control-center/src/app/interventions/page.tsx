'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import {
  fetchInterventions,
  cleanDuplicates,
  trimDatabase,
  exportInterventionsExcel,
  PageResponse
} from '../../services/api';
import styles from './page.module.css';

/* ========================================================================== */
/* PURE VECTOR SVG ICONS (ZERO EMOJI SYSTEM)                                  */
/* ========================================================================== */
const IconLayers = () => <svg width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>;
const IconArrowLeft = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.4" viewBox="0 0 24 24"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>;
const IconTrashClean = () => <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>;
const IconDownload = () => <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>;
const IconScissors = () => <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"><circle cx="6" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><line x1="20" y1="4" x2="8.12" y2="15.88"/><line x1="14.47" y1="14.48" x2="20" y2="20"/><line x1="8.12" y1="8.12" x2="12" y2="12"/></svg>;
const IconFilter = () => <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/></svg>;
const IconCode = () => <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>;
const IconClose = () => <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>;
const IconPlus = () => <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>;

export default function InterventionsPage() {
  const [data, setData] = useState<PageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);

  const [search, setSearch] = useState('');
  const [sourceFilter, setSourceFilter] = useState('ALL');
  const [period, setPeriod] = useState('');

  const [selectedDetails, setSelectedDetails] = useState<string | null>(null);
  const [trimCount, setTrimCount] = useState<string>('711003');

  const [isExportModalOpen, setIsExportModalOpen] = useState(false);
  const [exportSource, setExportSource] = useState('ALL');
  const [exportType, setExportType] = useState('ALL');

  const [exportPeriods, setExportPeriods] = useState<string[]>([]);
  const [exportPeriodInput, setExportPeriodInput] = useState('2026_M01');

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

  const addExportPeriod = () => {
    if (!exportPeriods.includes(exportPeriodInput)) {
      setExportPeriods([...exportPeriods, exportPeriodInput]);
    }
  };

  const handleExport = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsExporting(true);
    const joinedPeriods = exportPeriods.join(',');
    await exportInterventionsExcel(exportSource, joinedPeriods, exportType);
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
          <div className={styles.titleGroup}>
            <div className={styles.titleIcon}>
              <IconLayers />
            </div>
            <h1 className={styles.title}>EXPLORATEUR DE DONNÉES EPS</h1>
          </div>
          <Link href="/" className={styles.backBtn}>
            <IconArrowLeft />
            <span>Retour au Dashboard</span>
          </Link>
        </div>

        <div className={styles.toolsPanel}>
          <div style={{ display: 'flex', gap: '14px', flexWrap: 'wrap' }}>
            <button onClick={handleCleanDuplicates} className={styles.cleanBtn}>
              <IconTrashClean />
              <span>Nettoyer les doublons</span>
            </button>
            <button onClick={() => setIsExportModalOpen(true)} className={styles.exportBtn}>
              <IconDownload />
              <span>Exporter (Excel)</span>
            </button>
          </div>

          <div className={styles.trimBox}>
            <span style={{ fontSize: '0.84rem', fontWeight: 800, color: '#94a3b8' }}>
              Garder uniquement les premiers :
            </span>
            <input
              type="number"
              value={trimCount}
              onChange={(e) => setTrimCount(e.target.value)}
              className={styles.trimInput}
            />
            <button onClick={handleTrimDatabase} className={styles.trimBtn}>
              <IconScissors />
              <span>Couper la base</span>
            </button>
          </div>
        </div>

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
            <label>Source d&apos;Ingestion</label>
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
                    <option key={`${year}_${month}`} value={`${year}_${month}`}>
                      {year} — {month}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>

          <button type="submit" className={styles.btnFilter}>
            <IconFilter />
            <span>Filtrer</span>
          </button>
        </form>

        <div className={styles.tableContainer}>
          {loading ? (
            <div style={{ padding: '60px', textAlign: 'center', color: '#38bdf8', fontWeight: 900, fontSize: '1.1rem', letterSpacing: '2px' }}>
              SCAN QUANTIQUE EN COURS...
            </div>
          ) : !data || data.content.length === 0 ? (
            <div style={{ padding: '60px', textAlign: 'center', color: '#64748b', fontWeight: 800 }}>
              Aucune intervention trouvée pour ces critères.
            </div>
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
                    <tr key={inv.id} className={styles.tableRow}>
                      <td style={{ color: '#64748b', fontWeight: 800, fontFamily: 'monospace' }}>#{inv.id}</td>
                      <td style={{ fontWeight: 900, color: '#f8fafc', fontFamily: 'monospace' }}>{inv.id_intervention}</td>
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
                          <IconCode />
                          <span>Inspecter JSON</span>
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className={styles.pagination}>
                <button disabled={data.number === 0} onClick={() => setPage(p => p - 1)} className={styles.pageBtn}>
                  Précédent
                </button>
                <span>
                  Page {data.number + 1} sur {data.totalPages}{' '}
                  <span style={{ color: '#38bdf8' }}>({data.totalElements} résultats)</span>
                </span>
                <button disabled={data.number >= data.totalPages - 1} onClick={() => setPage(p => p + 1)} className={styles.pageBtn}>
                  Suivant
                </button>
              </div>
            </>
          )}
        </div>

        {/* MODAL EXPORT AVEC MULTI-SELECT */}
        {isExportModalOpen && (
          <div className={styles.modalOverlay} onClick={() => !isExporting && setIsExportModalOpen(false)}>
            <div className={styles.modalExportContent} onClick={e => e.stopPropagation()}>
              <div className={styles.modalHeader}>
                <h2>
                  <IconDownload />
                  <span>Générer un Export Excel</span>
                </h2>
                <button onClick={() => !isExporting && setIsExportModalOpen(false)} className={styles.closeBtn}>
                  <IconClose />
                </button>
              </div>
              <form onSubmit={handleExport} className={styles.exportForm}>

                <div className={styles.filterGroup}>
                  <label>Type d&apos;Intervention</label>
                  <select value={exportType} onChange={(e) => setExportType(e.target.value)} className={styles.selectInput}>
                    <option value="ALL">Tous les types (Global)</option>
                    <option value="RACC">RACC (Raccordement)</option>
                    <option value="SAV">SAV (Service Après Vente)</option>
                    <option value="RZO">RZO (Audits / Réseau)</option>
                  </select>
                </div>

                <div className={styles.filterGroup}>
                  <label>Source d&apos;Ingestion</label>
                  <select value={exportSource} onChange={(e) => setExportSource(e.target.value)} className={styles.selectInput}>
                    <option value="ALL">Toutes les sources</option>
                    <option value="RADAR">RADAR (Flux Continu)</option>
                    <option value="TIME_MACHINE">TIME MACHINE (Historique)</option>
                    <option value="INCONNUE">INCONNUE (Anciennes données)</option>
                  </select>
                </div>

                <div className={styles.filterGroup}>
                  <label>Périodes à exporter</label>
                  <div className={styles.tmSelector}>
                    <select
                      value={exportPeriodInput}
                      onChange={(e) => setExportPeriodInput(e.target.value)}
                      className={styles.tmSelect}
                    >
                      {availableYears.map(year => (
                        <optgroup key={year} label={`Année ${year}`}>
                          {availableMonths.map(month => (
                            <option key={`${year}_${month}`} value={`${year}_${month}`}>
                              {year} — {month}
                            </option>
                          ))}
                        </optgroup>
                      ))}
                    </select>
                    <button type="button" className={styles.btnSecondary} onClick={addExportPeriod}>
                      <IconPlus />
                      <span>Ajouter</span>
                    </button>
                  </div>

                  <div className={styles.tmList}>
                    {exportPeriods.length === 0 && (
                      <span style={{ fontSize: '0.8rem', color: '#94a3b8', margin: 'auto', fontWeight: 700 }}>
                        TOUTES LES PÉRIODES (BASE GLOBALE)
                      </span>
                    )}
                    {exportPeriods.map(p => (
                      <div key={p} className={styles.tmTag}>
                        <span>{p.replace('_', '-')}</span>
                        <button type="button" onClick={() => setExportPeriods(exportPeriods.filter(item => item !== p))}>
                          <IconClose />
                        </button>
                      </div>
                    ))}
                  </div>
                </div>

                <div className={styles.modalActions}>
                  <button type="button" className={styles.btnCancel} onClick={() => setIsExportModalOpen(false)} disabled={isExporting}>
                    Annuler
                  </button>
                  <button type="submit" className={styles.exportBtn} disabled={isExporting}>
                    <IconDownload />
                    <span>{isExporting ? 'Génération en cours...' : 'Télécharger le fichier'}</span>
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {selectedDetails && (
          <div className={styles.modalOverlay} onClick={() => setSelectedDetails(null)}>
            <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
              <div className={styles.modalHeader}>
                <h2>
                  <IconCode />
                  <span>CONSOLE PAYLOAD JSON</span>
                </h2>
                <button onClick={() => setSelectedDetails(null)} className={styles.closeBtn}>
                  <IconClose />
                </button>
              </div>
              <pre className={styles.jsonView}>{selectedDetails}</pre>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}