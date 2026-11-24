import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiRequest } from '../api/client';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';

const styles = 'space-y-4 rounded-xl border border-gray-200 bg-white p-4 sm:p-6';
const control = 'block w-full rounded-lg border border-gray-300 bg-white p-2 text-sm';
const fields = [
  ['productId', 'Exact catalogue product ID', 'number'], ['storeId', 'Registered anchor branch ID', 'number'],
  ['title', 'Offer title'], ['description', 'Advertised terms'], ['sourceReference', 'Source URL or document reference'],
  ['startsOn', 'Start date', 'date'], ['endsOn', 'Expiry date', 'date'], ['region', 'Province for a regional offer'],
  ['price', 'Offer amount (R)', 'number'], ['minimumQuantity', 'Minimum or paid quantity', 'number'],
  ['freeQuantity', 'Free quantity for buy-and-get-free', 'number'], ['percentOff', 'Percentage discount', 'number'],
  ['loyaltyProgram', 'Loyalty programme'],
];
const enums = {
  kind: ['DISCOUNT', 'MULTIBUY', 'PERCENT', 'BOGOF', 'MEMBER', 'COUPON', 'BUNDLE'],
  scope: ['BRANCH', 'REGION', 'NATIONAL', 'ONLINE', 'UNKNOWN'],
  verification: ['ADVERTISED', 'VERIFIED', 'UNVERIFIED'],
};
const labels = { kind: 'Offer type', scope: 'Applicability', verification: 'Evidence' };
const required = new Set(['productId', 'storeId', 'title', 'description', 'sourceReference', 'startsOn', 'endsOn']);

function Editor({ record, onSubmit, onCancel, busy }) {
  const value = { ...record, storeId: record.storeId ?? record.branchId, price: record.offerAmount, description: record.description ?? '' };
  return <form className={styles} onSubmit={event => {
    event.preventDefault();
    const body = Object.fromEntries(new FormData(event.currentTarget));
    Object.keys(body).forEach(key => { if (body[key] === '') body[key] = null; });
    onSubmit(body);
  }}>
    <h2 className="text-lg font-semibold">{record.id ? 'Correct promotion' : 'Add promotion for review'}</h2>
    <p className="text-sm text-gray-600">Use the catalogue product ID and the branch confirmed by the source. Corrections return the offer to review.</p>
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {fields.map(([key, label, type]) => <label key={key} className="text-sm">{label}<Input type={type || 'text'} name={key}
        min={type === 'number' ? key === 'price' || key === 'percentOff' ? '.01' : '1' : undefined}
        step={type === 'number' && ['price', 'percentOff'].includes(key) ? '.01' : undefined}
        required={required.has(key)} defaultValue={value[key] ?? ''} /></label>)}
      {Object.entries(enums).map(([key, options]) => <label key={key} className="text-sm">{labels[key]}<select name={key} className={control} defaultValue={value[key] || options[0]}>
        {options.map(option => <option key={option}>{option}</option>)}</select></label>)}
    </div>
    <div className="flex gap-2"><Button type="submit" disabled={busy}>Save for review</Button><Button variant="secondary" type="button" onClick={onCancel}>Cancel</Button></div>
  </form>;
}

export default function SpecialsAdmin({ token }) {
  const [data, setData] = useState(null);
  const [audit, setAudit] = useState(null);
  const [imports, setImports] = useState(null);
  const [report, setReport] = useState(null);
  const [edit, setEdit] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [version, setVersion] = useState(0);
  const [query, setQuery] = useState({ q: '', validity: '', page: 0 });
  const params = new URLSearchParams({ q: query.q, validity: query.validity, sort: 'NEWEST', limit: 21, offset: query.page * 20 });
  const path = '/admin/specials?' + params;
  useEffect(() => {
    const controller = new AbortController();
    apiRequest(path, { signal: controller.signal }, token).then(rows => {
      if (!controller.signal.aborted) setData({ path, rows });
    }).catch(failure => {
      if (!controller.signal.aborted) setData({ path, error: failure.message });
    });
    return () => controller.abort();
  }, [path, token, version]);
  const current = data?.path === path ? data : null;

  async function action(work, refresh = true) {
    setError('');
    setBusy(true);
    try {
      await work();
      if (refresh) setVersion(value => value + 1);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }

  return <div className="space-y-5">
    <header className="flex flex-wrap items-center justify-between gap-2">
      <div><h1 className="text-2xl font-bold">Manage specials</h1><Link className="text-sm text-emerald-800 underline" to="/specials">Open public specials</Link></div>
      <Button onClick={() => setEdit({})} disabled={busy}>Add promotion</Button>
    </header>
    {error && <p role="alert" className="rounded-lg bg-red-50 p-3 text-red-900">{error}</p>}
    {edit && <Editor key={edit.id ?? 'new'} record={edit} busy={busy} onCancel={() => setEdit(null)} onSubmit={body => action(async () => {
      const target = edit.id ? '/admin/specials/' + edit.id + '?revision=' + edit.revision : '/admin/specials';
      await apiRequest(target, { method: edit.id ? 'PUT' : 'POST', body: JSON.stringify(body) }, token);
      setEdit(null);
    })} />}
    <section className={styles}>
      <h2 className="text-lg font-semibold">Review promotions</h2>
      <form className="flex flex-wrap items-end gap-2" onSubmit={event => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        setQuery({ q: form.get('q'), validity: form.get('validity'), page: 0 });
      }}>
        <label className="flex-1 text-sm">Search<Input name="q" defaultValue={query.q} /></label>
        <label className="text-sm">Status<select className={control} name="validity" defaultValue={query.validity}>
          <option value="">All statuses</option>{['PENDING', 'ACTIVE', 'UPCOMING', 'EXPIRED', 'UNVERIFIED', 'REJECTED', 'SUSPENDED'].map(item => <option key={item}>{item}</option>)}
        </select></label>
        <Button type="submit">Find promotions</Button>
      </form>
      {current?.error && <p role="alert">{current.error}</p>}
      {!current && <p role="status">Loading promotions…</p>}
      {current?.rows?.length === 0 && <p>No promotions match.</p>}
      <ul className="divide-y divide-gray-200">{current?.rows?.slice(0, 20).map(row => <li key={row.id} className="space-y-2 py-3">
        <h3 className="break-words font-semibold">{row.title || row.productName} · {row.retailer}</h3>
        <p className="text-sm text-gray-600">{row.validity} · {row.scope} · {row.startsOn} to {row.endsOn} · {row.sourceType}</p>
        <p className="break-words text-sm">{row.description}</p>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" disabled={busy} onClick={() => setEdit(row)}>Correct</Button>
          {row.validity !== 'EXPIRED' && ['ACTIVE', 'REJECTED', 'SUSPENDED'].map((status, index) => <Button key={status} variant="secondary" disabled={busy}
            onClick={() => action(() => apiRequest('/admin/specials/' + row.id + '/review', { method: 'POST',
              body: JSON.stringify({ status, revision: row.revision }) }, token))}>{['Approve', 'Reject', 'Suspend'][index]}</Button>)}
          <Button variant="ghost" disabled={busy} onClick={() => action(async () => {
            const rows = await apiRequest('/admin/specials/' + row.id + '/audit', {}, token);
            setAudit({ name: row.title || row.productName, rows });
          }, false)}>View audit history</Button>
        </div>
      </li>)}</ul>
      <div className="flex gap-2">
        {query.page > 0 && <Button variant="secondary" onClick={() => setQuery({ ...query, page: query.page - 1 })}>Previous</Button>}
        {current?.rows?.length > 20 && <Button variant="secondary" onClick={() => setQuery({ ...query, page: query.page + 1 })}>Next</Button>}
      </div>
    </section>
    {audit && <section className={styles}><h2 className="font-semibold">Audit history for {audit.name}</h2>
      {audit.rows.length === 0 && <p>No changes recorded since promotion auditing was enabled.</p>}
      {audit.rows.map(row => <details key={row.id}><summary>{row.action} · {new Date(row.happenedAt).toLocaleString()} · {row.actorId ? 'Administrator ' + row.actorId : 'Import'}</summary>
        <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-all text-xs">{JSON.stringify({ before: row.beforeRecord && JSON.parse(row.beforeRecord), after: row.afterRecord && JSON.parse(row.afterRecord) }, null, 2)}</pre>
      </details>)}
      <Button variant="secondary" onClick={() => setAudit(null)}>Close audit history</Button>
    </section>}
    <section className={styles}>
      <h2 className="text-lg font-semibold">Import Pick n Pay promotions</h2>
      <form className="flex flex-wrap items-end gap-2" onSubmit={event => {
        event.preventDefault();
        const storeCode = new FormData(event.currentTarget).get('storeCode');
        action(async () => setReport(await apiRequest('/admin/ingest/reefapi/promotions?' + new URLSearchParams({ retailerSlug: 'picknpay', storeCode }),
          { method: 'POST' }, token)));
      }}>
        <label className="text-sm">Mapped Pick n Pay store code<Input name="storeCode" required maxLength={30} /></label>
        <Button type="submit" disabled={busy}>Fetch for review</Button>
      </form>
      <Button type="button" variant="secondary" disabled={busy} onClick={() => action(async () => setImports(await apiRequest('/admin/specials/imports', {}, token)), false)}>Import history</Button>
      {imports && <ul className="space-y-2 text-sm">{imports.map(run => <li key={run.id}>
        <strong>{run.status}</strong> {run.provider} {run.storeCode} {new Date(run.startedAt).toLocaleString()}
        {run.errorMessage && <p className="text-red-800">{run.errorMessage}</p>}
      </li>)}</ul>}
      {report && <pre className="max-h-52 overflow-auto whitespace-pre-wrap break-all text-xs">{JSON.stringify(report, null, 2)}</pre>}
    </section>
  </div>;
}
