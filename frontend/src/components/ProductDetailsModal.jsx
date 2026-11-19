import { useEffect, useState } from 'react';
import { X, TrendingDown, Store, MapPin, Bell, Plus } from 'lucide-react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { apiRequest } from '../api/client';
import { Button } from './ui/Button';
import { Input } from './ui/Input';

export default function ProductDetailsModal({ product, onClose, lat, lon, token, onAddItem }) {
  const [history, setHistory] = useState([]);
  const [currentPrices, setCurrentPrices] = useState([]);
  const [loading, setLoading] = useState(true);

  const [targetPrice, setTargetPrice] = useState('');
  const [alertStatus, setAlertStatus] = useState('idle');

  useEffect(() => {
    async function fetchData() {
      try {
        const [historyData, pricesData] = await Promise.all([
          apiRequest(`/products/${product.productId}/history`),
          apiRequest(`/products/${product.productId}/prices?lat=${lat}&lon=${lon}&r=50`)
        ]);

        const grouped = historyData.reduce((acc, row) => {
          const date = new Date(row.observedAt).toLocaleDateString();
          if (!acc[date]) acc[date] = { date };
          acc[date][row.storeName] = row.price;
          return acc;
        }, {});
        setHistory(Object.values(grouped));
        setCurrentPrices(pricesData);
      } catch (err) {
        console.error(err);
      } finally {
        setLoading(false);
      }
    }
    fetchData();
  }, [product.productId, lat, lon]);

  const handleSetAlert = async () => {
    if (!targetPrice || isNaN(targetPrice)) return;
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

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-0 sm:p-4 bg-gray-900/50 backdrop-blur-sm">
      <div className="bg-white sm:rounded-xl shadow-xl w-full max-w-3xl overflow-hidden flex flex-col h-full sm:h-auto sm:max-h-[90vh]">

        {/* Header */}
        <div className="flex justify-between items-start p-6 border-b border-gray-100">
          <div>
            <h2 className="text-xl font-bold text-gray-900">{product.canonicalName}</h2>
            <p className="text-sm text-gray-500 mt-1">{product.brand} • {product.sizeText}</p>
          </div>
          <button onClick={onClose} className="p-2 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg transition-colors">
            <X size={20} />
          </button>
        </div>

        {/* Body */}
        <div className="p-4 sm:p-6 overflow-y-auto space-y-8 flex-1">

          {/* Price Alert Section */}
          <div className="bg-gray-50 border border-gray-200 rounded-lg p-4 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
            <div>
              <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2">
                <Bell size={16} className="text-emerald-600" /> Set Price Alert
              </h3>
              <p className="text-xs text-gray-500 mt-1">
                {alertStatus === 'success' ? "Alert set! We'll email you when it drops." : "Notify me when the price drops below:"}
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
                {alertStatus === 'loading' ? '...' : 'Notify'}
              </Button>
            </div>
          </div>

          {/* Current Prices Table */}
          <div>
            <h3 className="text-sm font-semibold text-gray-900 mb-3 flex items-center gap-2">
              <Store size={16} /> Current Prices
            </h3>
            {loading ? (
              <p className="text-sm text-gray-500">Loading...</p>
            ) : currentPrices.length === 0 ? (
              <p className="text-sm text-gray-500">No current prices found nearby.</p>
            ) : (
              <div className="border border-gray-200 rounded-lg overflow-x-auto">
                <table className="w-full text-sm text-left whitespace-nowrap">
                  <thead className="bg-gray-50 text-gray-600 border-b border-gray-200">
                    <tr>
                      <th className="px-4 py-3 font-medium">Store</th>
                      <th className="px-4 py-3 font-medium">Distance</th>
                      <th className="px-4 py-3 font-medium text-right">Price</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {currentPrices.map((p, i) => (
                      <tr key={i} className="hover:bg-gray-50">
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
                        <td className="px-4 py-3 text-right font-bold text-gray-900">
                          R{p.promoPrice || p.shelfPrice}
                          {p.promoPrice && <span className="block text-xs text-emerald-600 font-medium">Promo</span>}
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
            {loading ? (
              <div className="h-48 flex items-center justify-center text-gray-400">Loading chart...</div>
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
                    {Object.keys(history[0] || {}).filter(k => k !== 'date').map((store, i) => (
                      <Line
                        key={store}
                        type="monotone"
                        dataKey={store}
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
            Lowest Price: <span className="font-bold text-gray-900">R{product.minEffectivePrice}</span>
          </p>
          <div className="flex gap-3 w-full sm:w-auto">
            <Button variant="secondary" onClick={onClose} className="flex-1 sm:flex-none">Close</Button>
            <Button onClick={() => { onAddItem(product.productId); onClose(); }} className="flex-1 sm:flex-none">
              <Plus size={16} className="mr-2" /> Add to List
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}