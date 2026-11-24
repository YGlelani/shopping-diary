import { lazy, Suspense, useEffect, useState } from 'react';
import { MapPin, Plus, Search } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';
import { Card, CardContent } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { ProductCredits, ProductImage } from '../components/ProductPresentation';
import { formatObservationDate, formatProductPrice } from '../utils/productFormatting';
import { apiRequest } from '../api/client';

const ProductDetailsModal = lazy(() => import('../components/ProductDetailsModal'));
const PAGE_SIZE = 24;
const selectClass = 'w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm';
const initialFilters = { q: '', category: '', brand: '', size: '', priced: '' };

export default function Discover({ token, onAddItem, location, radius, onRadiusChange }) {
  const [params] = useSearchParams();
  const q = params.get('q')?.slice(0, 120) ?? '';
  return (
    <DiscoverContent initialQuery={q}
      key={[location?.lat, location?.lon, q].join(':')}
      token={token}
      onAddItem={onAddItem}
      location={location}
      radius={radius}
      onRadiusChange={onRadiusChange}
    />
  );
}

function DiscoverContent({ token, onAddItem, location, radius, onRadiusChange, initialQuery }) {
  const [filters, setFilters] = useState({ ...initialFilters, q: initialQuery });
  const [request, setRequest] = useState({ filters, offset: 0 });
  const [departments, setDepartments] = useState([]);
  const [categoryError, setCategoryError] = useState(false);
  const [response, setResponse] = useState(null);
  const [selectedProduct, setSelectedProduct] = useState(null);
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState('');
  const { lat, lon } = location ?? {};
  const hasLocation = Number.isFinite(lat) && Number.isFinite(lon);

  useEffect(() => {
    const controller = new AbortController();
    apiRequest('/products/categories', { signal: controller.signal }, token)
      .then(setDepartments)
      .catch(() => { if (!controller.signal.aborted) setCategoryError(true); });
    return () => controller.abort();
  }, [token]);

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({
      r: radius, limit: PAGE_SIZE + 1, offset: request.offset,
    });
    for (const [key, value] of Object.entries(request.filters)) {
      if (value.trim() && (key !== 'priced' || hasLocation)) params.set(key, value.trim());
    }
    if (hasLocation) {
      params.set('lat', lat);
      params.set('lon', lon);
    }

    apiRequest('/products/search?' + params, { signal: controller.signal }, token)
      .then(products => {
        if (!Array.isArray(products)) throw new Error('The catalogue returned an unexpected response.');
        if (!controller.signal.aborted) setResponse({ request, products });
      })
      .catch(error => {
        if (!controller.signal.aborted) setResponse({ request, error: error.message || 'Search failed. Try again.' });
      });
    return () => controller.abort();
  }, [request, token, lat, lon, hasLocation, radius]);

  function updateFilter(event) {
    const { name, value } = event.target;
    setFilters(current => ({ ...current, [name]: value }));
  }

  function submitSearch(event) {
    event.preventDefault();
    setRequest({ filters, offset: 0 });
  }

  async function addProduct(product) {
    if (adding) return false;
    setAdding(true);
    setNotice('');
    try {
      const added = await onAddItem(product.productId);
      if (added) setNotice(product.canonicalName + ' added to your shopping list.');
      return added;
    } finally {
      setAdding(false);
    }
  }

  const loading = response?.request !== request;
  const error = loading ? null : response?.error;
  const products = loading ? [] : response?.products ?? [];

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-gray-900">Discover products</h1>

      <Card>
        <CardContent className="p-4">
          <form onSubmit={submitSearch} className="space-y-3" aria-label="Catalogue search">
            <div className="flex flex-col gap-2 sm:flex-row">
              <div className="relative flex-1">
                <Search aria-hidden="true" className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" size={18} />
                <Input name="q" type="search" aria-label="Search groceries" placeholder="Try Sasko bread, rice 2 kg or a barcode"
                  className="pl-10" value={filters.q} onChange={updateFilter} minLength={2} maxLength={120} />
              </div>
              <Button type="submit">Search</Button>
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <label className="space-y-1 text-sm text-gray-700">
                <span>Category or department</span>
                <select name="category" className={selectClass} value={filters.category} onChange={updateFilter}>
                  <option value="">All categories</option>
                  {departments.map(name => <option key={name}>{name}</option>)}
                </select>
              </label>
              <label className="space-y-1 text-sm text-gray-700">
                <span>Brand</span>
                <Input name="brand" value={filters.brand} onChange={updateFilter} maxLength={100} placeholder="Any brand" />
              </label>
              <label className="space-y-1 text-sm text-gray-700">
                <span>Package size</span>
                <Input name="size" value={filters.size} onChange={updateFilter} maxLength={50} placeholder="e.g. 500 g or 2 L" />
              </label>
              <label className="space-y-1 text-sm text-gray-700">
                <span>Nearby price</span>
                <select name="priced" className={selectClass} value={filters.priced} onChange={updateFilter} disabled={!hasLocation}>
                  <option value="">Any price status</option>
                  <option value="true">With prices</option>
                  <option value="false">Without prices</option>
                </select>
              </label>
            </div>
          </form>
          {hasLocation && <label className="mt-3 block text-sm">Search radius
            <select value={radius} onChange={event => {
              onRadiusChange(Number(event.target.value));
              setRequest(current => ({ ...current, offset: 0 }));
            }} className="ml-2 rounded border p-1">
              {[2, 5, 10, 15, 25, 50, 100].map(km => <option key={km} value={km}>{km} km</option>)}
            </select>
          </label>}
          {categoryError && <p role="status" className="mt-2 text-sm text-gray-600">Department filters could not load.</p>}
          <p className="mt-3 flex items-center gap-2 text-xs text-gray-600">
            <MapPin size={14} aria-hidden="true" />
            {hasLocation ? 'Prices shown within ' + radius + ' km of your shopping location.' : (
              <Link to="/stores" state={{ returnTo: '/discover' }} className="underline">Select a shopping location to see nearby prices</Link>
            )}
          </p>
        </CardContent>
      </Card>

      <p role="status" className={notice ? "text-sm text-emerald-800" : "sr-only"}>{notice}</p>
      {loading && <p role="status" className="py-8 text-center text-gray-600">Searching the catalogue…</p>}
      {error && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-red-800">
          <p>{error}</p>
          <Button variant="secondary" className="mt-2" onClick={() => setRequest({ ...request })}>Try again</Button>
        </div>
      )}
      {!loading && !error && products.length === 0 && (
        <div role="status" className="py-8 text-center text-gray-600">
          <p className="font-semibold text-gray-900">No matching products</p>
          <p>Try a different search or clear a filter.</p>
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {products.slice(0, PAGE_SIZE).map(product => (
          <Card key={product.productId} className="flex flex-col overflow-hidden">
            <ProductImage product={product} />
            <CardContent className="flex flex-1 flex-col gap-3 p-4">
              <div>
                {product.category && <p className="text-xs text-gray-600">{product.category}</p>}
                <h2 className="font-semibold text-gray-900">{product.canonicalName}</h2>
                <p className="text-sm text-gray-600">{[product.brand, product.sizeText].filter(Boolean).join(' · ')}</p>
                {product.barcode && <p className="text-xs text-gray-600">Barcode {product.barcode}</p>}
              </div>
              {product.storeCount > 0 ? (
                <div>
                  <p className="text-lg font-bold text-emerald-700">From {formatProductPrice(product.minEffectivePrice)}</p>
                  <p className="text-xs text-gray-600">
                    {product.storeCount} nearby {product.storeCount === 1 ? 'store' : 'stores'}
                    {product.latestObservedAt && ' · Latest ' + formatObservationDate(product.latestObservedAt)}
                  </p>
                </div>
              ) : (
                <p className="text-sm font-medium text-gray-600">{hasLocation ? 'No nearby prices reported' : 'Price unavailable'}</p>
              )}
              <ProductCredits product={product} />
              <div className="mt-auto flex gap-2">
                <Button variant="secondary" className="flex-1" onClick={() => setSelectedProduct(product)}
                  aria-label={'View details for ' + product.canonicalName}>{product.storeCount ? 'Compare stores' : 'Details'}</Button>
                <Button className="flex-1" disabled={adding} onClick={() => addProduct(product)}
                  aria-label={'Add ' + product.canonicalName + ' to shopping list'}>
                  <Plus size={14} aria-hidden="true" className="mr-1" /> Add
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {!loading && !error && (request.offset > 0 || products.length > PAGE_SIZE) && (
        <nav aria-label="Catalogue pages" className="flex items-center justify-center gap-3">
          <Button variant="secondary" disabled={request.offset === 0}
            onClick={() => setRequest({ ...request, offset: request.offset - PAGE_SIZE })}>Previous</Button>
          <span className="text-sm text-gray-600">Page {request.offset / PAGE_SIZE + 1}</span>
          <Button variant="secondary" disabled={products.length <= PAGE_SIZE}
            onClick={() => setRequest({ ...request, offset: request.offset + PAGE_SIZE })}>Next</Button>
        </nav>
      )}

      {selectedProduct && (
        <Suspense fallback={<p role="status">Loading product details…</p>}>
          <ProductDetailsModal key={selectedProduct.productId} product={selectedProduct}
            alternatives={request.filters.q || request.filters.category
              ? products.filter(row => row.productId !== selectedProduct.productId).slice(0, 4) : []}
            onSelectProduct={setSelectedProduct} onClose={() => setSelectedProduct(null)}
            lat={lat} lon={lon} radiusKm={radius} token={token} adding={adding} onAddItem={() => addProduct(selectedProduct)} />
        </Suspense>
      )}
    </div>
  );
}
