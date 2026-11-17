import { SyncEvent } from '../../services/api';

const SRC: Record<string, string> = {
  TIME_MACHINE: 'Time Machine', HEALER: 'Healer', AUTOPILOT: 'AutoPilot',
  BYTEL: 'Bytel', MAINTENANCE: 'Maintenance', SYSTEM: 'Système',
};
const LVL: Record<string, string> = { INFO: 'Info', SUCCESS: 'OK', WARN: 'Alerte', ERROR: 'Erreur' };

export const fmtEventTime = (iso?: string) =>
  iso ? `${iso.slice(8, 10)}/${iso.slice(5, 7)} ${iso.slice(11, 19)}` : '';

/** Une ligne TSV (colle proprement dans Excel / Teams / un ticket). */
export const eventToLine = (e: SyncEvent) =>
  [fmtEventTime(e.createdAt), SRC[e.source] || e.source, LVL[e.level] || e.level, e.period || '—', e.message].join('\t');

export const eventsToText = (rows: SyncEvent[], header = true) =>
  (header ? 'Horodatage\tSource\tNiveau\tPériode\tMessage\n' : '') + rows.map(eventToLine).join('\n');

/**
 * Copie dans le presse-papier. navigator.clipboard n'existe qu'en HTTPS/localhost :
 * en http://10.x on retombe sur l'ancienne méthode textarea + execCommand.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch { /* fallback */ }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}
