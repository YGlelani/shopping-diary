import { lazy, Suspense, useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { MapPin } from 'lucide-react';
import { apiRequest } from '../api/client';
import BranchProposal from '../components/BranchProposal';
import ShoppingLocationSelector from '../components/ShoppingLocationSelector';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { BranchIdentity, Coverage, Directions } from '../components/BranchPresentation';
import { formatProductPrice } from '../utils/productFormatting';

const StoreMap = lazy(() => import('../components/LocationPicker'));
const PAGE_SIZE = 20;

export default function NearbyStores({ location, onLocationChange, token, radius, onRadiusChange }) {
  const route = useLocation();
  const returnTo = route.state?.returnTo;
  const initial = { retailer: '', area: '', priced: '', promotions: false, sort: 'distance', productId: route.state?.productId ?? '' };
  const [filters, setFilters] = useState(initial);
  const [applied, setApplied] = useState(initial);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState(null);
  const [retailers, setRetailers] = useState([]);
  const [selected, setSelected] = useState(null);
  const [mapVisible, setMapVisible] = useState(false);
  const [productQuery, setProductQuery] = useState(route.state?.productName ?? '');
  const [products, setProducts] = useState(null);
  const [retry, setRetry] = useState(0);
  const lat = location?.lat;
  const lon = location?.lon;
  const params = new URLSearchParams({ lat, lon, r: radius, offset: page * PAGE_SIZE, limit: PAGE_SIZE + 1 });
  Object.entries(applied).forEach(([name, value]) => { if (value) params.set(name, value); });
  const query = params.toString();
  const key = query + ':' + retry;
  const current = location && result?.key === key ? result : null;
  const stores = current?.rows?.slice(0, PAGE_SIZE) ?? [];

  useEffect(() => {
    const controller = new AbortController();
    apiRequest('/retailers', { signal: controller.signal }, token)
      .then(rows => { if (!controller.signal.aborted) setRetailers(rows.filter(row => row.active)); }).catch(() => {});
    return () => controller.abort();
  }, [token]);

  useEffect(() => {
    if (lat == null || lon == null) return;
    const controller = new AbortController();
    apiRequest('/stores/nearby?' + query, { signal: controller.signal }, token)
      .then(rows => { if (!controller.signal.aborted) setResult({ key, rows }); })
      .catch(error => { if (!controller.signal.aborted) setResult({ key, error: error.message }); });
    return () => controller.abort();
  }, [key, query, lat, lon, token]);

  function change(event) {
    const { name, value, checked, type } = event.target;
    setFilters(previous => ({ ...previous, [name]: type === 'checkbox' ? checked : value }));
  }

  async function findProduct(event) {
    event.preventDefault();
    setProducts({ loading: true });
    try {
      const params = new URLSearchParams({ q: productQuery.trim(), limit: '10' });
      setProducts({ rows: await apiRequest('/products/search?' + params, {}, token) });
    } catch (error) {
      setProducts({ error: error.message });
    }
  }

  return (
    <div className="mx-auto max-w-5xl space-y-5">
      <h1 className="text-2xl font-bold">Nearby stores</h1>
      <ShoppingLocationSelector location={location} token={token} onLocationChange={(...args) => {
        setPage(0); setSelected(null); onLocationChange(...args);
      }} />
      {location && <>
        {returnTo && <Link className="text-emerald-800 underline" to={returnTo}>Continue with this location</Link>}
        <div className="space-y-3 rounded-xl border bg-white p-4">
          <form className="flex flex-wrap items-end gap-2" onSubmit={findProduct}>
            <label className="flex-1 text-sm">Find stores with a price for a product
              <Input value={productQuery} minLength={2} maxLength={120} required onChange={event => setProductQuery(event.target.value)} />
            </label>
            <Button variant="secondary" type="submit" disabled={products?.loading}>Find products</Button>
          </form>
          {products?.error && <p role="alert">{products.error}</p>}
          {products?.rows?.length === 0 && <p>No catalogue products match.</p>}
          <form className="grid gap-3 text-sm sm:grid-cols-3" onSubmit={event => {
            event.preventDefault(); setPage(0); setSelected(null); setApplied(filters);
          }}>
            <label>Search radius<select className="block w-full rounded border p-2" value={radius} onChange={event => {
              setPage(0); setSelected(null); onRadiusChange(Number(event.target.value));
            }}>{[2, 5, 10, 15, 25, 50, 100].map(km => <option key={km} value={km}>{km} km</option>)}</select></label>
            <label>Retailer<select className="block w-full rounded border p-2" name="retailer" value={filters.retailer} onChange={change}>
              <option value="">All retailers</option>{retailers.map(row => <option key={row.id} value={row.slug}>{row.name}</option>)}
            </select></label>
            <label>Town or province<Input name="area" value={filters.area} maxLength={100} onChange={change} /></label>
            <label>Price coverage<select className="block w-full rounded border p-2" name="priced" value={filters.priced} onChange={change}>
              <option value="">All branches</option><option value="true">With eligible prices</option><option value="false">Without eligible prices</option>
            </select></label>
            <label>Exact product<select className="block w-full rounded border p-2" name="productId" value={filters.productId} onChange={event => {
              const productId = event.target.value;
              setFilters(previous => ({ ...previous, productId, sort: productId ? previous.sort : 'distance' }));
            }}>
              <option value="">Any product</option>
              {filters.productId && !(products?.rows ?? []).some(row => String(row.productId) === String(filters.productId)) &&
                <option value={filters.productId}>{route.state?.productName || 'Selected product'}</option>}
              {(products?.rows ?? []).map(row => <option key={row.productId} value={row.productId}>
                {[row.canonicalName, row.brand, row.sizeText, row.barcode].filter(Boolean).join(' · ')}
              </option>)}
            </select></label>
            <label>Sort by<select className="block w-full rounded border p-2" name="sort" value={filters.sort} onChange={change}>
              <option value="distance">Nearest</option><option value="name">Store name</option><option value="freshness">Newest observation</option>
              {filters.productId && <option value="price">Lowest eligible single-package price</option>}
            </select></label>
            <label><input type="checkbox" name="promotions" checked={filters.promotions} onChange={change} /> With current branch promotions</label>
            <Button type="submit">Apply filters</Button>
          </form>
        </div>
        <p className="flex items-center gap-2 text-sm text-gray-600"><MapPin size={16} /> Distances are straight-line estimates.</p>
        {!current && <p role="status">Finding nearby branches…</p>}
        {current?.error && <div role="alert">{current.error} <Button variant="secondary" onClick={() => setRetry(value => value + 1)}>Retry</Button></div>}
        {current?.rows?.length === 0 && <p>No registered branches match within {radius} km. Try a wider radius or clear a filter.</p>}
        {stores.length > 0 && <>
          <Button variant="secondary" aria-expanded={mapVisible} onClick={() => setMapVisible(value => !value)}>
            {mapVisible ? 'Hide map' : 'Show map'}
          </Button>
          {mapVisible && <Suspense fallback={<p role="status">Loading map…</p>}>
            <StoreMap key={[lat, lon].join(':')} location={location} stores={stores} selected={selected} onSelect={setSelected} />
          </Suspense>}
          <ul className="grid gap-3 sm:grid-cols-2">
            {stores.map(store => <li key={store.id} className={'space-y-2 rounded-xl border bg-white p-4 ' + (selected === store.id ? 'ring-2 ring-emerald-700' : '')}>
              <BranchIdentity store={store} />
              <p className="text-sm">{store.distanceKm.toFixed(1)} km straight line</p>
              <Coverage store={store} />
              {store.productPrice != null && <p className="font-medium">{formatProductPrice(store.productPrice)} per selected package</p>}
              {store.phone && <a className="block text-sm underline" href={'tel:' + store.phone}>{store.phone}</a>}
              <div className="flex flex-wrap items-center gap-3">
                <Link className="text-sm text-emerald-800 underline" to={'/stores/' + store.id}>View branch and prices</Link>
                {mapVisible && <Button variant="secondary" aria-pressed={selected === store.id} onClick={() => setSelected(store.id)}>Show on map</Button>}
                <Directions store={store} />
              </div>
            </li>)}
          </ul>
        </>}
        {current?.rows && (page > 0 || current.rows.length > PAGE_SIZE) && <nav className="flex gap-3" aria-label="Store pages">
          <Button variant="secondary" disabled={!page} onClick={() => { setPage(value => value - 1); setSelected(null); }}>Previous</Button>
          <span className="self-center">Page {page + 1}</span>
          <Button variant="secondary" disabled={current.rows.length <= PAGE_SIZE} onClick={() => { setPage(value => value + 1); setSelected(null); }}>Next</Button>
        </nav>}
      </>}
      {token && <BranchProposal token={token} retailers={retailers} />}
    </div>
  );
}
