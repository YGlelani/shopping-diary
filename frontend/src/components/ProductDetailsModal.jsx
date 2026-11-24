import { useEffect, useRef, useState } from 'react';
import { X, TrendingDown, Store, MapPin, Bell, Plus } from 'lucide-react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { apiRequest } from '../api/client';
import { Button } from './ui/Button';
import { Input } from './ui/Input';
import { ProductCredits, ProductImage } from './ProductPresentation';
import { formatObservationDate, formatProductPrice } from '../utils/productFormatting';

export default function ProductDetailsModal({ product, onClose, lat, lon, radiusKm = 15, token, adding, onAddItem }) {
  const dialog = useRef(null);
  const [history, setHistory] = useState(null);
  const [currentPrices, setCurrentPrices] = useState(null);
  const [historyError, setHistoryError] = useState(false);
  const [pricesError, setPricesError] = useState(false);
  const [targetPrice, setTargetPrice] = useState('');
  const [alertStatus, setAlertStatus] = useState('idle');
  const hasLocation = Number.isFinite(lat) && Number.isFinite(lon);

  useEffect(() => {
    const element = dialog.current;
    element.showModal();
    return () => element.close();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const options = { signal: controller.signal };

    apiRequest('/products/' + product.productId + '/history', options)
      .then(rows => {
        const byDate = new Map();
        for (const { observedAt, storeName, price } of rows) {
          if (Number(price) <= 0 || price == null) continue;
          const date = formatObservationDate(observedAt);
          if (!byDate.has(date)) byDate.set(date, { date, prices: new Map() });
          byDate.get(date).prices.set(storeName, Number(price));
        }
        if (!controller.signal.aborted) setHistory([...byDate.values()]);
      })
      .catch(() => { if (!controller.signal.aborted) setHistoryError(true); });

    if (hasLocation) {
      const params = new URLSearchParams({ lat, lon, r: radiusKm });
      apiRequest('/products/' + product.productId + '/prices?' + params, options)
        .then(rows => { if (!controller.signal.aborted) setCurrentPrices(rows); })
        .catch(() => { if (!controller.signal.aborted) setPricesError(true); });
    }
    return () => controller.abort();
  }, [product.productId, lat, lon, radiusKm, hasLocation]);

  const handleSetAlert = async () => {
    if (!Number.isFinite(Number(targetPrice)) || Number(targetPrice) <= 0) return;
    setAlertStatus('loading');
    try {
      await apiRequest('/alerts', {
        method: 'POST',
        body: JSON.stringify({
          productId: product.productId,
          targetPrice: parseFloat(targetPrice)
        })
      }, token);
      setAlertStatus('success');
      setTargetPrice('');
    } catch (err) {
      setAlertStatus('error');
      alert("Error setting alert: " + err.message);
    }
  };

  const stores = [...new Set(history?.flatMap(row => [...row.prices.keys()]))];
  return (
    <dialog ref={dialog} onCancel={onClose} aria-labelledby="product-detail-title"
      className="m-auto h-full max-h-full w-full max-w-3xl bg-transparent backdrop:bg-gray-900/50 sm:h-auto sm:max-h-[90vh]">
      <div className="bg-white sm:rounded-xl shadow-xl w-full max-w-3xl overflow-hidden flex flex-col h-full sm:h-auto sm:max-h-[90vh]">

        {/* Header */}
        <div className="flex justify-between items-start p-6 border-b border-gray-100">
          <div>
            <h2 id="product-detail-title" className="text-xl font-bold text-gray-900">{product.canonicalName}</h2>
            <p className="mt-1 text-sm text-gray-600">{[product.brand, product.sizeText, product.category].filter(Boolean).join(' ? ')}</p>
            {product.barcode && <p className="text-xs text-gray-600">Barcode {product.barcode}</p>}
          </div>
          <button type="button" aria-label="Close product details" onClick={onClose} className="p-2 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg transition-colors">
            <X size={20} />
          </button>
        </div>

        {/* Body */}
        <div className="p-4 sm:p-6 overflow-y-auto space-y-8 flex-1">

          <div className="space-y-2">
            <ProductImage product={product} />
            <ProductCredits product={product} />
          </div>

          {/* Price Alert Section */}
          <div className="bg-gray-50 border border-gray-200 rounded-lg p-4 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
            <div>
              <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2">
                <Bell size={16} className="text-emerald-600" /> Set Price Alert
              </h3>
              <p className="text-xs text-gray-500 mt-1">
                {alertStatus === 'success' ? "Price alert saved." : "Set a target price for this product."}
              </p>
            </div>
            <div className="flex gap-2 w-full sm:w-auto">
              <Input
                type="number"
                placeholder="e.g. 25.00"
                value={targetPrice}
                onChange={(e) => setTargetPrice(e.target.value)}
                className="w-full sm:w-28"
              />
              <Button onClick={handleSetAlert} disabled={alertStatus === 'loading' || alertStatus === 'success'}>
                {alertStatus === 'loading' ? 'Saving?' : 'Save alert'}
              </Button>
            </div>
          </div>

          {/* Current Prices Table */}
          <div>
            <h3 className="text-sm font-semibold text-gray-900 mb-3 flex items-center gap-2">
              <Store size={16} /> Current Prices
            </h3>
            {!hasLocation ? (
              <p className="text-sm text-gray-600">Select a shopping location to compare nearby prices.</p>
            ) : pricesError ? (
              <p role="alert" className="text-sm text-red-700">Nearby prices could not load.</p>
            ) : currentPrices === null ? (
              <p role="status" className="text-sm text-gray-600">Loading nearby prices?</p>
            ) : currentPrices.length === 0 ? (
              <p className="text-sm text-gray-500">No current prices found nearby.</p>
            ) : (
              <div className="border border-gray-200 rounded-lg overflow-x-auto">
                <table className="w-full text-sm text-left whitespace-nowrap">
                  <thead className="bg-gray-50 text-gray-600 border-b border-gray-200">
                    <tr>
                      <th className="px-4 py-3 font-medium">Store</th>
                      <th className="px-4 py-3 font-medium">Distance</th>
                      <th className="px-4 py-3 font-medium">Observation</th>
                      <th className="px-4 py-3 font-medium text-right">Price</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {currentPrices.map(p => (
                      <tr key={p.storeId} className="hover:bg-gray-50">
                        <td className="px-4 py-3">
                          <p className="font-medium text-gray-900">{p.storeName}</p>
                          <p className="text-xs text-gray-500">{p.retailerName}</p>
                        </td>
                        <td className="px-4 py-3 text-gray-500">
                          <span className="flex items-center gap-1">
                            <MapPin size={12} />
                            {p.storeName.includes('Online') ? 'Online' : `${(p.distanceMeters / 1000).toFixed(1)} km`}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-gray-600">
                          {formatObservationDate(p.observedAt)}
                          <span className="block text-xs">{p.verificationStatus === 'VERIFIED' ? 'Verified' : 'Unverified'}</span>
                        </td>
                        <td className="px-4 py-3 text-right font-bold text-gray-900">
                          {formatProductPrice(p.promoPrice ?? p.shelfPrice)}
                          {p.promoPrice != null && <span className="block text-xs font-medium text-emerald-700">Promo</span>}
                          {Number(p.memberPrice) > 0 && (
                            <span className="block text-xs font-normal">Members {formatProductPrice(p.memberPrice)}</span>
                          )}
                          {p.promotions?.map((promotion, index) => (
                            <div key={index} className="mt-1 max-w-52 whitespace-normal text-xs font-normal text-emerald-800">
                              {promotion.type === 'MULTIBUY'
                                ? 'Buy ' + promotion.minQuantity + ' for ' + formatProductPrice(promotion.price)
                                : (promotion.type === 'MEMBER' ? 'Member offer ' : 'Offer ') + formatProductPrice(promotion.price)}
                              {promotion.description && <span className="block">{promotion.description}</span>}
                              <span className="block">Ends {formatObservationDate(promotion.endsOn)}</span>
                            </div>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* Price History Chart */}
          <div>
            <h3 className="text-sm font-semibold text-gray-900 mb-3 flex items-center gap-2">
              <TrendingDown size={16} /> Price History
            </h3>
            {historyError ? (
              <p role="alert" className="text-sm text-red-700">Price history could not load.</p>
            ) : history === null ? (
              <div role="status" className="h-48 flex items-center justify-center text-gray-400">Loading chart...</div>
            ) : history.length === 0 ? (
              <div className="h-48 flex items-center justify-center text-gray-400">No historical data available yet.</div>
            ) : (
              <div className="h-56 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={history} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#E5E7EB" />
                    <XAxis dataKey="date" tick={{ fontSize: 12, fill: '#6B7280' }} axisLine={false} tickLine={false} />
                    <YAxis tick={{ fontSize: 12, fill: '#6B7280' }} domain={['auto', 'auto']} tickFormatter={(val) => `R${val}`} axisLine={false} tickLine={false} />
                    <Tooltip formatter={(value) => `R${value}`} />
                    <Legend wrapperStyle={{ fontSize: '12px' }} />
                    {stores.map((store, i) => (
                      <Line
                        key={store}
                        type="monotone"
                        dataKey={row => row.prices.get(store)}
                        name={store}
                        stroke={['#047857', '#2563EB', '#DC2626', '#D97706'][i % 4]}
                        strokeWidth={2}
                        dot={{ r: 3 }}
                        activeDot={{ r: 5 }}
                      />
                    ))}
                  </LineChart>
                </ResponsiveContainer>
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-100 bg-gray-50 flex flex-col sm:flex-row justify-between items-center gap-4">
          <p className="text-sm text-gray-500 w-full text-center sm:text-left">
            <span className="font-bold text-gray-900">
              {formatProductPrice(currentPrices === null && !pricesError
                ? product.minEffectivePrice
                : currentPrices?.[0]?.promoPrice ?? currentPrices?.[0]?.shelfPrice)}
            </span>
          </p>
          <div className="flex gap-3 w-full sm:w-auto">
            <Button variant="secondary" onClick={onClose} className="flex-1 sm:flex-none">Close</Button>
            <Button disabled={adding} onClick={async () => { if (await onAddItem(product.productId)) onClose(); }} className="flex-1 sm:flex-none">
              <Plus size={16} className="mr-2" /> Add to List
            </Button>
          </div>
        </div>
      </div>
    </dialog>
  );
}