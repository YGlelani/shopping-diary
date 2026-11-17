import { useEffect, useState } from 'react';
import { X, TrendingDown, Store, MapPin, Bell } from 'lucide-react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { apiRequest } from '../api/client';

export default function ProductDetailsModal({ product, onClose, lat, lon, token }) {
  const [history, setHistory] = useState([]);
  const [currentPrices, setCurrentPrices] = useState([]);
  const [loading, setLoading] = useState(true);
  
  // Alert state
  const [targetPrice, setTargetPrice] = useState('');
  const [alertStatus, setAlertStatus] = useState(''); // 'idle', 'success', 'error'

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
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center p-4 z-50">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-3xl overflow-hidden max-h-[90vh] flex flex-col">
        
        {/* Header */}
        <div className="flex justify-between items-center p-4 border-b">
          <div>
            <h2 className="text-xl font-bold text-gray-800">{product.canonicalName}</h2>
            <p className="text-sm text-gray-500">{product.brand} • {product.sizeText}</p>
          </div>
          <button onClick={onClose} className="p-2 hover:bg-gray-100 rounded-full transition">
            <X size={20} className="text-gray-500" />
          </button>
        </div>

        {/* Body */}
        <div className="p-6 overflow-y-auto space-y-8">
          
          {/* Price Alert Section */}
          <div className="bg-blue-50 border border-blue-100 rounded-lg p-4 flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold text-blue-800 flex items-center gap-2">
                <Bell size={16} /> Set Price Alert
              </h3>
              <p className="text-xs text-blue-600 mt-1">
                {alertStatus === 'success' 
                  ? "✅ Alert set! We'll notify you when it drops." 
                  : "We'll notify you when the price drops below:"}
              </p>
            </div>
            <div className="flex gap-2">
              <input 
                type="number" 
                placeholder="e.g. 25.00"
                value={targetPrice}
                onChange={(e) => setTargetPrice(e.target.value)}
                className="w-24 px-3 py-2 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              <button 
                onClick={handleSetAlert}
                disabled={alertStatus === 'loading'}
                className="bg-blue-600 text-white px-4 py-2 rounded text-sm font-medium hover:bg-blue-700 transition disabled:opacity-50"
              >
                {alertStatus === 'loading' ? 'Saving...' : 'Notify Me'}
              </button>
            </div>
          </div>

          {/* Current Prices Table */}
          <div>
            <h3 className="text-sm font-semibold text-gray-600 mb-4 flex items-center gap-2">
              <Store size={16} /> Current Prices Nearby
            </h3>
            {loading ? (
              <p className="text-sm text-gray-400">Loading prices...</p>
            ) : currentPrices.length === 0 ? (
              <p className="text-sm text-gray-400">No nearby prices found.</p>
            ) : (
              <div className="border rounded-lg overflow-hidden">
                <table className="w-full text-sm text-left">
                  <thead className="bg-gray-50 text-gray-600">
                    <tr>
                      <th className="px-4 py-2 font-medium">Store</th>
                      <th className="px-4 py-2 font-medium">Distance</th>
                      <th className="px-4 py-2 font-medium text-right">Price</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {currentPrices.map((p, i) => (
                      <tr key={i} className="hover:bg-gray-50">
                        <td className="px-4 py-3">
                          <p className="font-medium text-gray-800">{p.storeName}</p>
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
                          {p.promoPrice && <span className="block text-xs text-green-600 font-normal">Promo</span>}
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
            <h3 className="text-sm font-semibold text-gray-600 mb-4 flex items-center gap-2">
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
                    <CartesianGrid strokeDasharray="3 3" vertical={false} />
                    <XAxis dataKey="date" tick={{fontSize: 12}} />
                    <YAxis tick={{fontSize: 12}} domain={['auto', 'auto']} tickFormatter={(val) => `R${val}`} />
                    <Tooltip formatter={(value) => `R${value}`} />
                    <Legend wrapperStyle={{fontSize: '12px'}} />
                    {Object.keys(history[0] || {}).filter(k => k !== 'date').map((store, i) => (
                      <Line 
                        key={store} 
                        type="monotone" 
                        dataKey={store} 
                        stroke={['#2563eb', '#16a34a', '#dc2626', '#d97706'][i % 4]} 
                        strokeWidth={2}
                        dot={{ r: 4 }}
                      />
                    ))}
                  </LineChart>
                </ResponsiveContainer>
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <div className="bg-gray-50 p-4 border-t flex justify-end">
          <button onClick={onClose} className="px-4 py-2 bg-gray-200 text-gray-800 rounded hover:bg-gray-300 transition text-sm font-medium">
            Close
          </button>
        </div>
      </div>
    </div>
  );
}