import { useEffect, useState } from 'react';
import { apiRequest } from '../../api/client';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';

const root = '/admin/grocery-automation';
const names = { OPEN_FOOD_FACTS: 'Open Food Facts catalogue', OPEN_STREET_MAP: 'OpenStreetMap branches', OPEN_PRICES: 'Open Prices exports', LOYALTYHUB: 'Loyalty Hub free tier' };
const isBulk = source => ['OPEN_FOOD_FACTS', 'OPEN_STREET_MAP'].includes(source);
const time = date => date ? new Date(date).toLocaleString('en-ZA') : 'No completed import';

export default function GroceryAutomationPanel({ token }) {
  const [version, setVersion] = useState(0);
  const [status, setStatus] = useState(null);
  const [source, setSource] = useState('OPEN_FOOD_FACTS');
  const [query, setQuery] = useState('milk');
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    const abort = new AbortController();
    apiRequest(root, { signal: abort.signal }, token)
      .then(data => { if (!abort.signal.aborted) setStatus(data); })
      .catch(failure => { if (!abort.signal.aborted) setError(failure.message); });
    return () => abort.abort();
  }, [token, version]);

  async function run(dryRun) {
    setBusy(true);
    setError('');
    setReport(null);
    try {
      const params = new URLSearchParams({ dryRun });
      if (source === 'LOYALTYHUB') params.set('query', query.trim());
      let target = root + '/' + source + '/sync';
      let body;
      if (file && isBulk(source)) {
        target = root + '/' + source + '/file';
        body = new FormData();
        body.append('file', file);
      }
      const data = await apiRequest(target + '?' + params, { method: 'POST', body }, token);
      setReport(data);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setVersion(value => value + 1);
      setBusy(false);
    }
  }

  const enabled = status?.sources.find(item => item.source === source)?.enabled;
  const available = isBulk(source) ? Boolean(file || enabled) : enabled && (source !== 'LOYALTYHUB' || query.trim());
  return <section className="space-y-4 rounded-xl border border-gray-200 bg-white p-4 sm:p-6">
    <h2 className="text-lg font-semibold">Free grocery imports</h2>
    <ul className="grid gap-2 sm:grid-cols-2">{status?.sources.map(item => <li key={item.source} className="rounded-lg border border-gray-200 p-3 text-sm">
      <strong>{names[item.source] || item.source}</strong>
      <p>{item.enabled ? 'Configured' : isBulk(item.source) ? 'File upload available' : 'Feed disabled'}</p>
      <p className="text-gray-600">Last successful sync · {time(item.lastSuccess)}</p>
      {item.lastRun?.status === 'FAILED' && <p className="text-red-800">{item.lastRun.detail}</p>}
    </li>)}</ul>
    <div className="grid gap-3 sm:grid-cols-2">
      <label className="text-sm">Source<select className="block w-full rounded-lg border border-gray-300 p-2" value={source} onChange={event => {
        setSource(event.target.value); setFile(null); setReport(null); setError('');
      }}>
        {Object.entries(names).map(([key,label]) => <option key={key} value={key}>{label}</option>)}
      </select></label>
      {isBulk(source) && <label className="text-sm">Source export<Input key={source} type="file" accept=".csv,.tsv,.json,.jsonl,.ndjson,.geojsonseq,.osm,.gz"
        onChange={event => setFile(event.target.files?.[0] || null)} /></label>}
      {source === 'LOYALTYHUB' && <label className="text-sm">Search query<Input value={query} maxLength={120} onChange={event => setQuery(event.target.value)} /></label>}
    </div>
    {source === 'LOYALTYHUB' && <p className="text-sm text-gray-600">Previews also use the free quota. {status?.quota.localCalls ?? 0} local calls in the last 31 days.
      {status?.quota.remaining != null && ' Provider calls remaining ' + status.quota.remaining + '.'}
      {status?.quota.rowsRemaining != null && ' Provider rows remaining ' + status.quota.rowsRemaining + '.'}</p>}
    <div className="flex flex-wrap gap-2">
      <Button type="button" disabled={busy || !available} variant="secondary" onClick={() => run(true)}>Dry-run preview</Button>
      <Button type="button" disabled={busy || !available} onClick={() => run(false)}>Run import</Button>
    </div>
    {busy && <p role="status">Reading source data…</p>}
    {error && <p role="alert" className="text-red-800">{error}</p>}
    {report && <div role="status" className="rounded-lg bg-emerald-50 p-3 text-sm text-emerald-950">
      <p>{report.dryRun ? 'Preview' : 'Import'} · {report.examined} examined · {report.imported} {report.dryRun ? 'eligible to import' : 'imported'} · {report.skipped} skipped · {report.review} for review</p>
      {report.checkpoint && <p className="break-all">Checkpoint · {report.checkpoint}</p>}
      {report.detail && <p className="break-words">{report.detail}</p>}
    </div>}
    <details><summary className="cursor-pointer font-medium">Recent import runs</summary>
      <ul className="mt-2 divide-y divide-gray-200 text-sm">{status?.runs.map(run => <li key={run.id} className="space-y-1 py-2">
        <p>{names[run.source] || run.source} · {run.status} · {time(run.finishedAt)}{run.dryRun && ' · Preview'}</p>
        <p>{run.imported} imported · {run.skipped} skipped · {run.review} for review</p>
        {run.detail && <p className="break-words text-gray-600">{run.detail}</p>}
      </li>)}</ul>
    </details>
    <details><summary className="cursor-pointer font-medium">Records requiring review</summary>
      {status?.review.length === 0 && <p className="mt-2 text-sm">No pending records.</p>}
      <ul className="mt-2 divide-y divide-gray-200 text-sm">{status?.review.map(item => <li key={item.source + ':' + item.sourceKey} className="break-words py-2">
        {item.label || item.sourceKey} · {item.reason}
      </li>)}</ul>
    </details>
  </section>;
}
