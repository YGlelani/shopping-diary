import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Calculator, MapPin, Store } from 'lucide-react';
import { ProductImage } from '../components/ProductPresentation';
import ShoppingLocationSelector from '../components/ShoppingLocationSelector';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { apiRequest } from '../api/client';

const rand = new Intl.NumberFormat('en-ZA', { style: 'currency', currency: 'ZAR' });
const money = value => value == null ? 'Unknown' : rand.format(value);
const panel = 'space-y-4 rounded-xl border border-gray-200 bg-white p-4 sm:p-6';
const field = 'mt-1 block w-full rounded-lg border border-gray-300 bg-white p-2 text-sm';
const link = 'font-medium text-emerald-800 underline';
const titles = { ONE_STORE: 'One supermarket', SPLIT: 'Split across supermarkets', ALTERNATIVES: 'Approved alternatives' };
const statuses = {
  EMPTY: 'Your list has no remaining items.', NO_FEASIBLE_PLAN: 'No eligible offers for this strategy.',
  INCOMPLETE: 'Partial estimate. The full basket cost is unknown.',
  KNOWN_COSTS_OVER_BUDGET: 'Known costs already exceed the budget.',
  OVER_BUDGET: 'The estimated trip exceeds the budget.',
  TRANSPORT_UNKNOWN: 'Transport cost is unknown. Overall affordability is unknown.',
  NO_BUDGET: 'Complete estimate.', WITHIN_BUDGET: 'Complete estimate within budget.',
};
const travel = { UNKNOWN: 'Leave transport cost unknown', WALK: 'Walk · R0 transport', DRIVE: 'Drive · estimated cost per km', PUBLIC_TRANSPORT: 'Public transport · enter return fare', FIXED: 'Enter total trip cost' };
const missingReasons = {
  UNLINKED_ITEM: 'Link to a catalogue product', OUTSIDE_SELECTED_BRANCHES: 'No eligible price at these branches',
  NO_NEARBY_BRANCHES: 'No branches match the location and retailer settings',
  REJECTED_OBSERVATION: 'A nearby price observation was disputed or rejected',
  OUT_OF_STOCK_OBSERVATION: 'A nearby observation reports out of stock',
  UNKNOWN_STOCK_OBSERVATION: 'A nearby price has unconfirmed stock',
  STALE_OBSERVATION: 'A nearby price observation is outdated',
};
const defaults = { radiusKm: 15, maxStores: 2, allowAlternatives: true, travelMode: 'UNKNOWN', costPerKm: '', tripCost: '', budget: '', preferredRetailer: '', excludedRetailers: [], rejectedAlternatives: [], confirmedLoyaltyRetailers: [], ignorePromotions: false };
const stamp = value => new Date(value).toLocaleString('en-ZA', { timeZone: 'Africa/Johannesburg' });

function useRows(path, token, revision) {
  const [data, setData] = useState(null);
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    apiRequest(path, { signal: controller.signal }, token).then(rows => {
      if (!controller.signal.aborted) setData({ path, rows });
    }).catch(error => {
      if (!controller.signal.aborted) setData({ path, error: error.message });
    });
    return () => controller.abort();
  }, [path, token, revision]);
  return data?.path === path ? data : null;
}

function Cost({ plan }) {
  return <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
    <div><dt className="text-gray-600">{plan.missingItems ? 'Known groceries' : 'Groceries'}</dt><dd className="font-semibold">{money(plan.totalProductCost)}</dd></div>
    <div><dt className="text-gray-600">Transport</dt><dd className="font-semibold">{money(plan.totalTransportCost)}</dd></div>
    <div><dt className="text-gray-600">Full trip estimate</dt><dd className="font-semibold">{money(plan.grandTotal)}</dd></div>
    {plan.budgetDifference != null && <div><dt className="text-gray-600">{plan.budgetDifference < 0 ? 'Over budget by' : plan.grandTotal == null ? 'Balance after known costs' : 'Estimated balance'}</dt><dd className="font-semibold">{money(Math.abs(plan.budgetDifference))}</dd></div>}
  </dl>;
}

function PlanDetails({ plan, onReject }) {
  return <div className="space-y-4">
    {plan.roundTripKm != null && <p className="text-sm text-gray-600">Estimated round trip {plan.roundTripKm} km in straight lines. Road distances and fares may differ.</p>}
    {plan.stores.map(store => <div className="rounded-lg border border-gray-200 p-3" key={store.storeId}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div><h3 className="flex gap-2 font-semibold"><Store size={18} className="shrink-0 text-emerald-700" />{store.storeName}</h3>
          <p className="break-words text-sm text-gray-600">{[store.retailerName, store.address, store.distanceKm + ' km from origin'].filter(Boolean).join(' · ')}</p>
          <Link to={'/stores/' + store.storeId} className={'text-sm ' + link}>Branch details</Link>
        </div>
        <strong>{money(store.storeSubtotal)}</strong>
      </div>
      <ul className="mt-3 divide-y">{plan.items.filter(item => item.assignedStoreId === store.storeId).map(item => <li key={item.itemId} className="flex items-start gap-3 py-3 text-sm">
        <ProductImage compact product={{ ...item, canonicalName: item.productName }} />
        <div className="min-w-0 flex-1 break-words">
          <p className="font-medium">{item.quantity} × {item.productName}{item.sizeText && ' · ' + item.sizeText}</p>
          <p className="text-gray-600">{item.verificationStatus === 'VERIFIED' ? 'Verified observation' : 'Unverified observation'} · {stamp(item.observedAt)}</p>
          {item.priceType !== 'SHELF' && <p className="text-emerald-800">{({ MULTIBUY: 'Qualifying multibuy', BOGOF: 'Qualifying buy-and-get-free offer', PERCENT: 'Percentage discount', MEMBER: 'Confirmed member price' })[item.priceType] || 'Eligible promotion'}{item.promotionEndsOn && ' · ends ' + item.promotionEndsOn}</p>}
          {item.promotionTerms && <p className="text-gray-600">{item.promotionTerms}</p>}
          {item.priceType !== 'SHELF' && item.shelfLineTotal != null && <p className="text-gray-600">Same quantity at shelf price {money(item.shelfLineTotal)}</p>}
          {item.substitutedFor && <div className="space-y-1 text-amber-900">
            <p>Approved option for {item.substitutedFor}. {item.exactLineTotal == null ? 'Original price unavailable at this branch.' : 'Original at this branch ' + money(item.exactLineTotal) + '.'}</p>
            {onReject && <button type="button" className={link} onClick={() => onReject(item.itemId)}>Keep original and recalculate</button>}
          </div>}
        </div>
        <strong>{money(item.lineTotal)}</strong>
      </li>)}</ul>
    </div>)}
    {plan.unresolved.length > 0 && <div className="rounded-lg bg-amber-50 p-3 text-amber-950">
      <h3 className="font-semibold">Items still to price</h3>
      <ul className="mt-2 space-y-1 text-sm">{plan.unresolved.map(item => <li key={item.itemId} className="break-words">{item.quantity} {item.unit || '×'} {item.name} · {
        missingReasons[item.reason] || 'No eligible offer nearby'
      }</li>)}</ul>
    </div>}
  </div>;
}

export default function Optimizer({ token, listId, lists, onSelect, location, onLocationChange, radius = 15 }) {
  const [settings, setSettings] = useState({ ...defaults, radiusKm: radius });
  const [result, setResult] = useState(null);
  const [shelfResult, setShelfResult] = useState(null);
  const [selected, setSelected] = useState('ONE_STORE');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [name, setName] = useState('');
  const [snapshot, setSnapshot] = useState(null);
  const [page, setPage] = useState(0);
  const [historyVersion, setHistoryVersion] = useState(0);
  const [locationOpen, setLocationOpen] = useState(false);
  const [locationsCleared, setLocationsCleared] = useState(false);
  const pending = useRef(null);
  const list = lists.find(row => row.id === listId);
  const actualLocation = location ?? (!locationsCleared && list?.locationLat != null ? { lat: list.locationLat, lon: list.locationLon, label: list.locationLabel } : null);
  const budgetRows = useRows(listId ? '/lists/' + listId + '/optimization-budgets' : null, token);
  const retailers = useRows('/retailers', token);
  const history = useRows('/shopping-plans?offset=' + page * 25, token, historyVersion);
  const budgetOptions = budgetRows?.rows ?? [];
  const currentKey = JSON.stringify([listId, list?.revision, actualLocation?.lat, actualLocation?.lon, settings]);
  const current = result?.key === currentKey ? result.data : null;
  const plans = current ? [current.bestSingleStore, current.bestSplit, current.bestAlternatives] : [];
  const active = plans.find(plan => plan.strategy === selected);
  const shelfPlan = shelfResult?.key === current?.calculatedAt ? shelfResult?.plan : null;

  function compareShelf() {
    return run(async () => {
      const data = await apiRequest('/lists/' + listId + '/optimize', {
        method: 'POST', body: JSON.stringify({ ...current.assumptions, ignorePromotions: true }),
      }, token);
      setShelfResult({ key: current.calculatedAt, plan: data.recommendation });
    });
  }

  function change(key, value) {
    setSettings(previous => ({ ...previous, [key]: value }));
    setError('');
  }

  function asRequest(values, point) {
    const { budget, costPerKm, tripCost, preferredRetailer, ...rest } = values;
    return { ...rest, lat: point.lat, lon: point.lon, preferredRetailer: preferredRetailer || null,
      budgetId: budget.startsWith('saved:') ? Number(budget.slice(6)) : null,
      budgetAmount: budget && !budget.startsWith('saved:') ? budget : null,
      costPerKm: values.travelMode === 'DRIVE' && costPerKm !== '' ? costPerKm : null,
      tripCost: ['FIXED', 'PUBLIC_TRANSPORT'].includes(values.travelMode) && tripCost !== '' ? tripCost : null,
    };
  }

  async function run(work) {
    setBusy(true);
    setError('');
    setMessage('');
    try { await work(); } catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }

  async function calculate(id, request, key) {
    const data = await apiRequest('/lists/' + id + '/optimize', { method: 'POST', body: JSON.stringify(request) }, token);
    setResult({ key, data });
    setSelected(data.recommendation.strategy === 'NO_PLAN' ? 'ONE_STORE' : data.recommendation.strategy);
    setName(lists.find(row => row.id === id)?.name.slice(0, 195) + ' plan');
  }

  function reject(itemId) {
    const ids = [...new Set([...settings.rejectedAlternatives, itemId])];
    const values = { ...settings, rejectedAlternatives: ids };
    setSettings(values);
    return run(() => calculate(listId, asRequest(values, actualLocation),
      JSON.stringify([listId, list?.revision, actualLocation?.lat, actualLocation?.lon, values])));
  }

  async function write(path, body) {
    const signature = path + JSON.stringify(body);
    if (pending.current?.signature !== signature) pending.current = { signature, id: crypto.randomUUID() };
    const response = await apiRequest(path, { method: 'POST', headers: { 'Idempotency-Key': pending.current.id },
      body: JSON.stringify(body) }, token);
    pending.current = null;
    setPage(0);
    setHistoryVersion(value => value + 1);
    return response;
  }

  function reopen(id) {
    return run(async () => setSnapshot(await apiRequest('/shopping-plans/' + id, {}, token)));
  }

  function recalculate() {
    const id = snapshot.summary.listId;
    const row = lists.find(candidate => candidate.id === id);
    const request = snapshot.assumptions;
    const point = location ?? (row?.locationLat != null ? { lat: row.locationLat, lon: row.locationLon } : null) ?? request;
    const updated = { ...defaults, radiusKm: request.radiusKm, maxStores: request.maxStores, allowAlternatives: request.allowAlternatives,
      travelMode: request.travelMode, costPerKm: request.costPerKm ?? '', tripCost: request.tripCost ?? '',
      budget: request.budgetId ? 'saved:' + request.budgetId : request.budgetAmount == null ? '' : String(request.budgetAmount),
      excludedRetailers: request.excludedRetailers, preferredRetailer: request.preferredRetailer ?? '',
      rejectedAlternatives: request.rejectedAlternatives, confirmedLoyaltyRetailers: request.confirmedLoyaltyRetailers ?? [],
      ignorePromotions: request.ignorePromotions ?? false };
    if (!location && row?.locationLat == null) {
      return run(async () => {
        const data = await apiRequest('/lists/' + id + '/optimize', { method: 'POST', body: JSON.stringify(asRequest(updated, point)) }, token);
        setSnapshot({ ...snapshot, updated: { plan: [data.bestSingleStore, data.bestSplit, data.bestAlternatives].find(plan => plan.strategy === snapshot.summary.strategy), time: data.calculatedAt } });
      });
    }
    onSelect(id);
    setSettings(updated);
    return run(async () => {
      const data = await apiRequest('/lists/' + id + '/optimize', { method: 'POST', body: JSON.stringify(asRequest(updated, point)) }, token);
      setResult({ key: JSON.stringify([id, row?.revision, point.lat, point.lon, updated]), data });
      setSelected(snapshot.summary.strategy);
      setName(snapshot.summary.name);
      setSnapshot({ ...snapshot, updated: { plan: [data.bestSingleStore, data.bestSplit, data.bestAlternatives].find(plan => plan.strategy === snapshot.summary.strategy), time: data.calculatedAt } });
    });
  }

  return <div className="space-y-5">
    <header><h1 className="text-2xl font-bold">Basket optimisation</h1><p className="text-sm text-gray-600">Compare branch prices, store visits and the alternatives you’ve approved.</p></header>
    {error && <p role="alert" className="rounded-lg bg-red-50 p-3 text-red-900">{error}</p>}
    {message && <p role="status" className="rounded-lg bg-emerald-50 p-3 text-emerald-900">{message}</p>}
    <form className={panel} onSubmit={event => { event.preventDefault(); run(() => calculate(listId, asRequest(settings, actualLocation), currentKey)); }}>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="text-sm">Shopping list<select className={field} value={listId ?? ''} onChange={event => { onSelect(Number(event.target.value) || null); setSettings({ ...defaults, radiusKm: radius }); }}>
          <option value="">Choose a list</option>{lists.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
        </select></label>
        <label className="text-sm">Available budget<select className={field} value={settings.budget.startsWith('saved:') ? settings.budget : settings.budget ? 'custom' : ''} onChange={event => change('budget', event.target.value === 'custom' ? '0' : event.target.value)}>
          <option value="">{list?.budgetAmount != null ? 'List limit · ' + money(list.budgetAmount) : 'No budget specified'}</option>
          <option value="custom">Enter a budget</option>
          {budgetOptions.map(row => <option key={row.id} value={'saved:' + row.id}>{row.name} · {money(row.remaining)} remaining</option>)}
        </select></label>
        {settings.budget && !settings.budget.startsWith('saved:') && <label className="text-sm">Budget amount (ZAR)<Input type="number" min="0" max="99999999.99" step="0.01" required value={settings.budget}
          onChange={event => change('budget', event.target.value || '0')} /></label>}
        <label className="text-sm">Search radius<select className={field} value={settings.radiusKm} onChange={event => change('radiusKm', Number(event.target.value))}>
          {[2, 5, 10, 15, 25, 50, 100].map(km => <option key={km} value={km}>{km} km</option>)}
        </select></label>
        <label className="text-sm">Maximum store visits<select className={field} value={settings.maxStores} onChange={event => change('maxStores', Number(event.target.value))}>
          {[1, 2, 3].map(count => <option key={count}>{count}</option>)}
        </select></label>
        <label className="text-sm">Travel cost<select className={field} value={settings.travelMode} onChange={event => change('travelMode', event.target.value)}>
          {Object.entries(travel).map(([key, value]) => <option key={key} value={key}>{value}</option>)}
        </select></label>
        {settings.travelMode === 'DRIVE' && <label className="text-sm">Cost per straight-line kilometre (ZAR)<Input type="number" step="0.01" min="0" max="1000" required value={settings.costPerKm} onChange={event => change('costPerKm', event.target.value)} /></label>}
        {['FIXED', 'PUBLIC_TRANSPORT'].includes(settings.travelMode) && <label className="text-sm">Total return trip cost (ZAR)<Input type="number" step="0.01" min="0" max="99999999.99" required value={settings.tripCost} onChange={event => change('tripCost', event.target.value)} /></label>}
      </div>
      <p className="flex items-center gap-2 text-sm"><MapPin size={17} className="shrink-0" />{actualLocation?.label || (actualLocation ? 'Shopping location selected' : 'Select a shopping location to continue.')}
        <button type="button" className={link} aria-expanded={locationOpen} onClick={() => setLocationOpen(value => !value)}>{actualLocation ? 'Change location' : 'Choose location'}</button></p>
      {budgetRows?.error && <p role="status" className="text-sm text-amber-900">Saved budgets are unavailable. Enter an amount to continue.</p>}
      <Link className={link} to={list ? '/lists?listId=' + list.id : '/lists'}>{list ? 'Edit list quantities' : 'Manage shopping lists'}</Link>
      <details><summary className="cursor-pointer text-sm font-medium">Retailer and substitution preferences</summary>
        <div className="mt-3 space-y-3">
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={settings.allowAlternatives} onChange={event => change('allowAlternatives', event.target.checked)} /> Compare alternatives already approved on this list</label>
          {settings.rejectedAlternatives.length > 0 && <button type="button" className={'text-sm ' + link} onClick={() => { change('rejectedAlternatives', []); }}>Restore rejected alternatives</button>}
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={settings.ignorePromotions} onChange={event => change('ignorePromotions', event.target.checked)} /> Use shelf prices only</label>
          <fieldset className="grid gap-2 sm:grid-cols-3" disabled={settings.ignorePromotions}>
            <legend className="mb-2 text-sm font-medium">I have the required loyalty card for</legend>
            {retailers?.rows?.filter(row => row.active).map(row => <label className="flex items-center gap-2 text-sm" key={row.id}><input type="checkbox"
              checked={settings.confirmedLoyaltyRetailers.includes(row.id)} onChange={event => change('confirmedLoyaltyRetailers',
                event.target.checked ? [...settings.confirmedLoyaltyRetailers, row.id] : settings.confirmedLoyaltyRetailers.filter(id => id !== row.id))} />{row.name}</label>)}
          </fieldset>
          <label className="block text-sm">Preferred retailer for otherwise comparable plans<select className={field} value={settings.preferredRetailer} onChange={event => change('preferredRetailer', Number(event.target.value) || '')}>
            <option value="">Any retailer</option>{retailers?.rows?.filter(row => row.active).map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
          </select></label>
          {retailers?.error && <p role="status" className="text-sm text-amber-900">Retailer preferences are temporarily unavailable.</p>}
          <fieldset className="grid gap-2 sm:grid-cols-3"><legend className="mb-2 text-sm font-medium">Exclude retailers</legend>
            {retailers?.rows?.filter(row => row.active).map(row => <label key={row.id} className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={settings.excludedRetailers.includes(row.id)} onChange={event => change('excludedRetailers',
                event.target.checked ? [...settings.excludedRetailers, row.id] : settings.excludedRetailers.filter(id => id !== row.id))} />{row.name}</label>)}
          </fieldset>
        </div>
      </details>
      <Button disabled={!list || !actualLocation || busy} type="submit" className="gap-2"><Calculator size={18} />{busy ? 'Working…' : 'Optimise My Basket'}</Button>
    </form>
    {locationOpen && <ShoppingLocationSelector token={token} location={actualLocation} onLocationChange={(...args) => { setLocationsCleared(args[0] == null); onLocationChange(...args); setLocationOpen(false); }} />}
    {current && <section className={panel}>
      <h2 className="text-xl font-bold">Compare shopping strategies</h2>
      <p className="text-sm text-gray-600">{current.branchesSearched} eligible branches searched · Prices checked {stamp(current.calculatedAt)}{current.budgetAmount != null && ' · Available budget ' + money(current.budgetAmount)}</p>
      <p className="text-sm text-gray-600">{current.recommendation.items.length ? (current.searchLimited ? 'Best plan found · ' : 'Recommended plan · ') + titles[current.recommendation.strategy] + '. The engine prioritises coverage, then known cost and fewer visits.' : statuses[current.recommendation.budgetStatus]}
        {current.recommendation.totalTransportCost == null && current.recommendation.items.length > 0 && ' Rankings compare grocery costs while transport is unknown.'}</p>
      {current.savingsVsBestSingle > 0 && <p className="rounded-lg bg-emerald-50 p-3 text-sm text-emerald-900">Estimated saving of {money(current.savingsVsBestSingle)} versus the complete one-store trip, including transport.</p>}
      <div className="grid gap-3 lg:grid-cols-3">
        {plans.map(plan => <div key={plan.strategy} className={'space-y-3 rounded-lg border p-3 ' + (selected === plan.strategy ? 'border-emerald-700' : 'border-gray-200')}>
          <h3 className="font-semibold">{titles[plan.strategy]}</h3>
          <p className="text-sm">{plan.items.length} of {current.totalItems} entries · {plan.stores.length} {plan.stores.length === 1 ? 'visit' : 'visits'}</p>
          <p className={'rounded px-2 py-1 text-sm '+ (plan.grandTotal == null ? 'bg-amber-50 text-amber-950' : plan.budgetStatus === 'OVER_BUDGET' ? 'bg-red-50 text-red-900' : 'bg-gray-50 text-gray-800')}>{plan.strategy === 'SPLIT' && settings.maxStores === 1 ? 'Allow more store visits to compare a split.' :
            plan.strategy === 'ALTERNATIVES' && !settings.allowAlternatives ? 'Alternatives are turned off.' : statuses[plan.budgetStatus]}</p>
          <Cost plan={plan} />
          {[['promoted lines', item => item.priceType !== 'SHELF'], ['alternatives', item => item.substitutedFor],
            ['unverified prices', item => item.verificationStatus !== 'VERIFIED']].map(([label, predicate]) => {
              const count = plan.items.filter(predicate).length;
              return count > 0 && <p key={label} className="text-sm text-gray-600">{count} {label}</p>;
            })}
          <Button type="button" variant="secondary" aria-pressed={selected === plan.strategy} onClick={() => setSelected(plan.strategy)}>View strategy</Button>
        </div>)}
      </div>
      {!settings.ignorePromotions && <Button type="button" variant="secondary" disabled={busy} onClick={compareShelf}>Compare with shelf prices only</Button>}
      {shelfPlan && <div className="rounded-lg border border-gray-200 p-3">
        <h3 className="font-semibold">Shelf-price comparison</h3>
        <p className="mb-2 text-sm text-gray-600">{shelfPlan.items.length} of {current.totalItems} entries priced. {statuses[shelfPlan.budgetStatus]}</p>
        <Cost plan={shelfPlan} />
        <p className="mt-2 text-sm text-gray-600">Shelf prices can change which branches make the best trip. Full trip comparisons require both plans to cover all entries and have known transport.</p>
      </div>}
      <details><summary className="cursor-pointer text-sm font-medium">Branches checked and price policy</summary>
        <p className="my-2 text-sm text-gray-600">Branch prices require fresh, in-stock observations. Coupons and bundles without validated terms are excluded. Member prices require your confirmation. Check offer terms and availability before travelling.</p>
        <ul className="grid gap-1 text-sm sm:grid-cols-2">{current.branches.map(branch => <li key={branch.storeId}>{branch.storeName} · {branch.distanceKm} km</li>)}</ul>
      </details>
    </section>}
    {active && <section className={panel}>
      <h2 className="text-lg font-bold">{titles[active.strategy]} shopping plan</h2>
      <PlanDetails plan={active} onReject={busy ? null : reject} />
      {active.fingerprint && <form className="flex flex-wrap items-end gap-2" onSubmit={event => {
        event.preventDefault();
        run(async () => {
          await write('/shopping-plans', { listId, name, strategy: active.strategy, fingerprint: active.fingerprint, assumptions: current.assumptions });
          setMessage('Shopping plan saved. Estimates can be reviewed in Saved shopping plans.');
        });
      }}>
        <label className="min-w-0 flex-1 text-sm">Plan name<Input value={name} required maxLength={200} onChange={event => setName(event.target.value)} /></label>
        <Button disabled={busy} type="submit">Save selected plan</Button>
      </form>}
      <p className="text-sm text-gray-600">Saving records the estimate and its observation dates. Confirm prices and stock before shopping.</p>
    </section>}
    <section className={panel}>
      <h2 className="text-lg font-bold">Saved shopping plans</h2>
      {history?.error && <p role="alert">{history.error}</p>}
      {!history && <p role="status">Loading saved plans…</p>}
      {history?.rows?.length === 0 && <p>No plans saved on this page.</p>}
      <ul className="divide-y">{history?.rows?.map(row => <li className="flex flex-wrap items-center justify-between gap-2 py-2" key={row.id}>
        <div className="min-w-0 break-words"><p className="font-semibold">{row.name}</p><p className="text-sm text-gray-600">{row.listName} · {titles[row.strategy]} · {stamp(row.createdAt)}</p></div>
        <Button disabled={busy} variant="secondary" type="button" onClick={() => reopen(row.id)}>Open plan <ArrowRight size={16} /></Button>
      </li>)}</ul>
      <div className="flex gap-2">
        {page > 0 && <Button variant="secondary" onClick={() => setPage(page - 1)}>Previous plans</Button>}
        {history?.rows?.length === 25 && <Button variant="secondary" onClick={() => setPage(page + 1)}>Older plans</Button>}
      </div>
    </section>
    {snapshot && <section className={panel}>
      <h2 className="break-words text-lg font-bold">{snapshot.summary.name}</h2>
      <p className="text-sm text-gray-600">Historical estimate from {stamp(snapshot.summary.calculatedAt)} · {travel[snapshot.assumptions.travelMode]}</p>
      <Cost plan={snapshot.estimate} />
      {snapshot.updated && <div className="space-y-2 rounded-lg border p-3">
        <h3 className="font-semibold">Latest estimate · {stamp(snapshot.updated.time)}</h3><Cost plan={snapshot.updated.plan} />
        <p className="text-sm">{statuses[snapshot.updated.plan.budgetStatus]} {snapshot.updated.plan.items.length} priced entries.</p>
      </div>}
      <PlanDetails plan={snapshot.estimate} />
      <div className="flex flex-wrap gap-2">
        <Button disabled={busy || !snapshot.summary.listId} onClick={recalculate}>Recalculate with latest prices</Button>
        <Button disabled={busy} variant="secondary" onClick={() => run(async () => {
          const result = await write('/shopping-plans/' + snapshot.summary.id + '/copy', { name: snapshot.summary.name.slice(0, 195) + ' copy' });
          setSnapshot(await apiRequest('/shopping-plans/' + result.id, {}, token));
        })}>Duplicate plan</Button>
        <Button disabled={busy} variant="danger" onClick={() => {
          if (window.confirm('Delete this saved shopping plan?')) run(async () => {
            await apiRequest('/shopping-plans/' + snapshot.summary.id, { method: 'DELETE' }, token);
            setSnapshot(null); setPage(0); setHistoryVersion(value => value + 1);
          });
        }}>Delete plan</Button>
      </div>
    </section>}
  </div>;
}
