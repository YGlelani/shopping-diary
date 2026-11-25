import { ProductImage } from '../components/ProductPresentation';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CalendarDays, MapPin, Search, Tag } from 'lucide-react';
import { apiRequest } from '../api/client';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import ShoppingLocationSelector from '../components/ShoppingLocationSelector';
import { formatProductPrice } from '../utils/productFormatting';

const card = 'space-y-3 rounded-xl border border-gray-200 bg-white p-4';
const control = 'block w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm';
const link = 'text-sm font-medium text-emerald-800 underline';
const kindNames = { DISCOUNT: 'Fixed price', PERCENT: 'Percentage discount', MULTIBUY: 'Multibuy', BOGOF: 'Buy and get free', MEMBER: 'Member price', COUPON: 'Coupon', BUNDLE: 'Bundle' };
const qualifiers = {
  NOT_CURRENT: 'Outside the confirmed offer window.',
  BRANCH_UNCONFIRMED: 'Local branch applicability is unconfirmed.',
  PREFERENCE_RESTRICTION: 'The deal conflicts with this list item’s required brand.',
  CHECK_TERMS: 'Check the retailer terms. The basket estimate excludes this discount.',
  LOYALTY_REQUIRED: 'Confirm that you have the required loyalty card.',
  QUANTITY_REQUIRED: 'Your quantity is below the offer requirement.',
  PRICE_EVIDENCE_REQUIRED: 'A current in-stock branch price is needed to estimate this offer.',
  NO_SAVING: 'The eligible shelf cost is as low or lower for this quantity.',
  QUALIFIES: 'This quantity qualifies at the selected branch.',
};
const money = value => value == null ? 'Unavailable' : formatProductPrice(value);
const localDate = value => value && new Intl.DateTimeFormat('en-ZA', { timeZone: 'UTC', day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(value + 'T00:00:00Z'));
const evidenceDate = value => new Date(value).toLocaleDateString('en-ZA', { timeZone: 'Africa/Johannesburg' });

function useSpecialsResource(path, token, revision) {
  const [response, setResponse] = useState(null);
  const [version, setVersion] = useState(0);
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    apiRequest(path, { signal: controller.signal }, token).then(data => {
      if (!controller.signal.aborted) setResponse({ path, version, revision, data });
    }).catch(error => {
      if (!controller.signal.aborted) setResponse({ path, version, revision, error: error.message });
    });
    return () => controller.abort();
  }, [path, version, token, revision]);
  return { ...(response?.path === path && response?.version === version && response?.revision === revision ? response : {}), retry: () => setVersion(value => value + 1) };
}

function Status({ request }) {
  if (request.error) return <p role="alert" className="text-sm text-red-800">{request.error}{' '}<button className={link} onClick={request.retry}>Retry</button></p>;
  return request.data == null ? <p role="status" className="text-sm text-gray-600">Loading offers…</p> : null;
}

function offerText(deal) {
  if (deal.kind === 'MULTIBUY') return 'Buy ' + deal.minimumQuantity + ' for ' + money(deal.offerAmount);
  if (deal.kind === 'BOGOF' && deal.freeQuantity) return 'Buy ' + deal.minimumQuantity + ', get ' + deal.freeQuantity + ' free';
  if (deal.kind === 'PERCENT' && deal.percentOff) return deal.percentOff + '% advertised discount';
  return deal.offerAmount == null ? kindNames[deal.kind] : money(deal.offerAmount);
}

function scopeText(deal) {
  if (deal.scope === 'BRANCH') return deal.branchName || 'Branch-restricted offer';
  if (deal.scope === 'REGION') return [deal.region, deal.branchName].filter(Boolean).join(' · ');
  if (deal.scope === 'NATIONAL') return ['National offer', deal.branchName].filter(Boolean).join(' · ');
  return deal.scope === 'ONLINE' ? 'Online channel' : 'Branch applicability unconfirmed';
}

function DealCard({ deal, onOpen }) {
  return <article className={card + ' flex min-w-0 flex-col'}>
    <div className="flex items-center gap-2 text-sm text-gray-700">
      {deal.logoUrl && <img src={deal.logoUrl} alt="" className="h-6 w-6 rounded object-contain" loading="lazy" />}
      <strong className="break-words">{deal.retailer}</strong>
      <span className="ml-auto shrink-0 rounded bg-gray-100 px-2 py-1 text-xs">{deal.validity === 'ACTIVE' ? deal.verification.toLowerCase() : deal.validity.toLowerCase()}</span>
    </div>
    <ProductImage product={{ imageUrl: deal.imageUrl, canonicalName: deal.productName }} />
    <div className="min-w-0 break-words">
      <p className="text-xs text-gray-600">{[deal.brand, deal.sizeText, deal.category].filter(Boolean).join(' · ')}</p>
      <h2 className="text-base font-semibold">{deal.productName}</h2>
      <p className="mt-1 text-lg font-bold text-emerald-900">{offerText(deal)}</p>
      {deal.shelfTotal != null && <p className="text-sm text-gray-600">Observed shelf total {money(deal.shelfTotal)} for {deal.quantity}</p>}
      {deal.saving != null && <p className="text-sm text-emerald-900">Save {money(deal.saving)} on {deal.quantity} at this branch</p>}
    </div>
    <div className="space-y-1 text-sm text-gray-700">
      <p className="flex items-start gap-2"><MapPin size={15} className="mt-0.5 shrink-0" />{scopeText(deal)}</p>
      <p className="flex items-start gap-2"><CalendarDays size={15} className="mt-0.5 shrink-0" />Through {localDate(deal.endsOn)}</p>
      {deal.loyaltyProgram && <p className="font-medium text-amber-900">{deal.loyaltyProgram} required</p>}
      {deal.itemId && <p className="rounded bg-gray-50 p-2">List quantity {deal.quantity}. {qualifiers[deal.qualification]}</p>}
    </div>
    <Button type="button" variant="secondary" className="self-start" onClick={() => onOpen(deal)}>View conditions</Button>
  </article>;
}

const emptyFilters = { q: '', retailer: '', category: '', brand: '', kind: '', verification: '', branchId: '', loyalty: '', validOn: '', minPrice: '', maxPrice: '', sort: 'ENDING' };

export default function Specials({ token, lists, location, onLocationChange, radius, onRadiusChange, onAddItem }) {
  const [params, setParams] = useSearchParams();
  const [filters, setFilters] = useState(emptyFilters);
  const [applied, setApplied] = useState(emptyFilters);
  const [page, setPage] = useState(0);
  const [showLocation, setShowLocation] = useState(false);
  const [details, setDetails] = useState(null);
  const detailsPanel = useRef(null);
  useEffect(() => {
    if (details) detailsPanel.current?.focus();
  }, [details]);
  const [quantity, setQuantity] = useState('1');
  const [member, setMember] = useState(false);
  const [coverageOpen, setCoverageOpen] = useState(false);
  const listId = params.get('listId');
  const list = lists.find(row => String(row.id) === listId);
  const point = list?.locationLat != null ? { lat: list.locationLat, lon: list.locationLon, label: list.locationLabel } : location;
  const coordinates = new URLSearchParams();
  if (point) { coordinates.set('lat', point.lat); coordinates.set('lon', point.lon); }
  const query = new URLSearchParams(coordinates);
  query.set('radius', radius);
  query.set('offset', page * 20);
  for (const [key, value] of Object.entries(applied)) if (value) query.set(key, value);
  const rows = useSpecialsResource((listId ? '/lists/' + listId + '/specials?' : '/specials?') + query, token, list?.revision);
  const retailers = useSpecialsResource('/retailers', token);
  const categories = useSpecialsResource('/products/categories', token);
  const branches = useSpecialsResource(point ? '/stores/nearby?' + coordinates + '&r=' + radius + '&limit=100' : null, token);
  const coverage = useSpecialsResource(coverageOpen ? '/specials/coverage' : null, token);
  const detailQuery = new URLSearchParams(coordinates);
  detailQuery.set('radius', radius);
  detailQuery.set('quantity', quantity);
  detailQuery.set('memberConfirmed', member);
  if (details?.branchId) detailQuery.set('branchId', details.branchId);
  const detail = useSpecialsResource(details && '/specials/' + details.id + '?' + detailQuery, token);

  function change(event) {
    const { name, value } = event.target;
    setFilters(previous => ({ ...previous, [name]: value }));
  }

  function open(deal) {
    setQuantity(String(deal.quantity));
    setMember(false);
    setDetails(deal);
  }

  return <div className="space-y-5">
    <header className="flex items-center gap-3"><Tag className="text-emerald-700" aria-hidden="true" />
      <div><h1 className="text-2xl font-bold">Specials &amp; Deals</h1><p className="text-sm text-gray-600">Check the conditions and price for the quantity you plan to buy.</p></div>
    </header>
    <form className={card} onSubmit={event => { event.preventDefault(); setApplied(filters); setPage(0); setDetails(null); }}>
      <div className="flex flex-wrap items-end gap-2">
        <label className="min-w-48 flex-1 text-sm font-medium">Search specials<Input name="q" value={filters.q} onChange={change} maxLength={120} placeholder="Product, brand, package or retailer" /></label>
        <Button type="submit" className="gap-2"><Search size={16} /> Apply filters</Button>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <label className="text-sm">Retailer<select className={control} name="retailer" value={filters.retailer} onChange={change}>
          <option value="">All retailers</option>{retailers.data?.filter(row => row.active).map(row => <option key={row.id} value={row.slug}>{row.name}</option>)}
        </select></label>
        <label className="text-sm">Category<select className={control} name="category" value={filters.category} onChange={change}>
          <option value="">All categories</option>{categories.data?.map(row => <option key={row}>{row}</option>)}
        </select></label>
        <label className="text-sm">Sort by<select className={control} name="sort" value={filters.sort} onChange={change}>
          <option value="ENDING">Ending soon</option><option value="PRICE">Lowest eligible offer total</option><option value="SAVING">Highest verified saving</option>
          <option value="DISCOUNT">Highest verified discount</option><option value="NEAREST">Nearest eligible branch</option><option value="NEWEST">Recently collected</option>
        </select></label>
        <label className="text-sm">Shopping list<select className={control} value={listId || ''} onChange={event => {
          setParams(event.target.value ? { listId: event.target.value } : {}); setPage(0); setDetails(null);
        }}>
          <option value="">Browse all specials</option>{lists.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
        </select></label>
      </div>
      <details><summary className="cursor-pointer text-sm font-medium">More filters</summary>
        <div className="mt-2 grid gap-3 sm:grid-cols-3">
          <label className="text-sm">Brand<Input name="brand" value={filters.brand} onChange={change} /></label>
          <label className="text-sm">Offer type<select className={control} name="kind" value={filters.kind} onChange={change}>
            <option value="">All types</option>{Object.entries(kindNames).map(([key, value]) => <option key={key} value={key}>{value}</option>)}
          </select></label>
          <label className="text-sm">Loyalty<select className={control} name="loyalty" value={filters.loyalty} onChange={change}>
            <option value="">Any offer</option><option value="false">No card needed</option><option value="true">Loyalty required</option>
          </select></label>
          <label className="text-sm">Verification<select className={control} name="verification" value={filters.verification} onChange={change}>
            <option value="">Any evidence</option><option>VERIFIED</option><option>ADVERTISED</option><option>UNVERIFIED</option>
          </select></label>
          <label className="text-sm">Valid on<Input name="validOn" type="date" value={filters.validOn} onChange={change} /></label>
          {point && <label className="text-sm">Branch<select className={control} name="branchId" value={filters.branchId} onChange={change}>
            <option value="">All nearby branches</option>{branches.data?.map(row => <option value={row.id} key={row.id}>{row.name}</option>)}
          </select></label>}
          <label className="text-sm">Minimum advertised amount (R)<Input name="minPrice" type="number" min="0" step=".01" value={filters.minPrice} onChange={change} /></label>
          <label className="text-sm">Maximum advertised amount (R)<Input name="maxPrice" type="number" min="0" step=".01" value={filters.maxPrice} onChange={change} /></label>
        </div>
      </details>
    </form>
    <section className={card}>
      <div className="flex flex-wrap items-center gap-3">
        <p className="flex items-center gap-2 text-sm"><MapPin size={16} />{point?.label || (point ? 'Shopping location selected' : 'Browsing all locations')}</p>
        {list?.locationLat == null && <button type="button" className={link} aria-expanded={showLocation} onClick={() => setShowLocation(value => !value)}>Choose shopping location</button>}
        {point && <label className="flex items-center gap-2 text-sm">Radius<select className={control} value={radius} onChange={event => { onRadiusChange(Number(event.target.value)); setPage(0); }}>
          {[2, 5, 10, 15, 25, 50, 100].map(km => <option key={km} value={km}>{km} km</option>)}
        </select></label>}
      </div>
      {showLocation && <ShoppingLocationSelector location={location} token={token} onLocationChange={(...args) => {
        onLocationChange(...args); setShowLocation(false); setPage(0); setDetails(null);
        setApplied(previous => ({ ...previous, branchId: '' })); setFilters(previous => ({ ...previous, branchId: '' }));
      }} />}
    </section>
    {details && <section className={card} aria-label="Offer conditions" tabIndex={-1} ref={detailsPanel}>
      <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-xl font-bold">Offer conditions</h2>
        <Button type="button" variant="secondary" onClick={() => setDetails(null)}>Close conditions</Button></div>
      <div className="space-y-1"><p className="font-semibold">{details.title || details.productName}</p>
        <p>{scopeText(details)} · {details.retailer}</p><p>{offerText(details)}</p>
        <p className="text-sm">{localDate(details.startsOn)} to {localDate(details.endsOn)}, inclusive in South Africa.</p>
        {details.description && <p className="break-words text-sm">{details.description}</p>}
        {details.loyaltyProgram && <p className="text-sm font-medium">{details.loyaltyProgram} required.</p>}
      </div>
      <div className="flex flex-wrap items-end gap-4">
        <label className="text-sm">Quantity to check<Input type="number" min="1" max="1000000" step="1" value={quantity} onChange={event => setQuantity(event.target.value)} /></label>
        {details.loyaltyProgram && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={member} onChange={event => setMember(event.target.checked)} /> I have the required loyalty card</label>}
      </div>
      <Status request={detail} />
      {detail.data && <div className="space-y-2 rounded-lg bg-emerald-50 p-3 text-sm">
        <p role="status" className="font-semibold">{qualifiers[detail.data.qualification]}</p>
        {detail.data.shelfTotal != null && <p>Shelf total for {detail.data.quantity} · {money(detail.data.shelfTotal)}</p>}
        {detail.data.offeredTotal != null && <p>Offer total for {detail.data.quantity} · {money(detail.data.offeredTotal)}</p>}
        {detail.data.saving != null && <p>Verified shelf-price saving · {money(detail.data.saving)} ({detail.data.savingPercent}%) before travel</p>}
        {detail.data.saving == null && <p>A comparable, verified shelf saving is unavailable.</p>}
        <p>Stock evidence · {detail.data.observedAt ? 'eligible observation from ' + evidenceDate(detail.data.observedAt) : 'current stock unconfirmed'}</p>
        <p className="break-words">Source · {detail.data.sourceType} · {evidenceDate(detail.data.collectedAt)}{detail.data.sourceReference && ' · ' + detail.data.sourceReference}</p>
      </div>}
      <div className="flex flex-wrap items-center gap-3"><Button onClick={() => onAddItem(details.productId)}>Add product to shopping list</Button>
        {listId && <Link className={link} to={'/lists?listId=' + listId}>Open this shopping list</Link>}
        <Link className={link} to="/optimize">Compare trips and transport</Link>
      </div>
    </section>}
    <section className="space-y-3">
      <h2 className="text-xl font-semibold">{listId ? 'Specials for your shopping list' : point ? 'Nearby specials' : applied.validOn ? 'Specials for selected date' : 'Current specials'}</h2>
      <Status request={rows} />
      {rows.data?.length === 0 && <p className={card}>No specials match these filters. Try a different retailer, date or location.</p>}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{rows.data?.slice(0, 20).map(deal => <DealCard key={deal.id + ':' + (deal.itemId ?? '')} deal={deal} onOpen={open} />)}</div>
      <div className="flex gap-2">{page > 0 && <Button variant="secondary" onClick={() => setPage(page - 1)}>Previous offers</Button>}
        {rows.data?.length > 20 && <Button variant="secondary" onClick={() => setPage(page + 1)}>Next offers</Button>}</div>
    </section>
    <details className={card} onToggle={event => setCoverageOpen(event.currentTarget.open)}>
      <summary className="cursor-pointer font-semibold">Retailer coverage</summary>
      {coverageOpen && <><Status request={coverage} /><ul className="grid gap-2 text-sm sm:grid-cols-2">
        {coverage.data?.map(row => <li key={row.retailerId}><strong>{row.retailer}</strong> · {row.currentOffers} current offers · {row.verifiedOffers} verified</li>)}
      </ul></>}
    </details>
  </div>;
}
