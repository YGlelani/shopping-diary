import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiRequest } from '../api/client';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';

const money = new Intl.NumberFormat('en-ZA', { style: 'currency', currency: 'ZAR' });
const rand = value => money.format(value);
const today = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Africa/Johannesburg' }).format(new Date());
const panel = 'space-y-4 rounded-xl border bg-white p-4 sm:p-6';
const select = 'block w-full rounded-md border border-gray-300 bg-white p-2';
const link = 'text-sm font-medium text-emerald-800 underline';
const periods = { TRIP: 'Single trip', WEEKLY: 'Weekly', FORTNIGHTLY: 'Fortnightly', MONTHLY: 'Monthly', CUSTOM: 'Custom dates' };
const statuses = {
  EMPTY: 'Add items to a linked list to see an estimate.',
  INSUFFICIENT_PRICES: 'No eligible prices are available for these items.',
  INCOMPLETE: 'Partial estimate. Affordability is unknown until the missing items are priced.',
  OVER_BUDGET: 'The known subtotal already exceeds your budget.',
  WITHIN_BUDGET: 'All items have eligible prices at the same branch and the estimate fits your budget.',
  MULTI_STORE_ESTIMATE: 'Every item is priced across several branches. Compare a single branch before planning one trip.',
};

function useResource(path, token) {
  const [result, setResult] = useState(null);
  const [version, setVersion] = useState(0);
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    const key = { path, version };
    apiRequest(path, { signal: controller.signal }, token).then(data => {
      if (!controller.signal.aborted) setResult({ ...key, data });
    }).catch(error => {
      if (!controller.signal.aborted) setResult({ ...key, error: error.message });
    });
    return () => controller.abort();
  }, [path, token, version]);
  return { ...(result?.path === path && result?.version === version ? result : {}), reload: () => setVersion(value => value + 1) };
}

function RequestStatus({ resource }) {
  return resource.error ? <div role="alert" className="text-red-800">{resource.error}{' '}
    <button className={link} onClick={resource.reload}>Retry</button></div> :
    resource.data == null ? <p role="status" className="text-sm text-gray-600">Loading…</p> : null;
}

function BudgetForm({ editor, lists, onSave, onCancel, busy }) {
  const { kind, initial } = editor;
  const [period, setPeriod] = useState(initial.period);
  const [ids, setIds] = useState(initial.listIds);
  const reuse = kind === 'reuse';
  return <form className={panel} onSubmit={event => {
    event.preventDefault();
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    const dates = { name: fields.name.trim(), startsOn: fields.startsOn, endsOn: period === 'CUSTOM' ? fields.endsOn : null };
    onSave(reuse ? dates : { ...dates, period, amount: fields.amount, description: fields.description, listIds: ids });
  }}>
    <h2 className="text-lg font-semibold">{reuse ? 'Reuse budget' : kind === 'edit' ? 'Edit budget' : 'New budget'}</h2>
    <div className="grid gap-3 sm:grid-cols-2">
      <label>Name<Input name="name" required maxLength={200} defaultValue={initial.name} /></label>
      {!reuse && <label>Budget amount (ZAR)<Input name="amount" type="number" step="0.01" min="0" max="99999999.99" required defaultValue={initial.amount} /></label>}
      {!reuse && <label>Period<select className={select} value={period} onChange={event => setPeriod(event.target.value)}>
        {Object.entries(periods).map(([key, value]) => <option key={key} value={key}>{value}</option>)}
      </select></label>}
      <label>Start date<Input type="date" name="startsOn" required defaultValue={reuse ? today() : initial.startsOn} /></label>
      {period === 'CUSTOM' && <label>End date<Input type="date" name="endsOn" required defaultValue={reuse ? '' : initial.endsOn} /></label>}
      {!reuse && <label className="sm:col-span-2">Description (optional)<Input name="description" maxLength={1000} defaultValue={initial.description} /></label>}
    </div>
    {reuse ? <p className="text-sm text-gray-600">Copy the amount, period type and linked lists into a new budget. Recorded purchases stay in the original budget.</p> :
      <fieldset className="space-y-2">
        <legend className="font-medium">Linked shopping lists</legend>
        {!lists.length && <p className="text-sm text-gray-600">Create a <Link className={link} to="/lists">shopping list</Link> to estimate a basket.</p>}
        <div className="grid gap-2 sm:grid-cols-2">
          {lists.map(row => <label key={row.id} className="flex items-start gap-2 break-all text-sm">
            <input type="checkbox" checked={ids.includes(row.id)} onChange={event => setIds(event.target.checked ? [...ids, row.id] : ids.filter(id => id !== row.id))} />
            {row.name}
          </label>)}
        </div>
      </fieldset>}
    <div className="flex flex-wrap gap-2"><Button type="submit" disabled={busy}>Save budget</Button><Button type="button" variant="secondary" onClick={onCancel}>Cancel</Button></div>
  </form>;
}

function PurchaseForm({ purchase, budget, lists, branches, onSave, onCancel, busy }) {
  return <form className="space-y-3 rounded-lg border bg-gray-50 p-3" onSubmit={event => {
    event.preventDefault();
    const body = Object.fromEntries(new FormData(event.currentTarget));
    onSave({ ...body, listId: body.listId ? Number(body.listId) : null, branchId: body.branchId ? Number(body.branchId) : null });
  }}>
    <h3 className="font-semibold">{purchase.id ? 'Correct purchase' : 'Record a purchase'}</h3>
    <div className="grid gap-3 sm:grid-cols-2">
      <label>Purchase or receipt description<Input name="description" required maxLength={300} defaultValue={purchase.description} /></label>
      <label>Amount paid (ZAR)<Input name="amount" type="number" step="0.01" min="0.01" max="99999999.99" required defaultValue={purchase.amount} /></label>
      <label>Purchase date<Input name="purchasedOn" type="date" min={budget.startsOn} max={budget.endsOn < today() ? budget.endsOn : today()} required defaultValue={purchase.purchasedOn || (budget.endsOn < today() ? budget.endsOn : today())} /></label>
      <label>Category (optional)<Input name="category" maxLength={100} defaultValue={purchase.category} /></label>
      <label>Shopping list (optional)<select className={select} name="listId" defaultValue={purchase.listId ?? ''}>
        <option value="">No linked list</option>{lists.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
        {purchase.listId && !lists.some(row => row.id === purchase.listId) && <option value={purchase.listId}>Previously linked list</option>}
      </select></label>
      <label>Store branch (optional)<select className={select} name="branchId" defaultValue={purchase.branchId ?? ''}>
        <option value="">No branch</option>{branches.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
        {purchase.branchId && !branches.some(row => row.id === purchase.branchId) && <option value={purchase.branchId}>{purchase.branchName}</option>}
      </select></label>
    </div>
    <div className="flex flex-wrap gap-2"><Button type="submit" disabled={busy}>Save purchase</Button><Button type="button" variant="secondary" onClick={onCancel}>Cancel</Button></div>
  </form>;
}

function Estimate({ resource, lists, archived }) {
  const quote = resource.data;
  const assessment = quote?.assessment;
  return <>
    <RequestStatus resource={resource} />
    {assessment && <>
      <p role="status" className={'rounded-lg p-3 text-sm ' + (assessment.status === 'OVER_BUDGET' ? 'bg-red-50 text-red-900' : 'bg-amber-50 text-gray-800')}>
        {statuses[assessment.status]} {assessment.overBy != null && <>Over by <strong>{rand(assessment.overBy)}</strong>.</>}
        {assessment.approaching && ' The known subtotal uses at least 90% of your budget.'}
      </p>
      <dl className="grid gap-3 sm:grid-cols-3">
        <div><dt className="text-sm text-gray-600">Known estimated subtotal</dt><dd className="text-lg font-semibold">{assessment.knownSubtotal == null ? 'Unavailable' : rand(assessment.knownSubtotal)}</dd></div>
        <div><dt className="text-sm text-gray-600">Price coverage</dt><dd className="text-lg font-semibold">{assessment.priced} of {assessment.entries} entries</dd></div>
        <div><dt className="text-sm text-gray-600">{assessment.unpriced ? 'Balance before missing items' : 'Estimated balance'}</dt><dd className="text-lg font-semibold">
          {assessment.remainingAfterKnown == null ? 'Unavailable' : rand(assessment.remainingAfterKnown)}
        </dd></div>
      </dl>
      <p className="text-sm text-gray-600">{archived ? 'Archived estimate from ' : 'Prices checked '}<time dateTime={quote.calculatedAt}>{new Date(quote.calculatedAt).toLocaleString('en-ZA', { timeZone: 'Africa/Johannesburg' })}</time>. Prices can change at checkout.</p>
      {quote.lines.length > 0 && <ul className="divide-y rounded-lg border px-3">
        {quote.lines.map(row => <li key={row.itemId} className="grid gap-1 py-3 text-sm sm:grid-cols-[1fr_auto]">
          <div className="min-w-0 break-words">
            <p className="font-medium">{row.requestedName} × {row.quantity}</p>
            <p className="text-gray-600">{lists.find(list => list.id === row.listId)?.name}{row.storeName && ' · ' + row.storeName}</p>
            {row.productName && row.productName !== row.requestedName && <p>Approved alternative {row.productName}</p>}
            {row.observedAt && <p className="text-gray-600">Observed {new Date(row.observedAt).toLocaleDateString('en-ZA', { timeZone: 'Africa/Johannesburg' })}{row.sourceType && ' · ' + row.sourceType.replaceAll('_', ' ').toLowerCase()}{row.verificationStatus && ' · ' + row.verificationStatus.replaceAll('_', ' ').toLowerCase()}</p>}
            {row.unavailableReason === 'STALE_OBSERVATION' && <p className="text-amber-800">A nearby observation is outdated.</p>}
            {row.unavailableReason === 'UNLINKED' && <p className="text-gray-600">Link this entry to a catalogue product to check prices.</p>}
            {row.promotionEndsOn && <p className="text-amber-800">Offer ends {row.promotionEndsOn}. Refresh before shopping.</p>}
          </div>
          <strong className={row.total == null ? 'text-amber-800' : ''}>{row.total == null ? 'Price unavailable' : rand(row.total)}</strong>
        </li>)}
      </ul>}
    </>}
  </>;
}

export default function Budgets({ token, lists, location, radius }) {
  const [params, setParams] = useSearchParams();
  const id = params.get('id');
  const sourceList = lists.find(row => String(row.id) === params.get('listId'));
  const [page, setPage] = useState(0);
  const [expensePage, setExpensePage] = useState(0);
  const [loyalty, setLoyalty] = useState([]);
  const [branch, setBranch] = useState({ scope: '', id: '' });
  const [editor, setEditor] = useState(null);
  const [purchase, setPurchase] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [tick, setTick] = useState(0);
  const lock = useRef(false);
  const requestKey = useRef(null);
  const overview = useResource('/budgets?offset=' + page * 50, token);
  const base = id ? '/budgets/' + id : null;
  const detail = useResource(base && base + '?offset=' + expensePage * 25, token);
  const budget = detail.data?.budget;
  const linked = lists.filter(row => detail.data?.listIds.includes(row.id));
  const archived = budget?.status === 'ARCHIVED';
  const coordinates = new URLSearchParams({ r: radius });
  if (location) {
    coordinates.set('lat', location.lat);
    coordinates.set('lon', location.lon);
  }
  const scope = [id, coordinates.toString()].join('|');
  const branchId = branch.scope === scope ? branch.id : '';
  const branches = useResource(location && '/stores/nearby?' + coordinates + '&limit=100', token);
  const branchRows = branches.data ?? [];
  const retailers = useResource('/retailers', token);
  loyalty.forEach(id => coordinates.append('loyaltyRetailer', id));
  if (branchId) coordinates.set('branchId', branchId);
  const estimateQuery = coordinates.toString();
  coordinates.set('refresh', [budget?.revision, tick, ...linked.map(row => row.revision)].join('-'));
  const quote = useResource(budget && base + '/estimate?' + coordinates, token);
  useEffect(() => {
    if (!id || archived) return;
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') setTick(value => value + 1);
    }, 60000);
    return () => clearInterval(timer);
  }, [id, archived]);

  function open(newId) {
    setParams(newId ? { id: newId } : {});
    setExpensePage(0);
    setEditor(null);
    setPurchase(null);
    setError('');
  }

  async function mutate(path, method, body, onDone, idempotent = false) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError('');
    try {
      const json = body ? JSON.stringify(body) : undefined;
      const payload = path + json;
      if (idempotent && requestKey.current?.payload !== payload) requestKey.current = { payload, id: crypto.randomUUID() };
      const headers = idempotent ? { 'Idempotency-Key': requestKey.current.id } : {};
      const result = await apiRequest(path, { method, body: json, headers }, token);
      requestKey.current = null;
      onDone?.(result);
      overview.reload();
      detail.reload();
      quote.reload();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
      lock.current = false;
    }
  }

  function newBudget() {
    setEditor({ kind: 'new', initial: { name: sourceList ? sourceList.name.slice(0, 193) + ' budget' : '', amount: sourceList?.budgetAmount ?? '', period: 'WEEKLY', startsOn: today(), listIds: sourceList ? [sourceList.id] : [] } });
  }

  function saveBudget(body) {
    if (editor.kind === 'edit') return mutate(base + '?revision=' + editor.initial.revision, 'PUT', body, () => setEditor(null));
    const path = editor.kind === 'reuse' ? base + '/reuse' : '/budgets';
    return mutate(path, 'POST', body, result => { setPage(0); open(result.id); }, true);
  }

  function savePurchase(body) {
    if (purchase.id) return mutate(base + '/expenses/' + purchase.id + '?revision=' + purchase.revision, 'PUT', body, () => setPurchase(null));
    return mutate(base + '/expenses', 'POST', body, () => { setExpensePage(0); setPurchase(null); }, true);
  }

  const spent = detail.data?.actualSpent;
  const remaining = detail.data?.actualRemaining;
  const progress = budget?.amount ? Math.min(100, Math.round(100 * spent / budget.amount)) : spent > 0 ? 100 : 0;
  return <div className="space-y-5">
    <header className="flex flex-wrap items-center justify-between gap-3">
      <div><h1 className="text-2xl font-bold">Grocery budgets</h1><p className="text-sm text-gray-600">Plan grocery costs and track what you pay.</p></div>
      <Button disabled={busy} onClick={newBudget}>New budget</Button>
    </header>
    {sourceList && !editor && <p className="text-sm text-gray-600">New budgets will start with {sourceList.name} selected.</p>}
    {error && <div className="rounded-lg bg-red-50 p-3 text-red-900" role="alert">{error}{' '}
      <button className={link} onClick={() => { detail.reload(); overview.reload(); quote.reload(); }}>Reload saved records</button></div>}
    {editor && <BudgetForm key={editor.kind + (editor.initial.id ?? '')} editor={editor} lists={lists} busy={busy} onCancel={() => setEditor(null)} onSave={saveBudget} />}
    {!id && <section className={panel}>
      <h2 className="text-lg font-semibold">Budget history</h2>
      <RequestStatus resource={overview} />
      {overview.data?.length === 0 && <p className="text-gray-600">No budgets on this page. Create a budget to start planning.</p>}
      <ul className="grid gap-3 sm:grid-cols-2">
        {overview.data?.map(row => <li key={row.id} className="rounded-lg border p-3">
          <button className="break-all text-left font-semibold text-emerald-800 underline" onClick={() => open(row.id)}>{row.name}</button>
          <p className="text-sm text-gray-600">{row.startsOn} to {row.endsOn} · {row.status === 'ARCHIVED' ? 'Archived' : row.endsOn < today() ? 'Ended' : row.startsOn > today() ? 'Upcoming' : 'Active'}</p>
          {row.snapshotSubtotal != null && <p className="text-sm text-gray-600">Saved estimate {rand(row.snapshotSubtotal)} for {row.snapshotPriced} of {row.snapshotEntries} entries</p>}
          <p className="mt-2 text-sm">{rand(row.actualSpent)} recorded of {rand(row.amount)}{row.recordedExpenses === 0 && ' · No purchases recorded'}</p>
        </li>)}
      </ul>
      <div className="flex gap-2">
        {page > 0 && <Button variant="secondary" onClick={() => setPage(page - 1)}>Previous budgets</Button>}
        {overview.data?.length === 50 && <Button variant="secondary" onClick={() => setPage(page + 1)}>Older budgets</Button>}
      </div>
    </section>}
    {id && <><button className={link} disabled={busy} onClick={() => open(null)}>All budgets</button><RequestStatus resource={detail} /></>}
    {budget && <>
      <section className={panel}>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 break-words">
            <h2 className="text-xl font-bold">{budget.name}</h2>
            <p className="text-sm text-gray-600">{periods[budget.period]} · {budget.startsOn} to {budget.endsOn}{archived && ' · Archived'}</p>
            {budget.description && <p className="mt-2">{budget.description}</p>}
          </div>
          <div className="flex flex-wrap gap-2">
            {!archived && <Button variant="secondary" disabled={busy} onClick={() => setEditor({ kind: 'edit', initial: { ...budget, listIds: detail.data.listIds } })}>Edit</Button>}
            <Button variant="secondary" disabled={busy} onClick={() => setEditor({ kind: 'reuse', initial: budget })}>Reuse</Button>
            {!archived && <Button variant="secondary" disabled={busy || !quote.data} onClick={() => {
              if (window.confirm('Archive this budget and save its current estimate?')) mutate(base + '/archive?' + estimateQuery + '&revision=' + budget.revision, 'POST');
            }}>Archive</Button>}
            <Button variant="secondary" disabled={busy} onClick={() => {
              if (window.confirm('Permanently delete this budget and its recorded purchases?')) mutate(base + '?revision=' + budget.revision, 'DELETE', null, () => open(null));
            }}>Delete</Button>
          </div>
        </div>
        <p className="break-words text-sm text-gray-600">{linked.length ? 'Linked lists · ' + linked.map(row => row.name).join(', ') : 'No linked shopping lists'}</p>
        {linked.length > 0 && <div className="flex flex-wrap gap-x-3 gap-y-1">{linked.map(row => <Link className={link} key={row.id} to={'/lists?listId=' + row.id}>Open {row.name}</Link>)}</div>}
        <dl className="grid gap-3 sm:grid-cols-3">
          <div><dt className="text-sm">Budget</dt><dd className="text-xl font-bold">{rand(budget.amount)}</dd></div>
          <div><dt className="text-sm">Actual spending recorded</dt><dd className="text-xl font-bold">{rand(spent)}</dd></div>
          <div><dt className="text-sm">{remaining < 0 ? 'Actual overspend' : 'Actual budget remaining'}</dt>
            <dd className={'text-xl font-bold ' + (remaining < 0 ? 'text-red-800' : 'text-emerald-800')}>{rand(Math.abs(remaining))}</dd></div>
        </dl>
        <progress className="block h-3 w-full accent-emerald-700" aria-label="Recorded spending as a percentage of budget" value={progress} max="100" />
      </section>
      <section className={panel}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold">{archived ? 'Saved shopping estimate' : 'Planned shopping estimate'}</h2>
          {!archived && <Button variant="secondary" onClick={quote.reload}>Refresh prices</Button>}
        </div>
        {!archived && <>
          <p className="text-sm text-gray-600">Estimates cover the full linked lists at their saved locations or your current shopping location. Prices across branches may require multiple trips.</p>
          <div className="grid items-end gap-3 sm:grid-cols-2">
            {linked.length > 0 && <Link className={link} to={'/specials?listId=' + linked[0].id}>Check applicable specials</Link>}
            <Link className={link} to="/stores" state={{ returnTo: '/budgets?id=' + id }}>{location ? 'Change shopping location' : 'Choose a shopping location'}</Link>
            {location && <label className="text-sm">Estimate for a branch<select className={select} value={branchId} onChange={event => setBranch({ scope, id: event.target.value })}>
              <option value="">Best eligible price per item</option>{branchRows.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
            </select></label>}
          </div>
          <details><summary className="cursor-pointer text-sm font-medium">Confirm loyalty eligibility</summary>
            <fieldset className="mt-2 grid gap-2 sm:grid-cols-3">
              <legend className="text-sm">I have the required loyalty card for</legend>
              {retailers.data?.filter(row => row.active).map(row => <label className="flex gap-2 text-sm" key={row.id}><input type="checkbox"
                checked={loyalty.includes(row.id)} onChange={event => setLoyalty(event.target.checked ? [...loyalty, row.id] : loyalty.filter(id => id !== row.id))} />
                {row.name}</label>)}
            </fieldset>
          </details>
          {location && branches.error && <RequestStatus resource={branches} />}
        </>}
        <Estimate resource={quote} lists={lists} archived={archived} />
      </section>
      <section className={panel}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold">Actual purchases</h2>
          <Button disabled={busy || budget.startsOn > today()} onClick={() => setPurchase({})}>Record purchase</Button>
        </div>
        {purchase && <PurchaseForm key={purchase.id ?? 'new'} purchase={purchase} budget={budget} lists={linked} branches={branchRows}
          onSave={savePurchase} onCancel={() => setPurchase(null)} busy={busy} />}
        {detail.data.expenseCount === 0 && <p className="text-sm text-gray-600">No purchases recorded for this period.</p>}
        {quote.data?.assessment?.priced > 0 && quote.data.assessment.unpriced === 0 && detail.data.expenseCount > 0 &&
          <p className="text-sm text-gray-600">Recorded so far {rand(spent)} · Full planned estimate {rand(quote.data.assessment.knownSubtotal)}. Purchases can cover a different basket.</p>}
        <ul className="divide-y">
          {detail.data.expenses.map(row => <li key={row.id} className="flex flex-wrap items-center justify-between gap-2 py-3 text-sm">
            <div className="min-w-0 break-words"><p className="font-medium">{row.description} · {rand(row.amount)}</p><p className="text-gray-600">
              {[row.purchasedOn, row.category, row.branchName, lists.find(list => list.id === row.listId)?.name].filter(Boolean).join(' · ')}
            </p></div>
            <div className="flex gap-2">
              <Button variant="secondary" disabled={busy} onClick={() => setPurchase(row)}>Correct</Button>
              <Button variant="secondary" disabled={busy} onClick={() => {
                if (window.confirm('Remove this recorded purchase?')) mutate(base + '/expenses/' + row.id + '?revision=' + row.revision, 'DELETE', null, () => { setPurchase(null); setExpensePage(0); });
              }}>Remove</Button>
            </div>
          </li>)}
        </ul>
        {expensePage > 0 && <Button variant="secondary" onClick={() => setExpensePage(expensePage - 1)}>Previous purchases</Button>}
        {detail.data.expenseCount > (expensePage + 1) * 25 && <Button variant="secondary" onClick={() => setExpensePage(expensePage + 1)}>Older purchases</Button>}
        {detail.data.spendingByCategory.length > 0 && <details><summary className="cursor-pointer font-medium">Recorded spending by category</summary>
          <ul className="space-y-1 pt-2 text-sm">{detail.data.spendingByCategory.map(row => <li key={row.category}>{row.category} · {rand(row.total)}</li>)}</ul>
        </details>}
      </section>
    </>}
  </div>;
}
