import { lazy, Suspense, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiRequest } from '../api/client';
import { BranchIdentity, Coverage, Directions } from '../components/BranchPresentation';
import PriceContribution from '../components/PriceContribution';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { formatObservationDate, formatProductPrice } from '../utils/productFormatting';

const StoreMap = lazy(() => import('../components/LocationPicker'));
const PAGE_SIZE = 20;

export default function StoreDetails(props) {
  const { id } = useParams();
  return <Details key={id} {...props} id={id} />;
}

function Details({ id, location, token, onAddItem }) {
  const [branchResult, setBranchResult] = useState(null);
  const [offers, setOffers] = useState(null);
  const [page, setPage] = useState(0);
  const [mapVisible, setMapVisible] = useState(false);
  const [notice, setNotice] = useState('');
  const [history, setHistory] = useState(null);
  const [reportQuery, setReportQuery] = useState('');
  const [reportResults, setReportResults] = useState(null);
  const [selectedProduct, setSelectedProduct] = useState('');
  const params = location ? '?' + new URLSearchParams({ lat: location.lat, lon: location.lon }) : '';
  const current = branchResult?.params === params ? branchResult : null;
  const store = current?.store;
  const currentOffers = offers?.page === page ? offers : null;

  useEffect(() => {
    const controller = new AbortController();
    apiRequest('/stores/' + id + params, { signal: controller.signal }, token)
      .then(store => { if (!controller.signal.aborted) setBranchResult({ params, store }); })
      .catch(error => { if (!controller.signal.aborted) setBranchResult({ params, error: error.message }); });
    return () => controller.abort();
  }, [id, params, token]);

  useEffect(() => {
    const controller = new AbortController();
    const query = new URLSearchParams({ offset: page * PAGE_SIZE, limit: PAGE_SIZE + 1 });
    apiRequest('/stores/' + id + '/offers?' + query, { signal: controller.signal }, token)
      .then(rows => { if (!controller.signal.aborted) setOffers({ page, rows }); })
      .catch(error => { if (!controller.signal.aborted) setOffers({ page, error: error.message }); });
    return () => controller.abort();
  }, [id, page, token]);

  async function findProduct(event) {
    event.preventDefault();
    setSelectedProduct('');
    setReportResults({ loading: true });
    try {
      const query = new URLSearchParams({ q: reportQuery.trim(), limit: '10' });
      setReportResults({ rows: await apiRequest('/products/search?' + query, {}, token) });
    } catch (error) {
      setReportResults({ error: error.message });
    }
  }

  async function loadHistory(page) {
    setHistory({ loading: true });
    try {
      const query = new URLSearchParams({ offset: page * PAGE_SIZE, limit: PAGE_SIZE + 1 });
      const rows = await apiRequest('/stores/' + id + '/observations?' + query, {}, token);
      setHistory({ page, rows });
    } catch (error) {
      setHistory({ error: error.message });
    }
  }

  return <div className="mx-auto max-w-4xl space-y-5">
    <Link to="/stores" className="text-sm text-emerald-800 underline">Back to nearby stores</Link>
    {!current && <p role="status">Loading branch…</p>}
    {current?.error && <p role="alert">{current.error}</p>}
    {store && <>
      <section className="space-y-3 rounded-xl border bg-white p-4">
        <h1 className="text-xl font-bold">Branch details</h1>
        <BranchIdentity store={store} />
        <p className="text-sm">{store.distanceKm != null && <>{store.distanceKm.toFixed(1)} km straight line · </>}
          {store.lat.toFixed(5)}, {store.lon.toFixed(5)}</p>
        <p className="text-sm whitespace-pre-line">{store.hoursText || 'Opening hours unavailable'}</p>
        {store.phone && <a className="block text-sm underline" href={'tel:' + store.phone}>{store.phone}</a>}
        <div className="flex flex-wrap items-center gap-3">
          <Directions store={store} />
          {store.sourceUrl && <a className="text-sm underline" href={store.sourceUrl} target="_blank" rel="noreferrer">Branch information source</a>}
          <Button variant="secondary" aria-expanded={mapVisible} onClick={() => setMapVisible(value => !value)}>
            {mapVisible ? 'Hide branch map' : 'Show branch map'}
          </Button>
        </div>
        {mapVisible && <Suspense fallback={<p>Loading map…</p>}>
          <StoreMap location={location} stores={[store]} selected={store.id} onSelect={() => {}} />
        </Suspense>}
        <Coverage store={store} />
      </section>
      <section className="space-y-3">
        <h2 className="font-semibold">Current branch prices</h2>
        {!currentOffers && <p role="status">Loading branch prices…</p>}
        {currentOffers?.error && <p role="alert">{currentOffers.error}</p>}
        {currentOffers?.rows?.length === 0 && <p>No eligible product prices are recorded for this branch. You can contribute an observation below.</p>}
        {notice && <p role="status">{notice}</p>}
        <ul className="grid gap-3 sm:grid-cols-2">
          {currentOffers?.rows?.slice(0, PAGE_SIZE).map(row => <li key={row.productId} className="space-y-2 rounded-xl border bg-white p-4">
            <div className="flex gap-3">
              {row.imageUrl && <img src={row.imageUrl} alt="" loading="lazy" className="h-16 w-16 shrink-0 object-contain" />}
              <div><h3 className="font-medium">{row.canonicalName}</h3>
                <p className="text-sm">{[row.brand, row.sizeText, row.barcode].filter(Boolean).join(' · ')}</p></div>
            </div>
            <p className="font-semibold">{formatProductPrice(row.effectivePrice)} per package</p>
            {row.memberPrice && <p className="text-sm">Members only {formatProductPrice(row.memberPrice)}</p>}
            {row.promotionType && <p className="text-sm text-emerald-800">
              {row.promotionType === 'MULTIBUY' ? 'Buy ' + row.minQuantity + ' for ' + formatProductPrice(row.promotionPrice)
                : row.promotionType === 'PERCENT' ? 'Percentage discount'
                : row.promotionType === 'BOGOF' ? 'Buy and get free offer'
                  : (row.promotionType === 'MEMBER' ? 'Members only offer ' : 'Branch promotion ') + formatProductPrice(row.promotionPrice)}
              {' · Until ' + formatObservationDate(row.promotionEnds)}
            </p>}
            <p className="text-sm text-gray-600">Current · {row.verificationStatus === 'VERIFIED' ? 'Verified' : 'Unverified'} · {row.sourceType}</p>
            <p className="text-xs text-gray-600">Observed {formatObservationDate(row.observedAt)} · Reported in stock</p>
            <div className="flex flex-wrap items-center gap-3">
              <Link className="text-sm text-emerald-800 underline" to={'/discover?' + new URLSearchParams({ q: row.barcode || row.canonicalName })}>Find product in Discover</Link>
              {token && <Button variant="secondary" onClick={async () => {
                if (await onAddItem(row.productId)) setNotice(row.canonicalName + ' added to your shopping list.');
              }}>Add to list</Button>}
            </div>
          </li>)}
        </ul>
        {currentOffers?.rows && (page > 0 || currentOffers.rows.length > PAGE_SIZE) && <nav className="flex gap-3" aria-label="Branch price pages">
          <Button variant="secondary" disabled={!page} onClick={() => setPage(value => value - 1)}>Previous</Button>
          <span className="self-center">Page {page + 1}</span>
          <Button variant="secondary" disabled={currentOffers.rows.length <= PAGE_SIZE} onClick={() => setPage(value => value + 1)}>Next</Button>
        </nav>}
      </section>
      <section className="space-y-2">
        <Button variant="secondary" aria-expanded={Boolean(history)} onClick={() => history ? setHistory(null) : loadHistory(0)}>
          {history ? 'Hide observation history' : 'Show observation history'}
        </Button>
        {history && <>
          <h2 className="font-semibold">Recorded observations</h2>
          <p className="text-sm text-gray-600">These records show what was reported at the time of observation.</p>
          {history.loading && <p role="status">Loading observations?</p>}
          {history.error && <p role="alert">{history.error}</p>}
          {history.rows?.length === 0 && <p>No observations are recorded for this branch.</p>}
          <ul className="space-y-2">
            {history.rows?.slice(0, PAGE_SIZE).map((row, index) => <li className="rounded border bg-white p-3 text-sm" key={row.productId + ':' + index}>
              <p className="font-medium">{[row.canonicalName, row.brand, row.sizeText].filter(Boolean).join(' ? ')}</p>
              <p>{formatObservationDate(row.observedAt)} ? {row.sourceType} ? {row.verificationStatus.toLowerCase()}
                {' ? ' + row.stockStatus.toLowerCase().replaceAll('_', ' ')}</p>
              {row.shelfPrice != null && <p>Recorded shelf price {formatProductPrice(row.shelfPrice)}</p>}
            </li>)}
          </ul>
          {history.rows && (history.page > 0 || history.rows.length > PAGE_SIZE) && <nav className="flex gap-2" aria-label="Observation pages">
            <Button variant="secondary" disabled={!history.page} onClick={() => loadHistory(history.page - 1)}>Previous</Button>
            <Button variant="secondary" disabled={history.rows.length <= PAGE_SIZE} onClick={() => loadHistory(history.page + 1)}>Next</Button>
          </nav>}
        </>}
      </section>
      {token && <section className="space-y-3 rounded-xl border bg-white p-4">
        <h2 className="font-semibold">Report a price at this branch</h2>
        <form onSubmit={findProduct} className="flex flex-wrap items-end gap-2">
          <label className="flex-1 text-sm">Find the exact product<Input value={reportQuery} onChange={event => setReportQuery(event.target.value)}
            minLength={2} maxLength={120} required /></label>
          <Button variant="secondary" type="submit" disabled={reportResults?.loading}>Find products</Button>
        </form>
        {reportResults?.error && <p role="alert">{reportResults.error}</p>}
        {reportResults?.rows?.length === 0 && <p>No matching catalogue products.</p>}
        {reportResults?.rows?.length > 0 && <label className="block text-sm">Select a catalogue product
          <select className="block w-full rounded border p-2" value={selectedProduct} onChange={event => setSelectedProduct(event.target.value)}>
            <option value="">Choose a product</option>
            {reportResults.rows.map(row => <option key={row.productId} value={row.productId}>
              {[row.canonicalName, row.brand, row.sizeText, row.barcode].filter(Boolean).join(' · ')}
            </option>)}
          </select>
        </label>}
        {selectedProduct && <PriceContribution key={selectedProduct} productId={Number(selectedProduct)} branch={store} token={token} />}
      </section>}
    </>}
  </div>;
}
