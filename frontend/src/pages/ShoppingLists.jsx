import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { apiRequest } from '../api/client';
import ListItemEditor from '../components/ListItemEditor';
import ListProductPicker from '../components/ListProductPicker';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { formatObservationDate, formatProductPrice } from '../utils/productFormatting';
import { savedItem } from '../utils/listEditing';

const ProductDetails = lazy(() => import('../components/ProductDetailsModal'));

function ListMetadata({ list, location, busy, onSave }) {
  const [locationMode, setLocationMode] = useState(list?.locationLat != null ? 'saved' : 'none');
  return <form className="grid gap-3 text-sm sm:grid-cols-2" onSubmit={event => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const chosen = locationMode === 'current' ? location : locationMode === 'saved'
      ? { lat: list.locationLat, lon: list.locationLon, label: list.locationLabel } : null;
    onSave({
      name: data.get('name'), description: data.get('description'), budgetAmount: data.get('budget') || null,
      locationLat: chosen?.lat ?? null, locationLon: chosen?.lon ?? null, locationLabel: chosen?.label ?? null,
    }, data.has('planBudget'));
  }}>
    <label>List name<Input name="name" required maxLength={200} defaultValue={list?.name} /></label>
    <label>Budget (R)<Input name="budget" type="number" min="0" max="99999999.99" step="0.01" defaultValue={list?.budgetAmount} /></label>
    <label className="sm:col-span-2">Description<textarea name="description" maxLength={1000} rows={2}
      className="block w-full rounded-lg border p-2" defaultValue={list?.description} /></label>
    <label>Preferred shopping location
      <select value={locationMode} onChange={event => setLocationMode(event.target.value)} className="block w-full rounded border p-2">
        <option value="none">Follow the active shopping location</option>
        {list?.locationLat != null && <option value="saved">Keep {list.locationLabel || 'saved location'}</option>}
        {location && <option value="current">Save {location.label || 'current location'} with this list</option>}
      </select>
    </label>
    {!list && <label className="flex items-center gap-2"><input type="checkbox" name="planBudget" /> Set up a grocery budget for this list</label>}
    <Button type="submit" disabled={busy} className="self-end">{busy ? 'Saving…' : list ? 'Save list details' : 'Create list'}</Button>
  </form>;
}

export default function ShoppingLists({ lists, listId, onSelect, onCreate, token, location, radius,
  onRemember, onRefresh, onForget, onAddItem }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const requested = lists.find(row => String(row.id) === params.get('listId'));
  const list = requested ?? lists.find(candidate => candidate.id === listId);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState(null);
  const inFlight = useRef(false);
  const copyRequest = useRef(null);

  const [coverage, setCoverage] = useState(null);
  const coverageParams = new URLSearchParams({ r: radius });
  if (location) {
    coverageParams.set('lat', location.lat);
    coverageParams.set('lon', location.lon);
  }
  const coverageQuery = coverageParams.toString();
  const coverageKey = coverageQuery + ':' + lists.map(row => row.id + '/' + row.revision).join(',');
  const covered = new Map((coverage?.key === coverageKey ? coverage.rows : []).map(row => [row.listId, row]));
  const hasLists = lists.length > 0;

  useEffect(() => {
    if (!hasLists) return;
    const controller = new AbortController();
    apiRequest('/lists/coverage?' + coverageQuery, { signal: controller.signal }, token)
      .then(rows => { if (!controller.signal.aborted) setCoverage({ key: coverageKey, rows }); })
      .catch(() => { if (!controller.signal.aborted) setCoverage({ key: coverageKey, rows: [] }); });
    return () => controller.abort();
  }, [coverageKey, coverageQuery, hasLists, token]);

  async function run(task) {
    if (inFlight.current) return false;
    inFlight.current = true;
    setBusy(true);
    setFeedback(null);
    try {
      await task();
      setFeedback({ message: 'Saved.' });
      return true;
    } catch (error) {
      setFeedback({ error: true, message: error.message });
      return false;
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  function open(id) {
    if (requested) navigate('/lists', { replace: true });
    onSelect(id);
    setFeedback(null);
    onRefresh(id).catch(error => setFeedback({ error: true, message: error.message }));
  }

  function duplicate(source, name) {
    return run(async () => {
      const signature = source.id + ':' + name;
      if (copyRequest.current?.signature !== signature) copyRequest.current = { signature, key: crypto.randomUUID() };
      const copy = await apiRequest('/lists/' + source.id + '/duplicate', {
        method: 'POST', body: JSON.stringify({ name }), headers: { 'Idempotency-Key': copyRequest.current.key },
      }, token);
      copyRequest.current = null;
      onRemember(copy);
      if (requested) navigate('/lists', { replace: true });
      onSelect(copy.id);
    });
  }

  function changeStatus(source, status) {
    return run(async () => {
      const { name, description, budgetAmount, locationLat, locationLon, locationLabel } = source;
      onRemember(await apiRequest('/lists/' + source.id + '?' + new URLSearchParams({ revision: source.revision, status }), {
        method: 'PUT', body: JSON.stringify({ name, description, budgetAmount, locationLat, locationLon, locationLabel }),
      }, token));
    });
  }

  function removeList(source) {
    if (!window.confirm('Delete “' + source.name + '” and its items?')) return;
    run(async () => {
      await apiRequest('/lists/' + source.id + '?revision=' + source.revision, { method: 'DELETE' }, token);
      onForget(source.id);
    });
  }

  return <div className="mx-auto max-w-5xl space-y-5">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h1 className="text-2xl font-bold">Shopping lists</h1>
      <Button aria-expanded={creating} onClick={() => setCreating(value => !value)}>{creating ? 'Hide new list form' : 'Create list'}</Button>
    </div>
    {(creating || lists.length === 0) && <section className="space-y-3 rounded-xl border bg-white p-4">
      <h2 className="font-semibold">New shopping list</h2>
      <ListMetadata location={location} busy={busy} onSave={(values, planBudget) => run(async () => {
        const created = await onCreate(values);
        setCreating(false);
        if (planBudget) navigate('/budgets?listId=' + created.id);
      })} />
    </section>}
    {feedback && <div role={feedback.error ? 'alert' : 'status'} className={feedback.error ? 'text-sm text-red-700' : 'text-sm text-emerald-800'}>
      {feedback.message}
      {feedback.error && list && <Button variant="secondary" disabled={busy} className="ml-2" onClick={() => run(() => onRefresh(list.id))}>Reload saved list</Button>}
    </div>}
    {lists.length > 0 && <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" aria-label="Saved shopping lists">
      {lists.map(row => <li key={row.id} className={'space-y-2 rounded-xl border bg-white p-4 ' + (row.id === list?.id ? 'ring-2 ring-emerald-700' : '')}>
        <h2 className="break-words font-semibold">{row.name}</h2>
        <p className="text-sm text-gray-600">{row.status === 'DRAFT' ? 'Draft' : row.status.toLowerCase()} · {row.items.length} entries · {row.items.filter(item => item.purchased).length} purchased</p>
        <p className="text-sm text-gray-600">Updated {formatObservationDate(row.updatedAt ?? row.createdAt)}{row.budgetAmount != null && ' · Budget R' + Number(row.budgetAmount).toFixed(2)}</p>
        {covered.get(row.id)?.hasLocation &&
          <p className="text-sm text-gray-600">{covered.get(row.id).pricedItems} of {row.items.length} entries with a usable price</p>}
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={() => open(row.id)}>{row.id === list?.id ? 'Reload list' : 'Open list'}</Button>
          <Button variant="ghost" disabled={busy} onClick={() => duplicate(row, (row.name.slice(0, 193) + ' copy'))}>Duplicate</Button>
          <Button variant="ghost" disabled={busy} onClick={() => changeStatus(row, row.status === 'ARCHIVED' ? 'ACTIVE' : 'ARCHIVED')}>
            {row.status === 'ARCHIVED' ? 'Restore' : 'Archive'}</Button>
          <Button variant="danger" disabled={busy} onClick={() => removeList(row)}>Delete</Button>
        </div>
      </li>)}
    </ul>}
    {list && <ListDetail key={list.id} list={list} token={token} activeLocation={location} radius={radius}
      busy={busy} run={run} onRemember={onRemember} onRefresh={onRefresh} onAddItem={onAddItem}
      onDuplicate={name => duplicate(list, name)} onStatus={status => changeStatus(list, status)}
      onPlan={() => onSelect(list.id)} />}
  </div>;
}

function ListDetail({ list, token, activeLocation, radius, busy, run, onRemember, onRefresh, onAddItem, onDuplicate, onStatus, onPlan }) {
  const [quotes, setQuotes] = useState(null);
  const [edit, setEdit] = useState(null);
  const [adding, setAdding] = useState('');
  const [sort, setSort] = useState('purchased');
  const [filter, setFilter] = useState('all');
  const [comparison, setComparison] = useState(null);
  const [copyName, setCopyName] = useState(list.name.slice(0, 193) + ' copy');
  const addRequest = useRef(null);
  const editable = list.status !== 'ARCHIVED';
  const lat = list.locationLat ?? activeLocation?.lat;
  const lon = list.locationLon ?? activeLocation?.lon;
  const hasLocation = Number.isFinite(lat) && Number.isFinite(lon);
  const base = '/lists/' + list.id;
  const revise = '?revision=' + list.revision;
  const quoteKey = [base, list.revision, lat, lon, radius].join(':');
  const quoteResult = quotes?.key === quoteKey && hasLocation ? quotes : null;

  useEffect(() => {
    if (!hasLocation) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ lat, lon, r: radius });
    apiRequest(base + '/estimate?' + params, { signal: controller.signal }, token)
      .then(rows => { if (!controller.signal.aborted) setQuotes({ key: quoteKey, rows }); })
      .catch(error => { if (!controller.signal.aborted) setQuotes({ key: quoteKey, error: error.message }); });
    return () => controller.abort();
  }, [base, hasLocation, lat, lon, quoteKey, radius, token]);

  function saveItem(item, values, revision = list.revision) {
    return run(async () => {
      onRemember(await apiRequest(base + '/items/' + item.id + '?revision=' + revision, {
        method: 'PUT', body: JSON.stringify(values),
      }, token));
      setEdit(null);
    });
  }

  function addItem(values) {
    return run(async () => {
      const body = JSON.stringify(values);
      if (addRequest.current?.body !== body) addRequest.current = { body, key: crypto.randomUUID() };
      await apiRequest(base + '/items', {
        method: 'POST', body, headers: { 'Idempotency-Key': addRequest.current.key },
      }, token);
      await onRefresh(list.id);
      addRequest.current = null;
      setAdding('');
    });
  }

  function removeItem(item) {
    if (!window.confirm('Remove “' + item.productName + '” from this list?')) return;
    run(async () => {
      await apiRequest(base + '/items/' + item.id + revise, { method: 'DELETE' }, token);
      await onRefresh(list.id);
    });
  }

  function clearPurchased() {
    if (!window.confirm('Remove all purchased items from this list?')) return;
    run(async () => onRemember(await apiRequest(base + '/items/purchased' + revise, { method: 'DELETE' }, token)));
  }

  const purchased = list.items.filter(item => item.purchased).length;
  const byItem = new Map((quoteResult?.rows ?? []).map(row => [row.itemId, row]));
  const cents = [...byItem.values()].reduce((sum, row) => sum + Math.round(Number(row.total) * 100), 0);
  const missing = list.items.length - byItem.size;
  const quantities = new Map();
  list.items.forEach(item => {
    const unit = item.unit ?? 'pack';
    quantities.set(unit, (quantities.get(unit) ?? 0) + Math.round(Number(item.quantity) * 1000));
  });
  const displayed = list.items.filter(item => filter === 'all' || item.purchased === (filter === 'purchased'));
  displayed.sort((a, b) => (sort === 'purchased' ? Number(a.purchased) - Number(b.purchased) :
    sort === 'category' ? (a.category ?? 'Other').localeCompare(b.category ?? 'Other') : 0)
    || a.productName.localeCompare(b.productName));
  const groups = new Map();
  displayed.forEach(item => {
    const label = sort === 'category' ? item.category || 'Other' : sort === 'purchased' ? item.purchased ? 'Purchased' : 'Remaining' : 'Items';
    groups.set(label, [...(groups.get(label) ?? []), item]);
  });

  return <section className="space-y-4 rounded-xl border bg-white p-4 sm:p-6" aria-label="Open shopping list">
    <div className="flex flex-wrap justify-between gap-3">
      <div className="min-w-0"><h2 className="break-words text-xl font-bold">{list.name}</h2>
        {list.description && <p className="whitespace-pre-wrap break-words text-sm text-gray-600">{list.description}</p>}
        <p className="text-sm text-gray-600">{purchased} of {list.items.length} purchased</p>
        {quantities.size > 0 && <p className="text-sm text-gray-600">{[...quantities].map(([unit, quantity]) => quantity / 1000 + ' ' + unit).join(' · ')}</p>}
      </div>
      <Link className="text-sm text-emerald-800 underline" to="/optimize" onClick={onPlan}>Plan remaining basket</Link>
      <Link className="text-sm text-emerald-800 underline" to={'/budgets?listId=' + list.id}>Create a budget for this list</Link>
    </div>
    <details><summary className="cursor-pointer text-sm font-medium">Edit list details</summary>
      <ListMetadata key={list.revision} list={list} location={activeLocation} busy={busy} onSave={values => run(async () => {
        onRemember(await apiRequest(base + revise, { method: 'PUT', body: JSON.stringify(values) }, token));
      })} />
    </details>
    <details><summary className="cursor-pointer text-sm font-medium">Reuse or complete this list</summary>
      <form className="mt-2 flex flex-wrap items-end gap-2" onSubmit={event => { event.preventDefault(); onDuplicate(copyName); }}>
        <label className="text-sm">Copy name<Input value={copyName} onChange={event => setCopyName(event.target.value)} required maxLength={200} /></label>
        <Button type="submit" disabled={busy}>Create reusable copy</Button>
        {editable && list.status !== 'COMPLETED' && <Button type="button" variant="secondary" disabled={busy} onClick={() => onStatus('COMPLETED')}>Mark list completed</Button>}
      </form>
    </details>
    <div className="space-y-1 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-950">
      <h3 className="font-semibold">Nearby price estimate</h3>
      {!hasLocation && <Link to="/stores" state={{ returnTo: '/lists' }} className="underline">Choose a location to estimate prices</Link>}
      {hasLocation && <p>{list.locationLabel || (list.locationLat != null ? 'Preferred list location' : activeLocation?.label) || 'Active shopping location'} · {radius} km radius</p>}
      {hasLocation && !quoteResult && <p role="status">Checking nearby prices…</p>}
      {quoteResult?.error && <p role="alert">{quoteResult.error}</p>}
      {quoteResult?.rows && <p>{byItem.size > 0
        ? 'Lowest-price subtotal across branches for ' + byItem.size + ' of ' + list.items.length + ' entries: R' + (cents / 100).toFixed(2)
        : 'No current nearby prices for this list.'}</p>}
      {quoteResult?.rows && missing > 0 && <p>{missing === 1 ? '1 entry lacks' : missing + ' entries lack'} a usable price. The full cost is unknown.</p>}
      {list.budgetAmount != null && <p>Budget R{Number(list.budgetAmount).toFixed(2)}
        {byItem.size > 0 && cents / 100 > Number(list.budgetAmount) && ' · Priced items alone exceed the budget'}
        {byItem.size > 0 && missing === 0 && new Set([...byItem.values()].map(row => row.storeId)).size === 1 && cents / 100 <= Number(list.budgetAmount) && ' · Listed item prices fit the budget before travel'}</p>}
    </div>
    {editable && <div className="flex flex-wrap gap-2">
      <Button onClick={() => setAdding(value => value === 'catalogue' ? '' : 'catalogue')}>Add catalogue product</Button>
      <Button variant="secondary" onClick={() => setAdding(value => value === 'custom' ? '' : 'custom')}>Add custom item</Button>
      {purchased > 0 && <Button variant="danger" disabled={busy} onClick={clearPurchased}>Clear purchased</Button>}
    </div>}
    {adding === 'catalogue' && <ListProductPicker token={token} label="Find a grocery product" onChoose={product => addItem({ productId: product.productId, quantity: 1 })} />}
    {adding === 'custom' && <ListItemEditor token={token} busy={busy} allowLink={false} onSave={addItem} />}
    <div className="flex flex-wrap gap-3 text-sm">
      <label>Show <select value={filter} onChange={event => setFilter(event.target.value)} className="rounded border p-1">
        <option value="all">All entries</option><option value="remaining">Remaining</option><option value="purchased">Purchased</option>
      </select></label>
      <label>Organise <select value={sort} onChange={event => setSort(event.target.value)} className="rounded border p-1">
        <option value="purchased">Purchased status</option><option value="name">Name</option><option value="category">Category</option>
      </select></label>
    </div>
    {!displayed.length && <p className="text-sm text-gray-600">{list.items.length ? 'No entries match this view.' : 'This list is empty. Add a catalogue product or a custom item.'}</p>}
    {[...groups].map(([label, entries]) => <div key={label} className="space-y-2">
      <h3 className="font-semibold">{label}</h3>
      <ul className="divide-y rounded-lg border">
        {entries.map(item => <li key={item.id} className="space-y-2 p-3">
          <div className="flex flex-wrap items-start gap-3">
            {item.imageUrl && <img src={item.imageUrl} alt="" loading="lazy" className="h-14 w-14 shrink-0 object-contain" />}
            <div className="min-w-[10rem] flex-1 space-y-1">
              <label className="flex items-start gap-2">
                <input type="checkbox" checked={item.purchased} disabled={busy || !editable} className="mt-1"
                  onChange={event => saveItem(item, { ...savedItem(item), purchased: event.target.checked })} />
                <span className={'font-medium ' + (item.purchased ? 'line-through text-gray-600' : '')}>{item.productName}</span>
              </label>
              <p className="text-sm text-gray-600">{[item.brand, item.sizeText, !item.productId && 'Custom item'].filter(Boolean).join(' · ')}</p>
              <p className="text-sm">{item.quantity} {item.unit ?? 'pack'}{item.combinedMeasure != null && ' · ' + item.combinedMeasure + ' ' + item.measureUnit + ' total'}</p>
              <p className="text-sm text-gray-600">{[item.preferredBrand && 'Preferred brand ' + item.preferredBrand,
                item.flexibleBrand && item.substituteProductId ? 'Approved substitute allowed' : 'Exact item'].filter(Boolean).join(' · ')}</p>
              {item.notes && <p className="break-words text-sm text-gray-600">{item.notes}</p>}
            </div>
            <div className="text-sm">
              <p className="font-medium">{byItem.has(item.id) ? formatProductPrice(byItem.get(item.id).total) : 'Price unavailable'}</p>
              {byItem.has(item.id) && <p>{byItem.get(item.id).storeName}</p>}
              {byItem.has(item.id) && byItem.get(item.id).productId !== item.productId && <p>Approved substitute {byItem.get(item.id).productName}</p>}
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {editable && <Button variant="secondary" disabled={busy || Number(item.quantity) <= 1}
              aria-label={'Decrease ' + item.productName} onClick={() => saveItem(item, { ...savedItem(item), quantity: (Number(item.quantity) - 1).toFixed(3) })}>&minus;</Button>}
            {editable && <Button variant="secondary" disabled={busy}
              aria-label={'Increase ' + item.productName} onClick={() => saveItem(item, { ...savedItem(item), quantity: (Number(item.quantity) + 1).toFixed(3) })}>+</Button>}
            {editable && <Button variant="secondary" disabled={busy} onClick={() => setEdit(edit?.id === item.id ? null : { id: item.id, revision: list.revision })}>Edit item</Button>}
            {editable && <Button variant="ghost" disabled={busy} onClick={() => removeItem(item)}>Remove item</Button>}
            {item.productId && <Button variant="ghost" onClick={() => setComparison(item)}>Compare prices</Button>}
          </div>
          {edit?.id === item.id && <ListItemEditor initial={item} token={token} busy={busy} onCancel={() => setEdit(null)} onSave={values => saveItem(item, values, edit.revision)} />}
        </li>)}
      </ul>
    </div>)}
    {comparison && <Suspense fallback={<p>Loading comparison…</p>}>
      <ProductDetails product={{ ...comparison, canonicalName: comparison.productName }}
        lat={lat} lon={lon} radiusKm={radius} token={token} onAddItem={onAddItem} onClose={() => setComparison(null)} />
    </Suspense>}
  </section>;
}
