import { useEffect, useState } from 'react';
import { X, TrendingDown, Store } from 'lucide-react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { apiRequest } from '../api/client';

export default function ProductDetailsModal({ product, onClose }) {
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function fetchHistory() {
      try {
        const data = await apiRequest(`/products/${product.productId}/history`);
        // Group data for Recharts (need format: { date: '2023-10-01', Checkers: 29.99, PnP: 32.99 })
        const grouped = data.reduce((acc, row) => {
          const date = new Date(row.observedAt).toLocaleDateString();
          if (!acc[date]) acc[date] = { date };
          acc[date][row.storeName] = row.price;
          return acc;
        }, {});
        setHistory(Object.values(grouped));
      } catch (err) {
        console.error(err);
      } finally {
        setLoading(false);
      }
    }
    fetchHistory();
  }, [product.productId]);

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center p-4 z-50">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-2xl overflow-hidden">
        <div className="flex justify-between items-center p-4 border-b">
          <div>
            <h2 className="text-xl font-bold text-gray-800">{product.canonicalName}</h2>
            <p className="text-sm text-gray-500">{product.brand} • {product.sizeText}</p>
          </div>
          <button onClick={onClose} className="p-2 hover:bg-gray-100 rounded-full transition">
            <X size={20} className="text-gray-500" />
          </button>
        </div>

        <div className="p-6">
          <h3 className="text-sm font-semibold text-gray-600 mb-4 flex items-center gap-2">
            <TrendingDown size={16} /> 30-Day Price History
          </h3>
          
          {loading ? (
            <div className="h-64 flex items-center justify-center text-gray-400">Loading chart...</div>
          ) : history.length === 0 ? (
            <div className="h-64 flex items-center justify-center text-gray-400">No historical data available yet.</div>
          ) : (
            <div className="h-64 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={history}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="date" tick={{fontSize: 12}} />
                  <YAxis tick={{fontSize: 12}} domain={['auto', 'auto']} tickFormatter={(val) => `R${val}`} />
                  <Tooltip formatter={(value) => `R${value}`} />
                  <Legend wrapperStyle={{fontSize: '12px'}} />
                  {/* Dynamically render a line for each store */}
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

        <div className="bg-gray-50 p-4 border-t flex justify-end">
          <button onClick={onClose} className="px-4 py-2 bg-gray-200 text-gray-800 rounded hover:bg-gray-300 transition text-sm font-medium">
            Close
          </button>
        </div>
      </div>
    </div>
  );
}