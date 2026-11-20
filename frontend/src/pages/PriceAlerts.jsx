import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, Trash2 } from 'lucide-react';
import { Card, CardContent, CardHeader } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { apiRequest } from '../api/client';

export default function PriceAlerts({ token }) {
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function fetchAlerts() {
      try {
        const res = await apiRequest('/alerts', {}, token);
        setAlerts(res);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    }
    fetchAlerts();
  }, [token]);

  async function deleteAlert(id) {
    setError('');
    try {
      await apiRequest(`/alerts/${id}`, { method: 'DELETE' }, token);
      setAlerts(current => current.filter(alert => alert.id !== id));
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Price Alerts</h1>
        <p className="text-gray-500 mt-1">Manage the products you are monitoring for price drops.</p>
      </div>

      {error && <p role="alert" className="text-red-700">{error}</p>}
      <Card>
        <CardHeader className="bg-gray-50/50">
          <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
            <Bell size={18} /> Alert Status
          </h2>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <div className="p-6 text-center text-gray-500">Loading alerts...</div>
          ) : alerts.length === 0 ? (
            <div className="text-center py-12 px-4">
              <p className="text-gray-500 mb-4">You don't have any price alerts.</p>
              <Button onClick={() => navigate('/discover')}>Find Products to Track</Button>
            </div>
          ) : (
            <ul className="divide-y divide-gray-100">
              {alerts.map((alert) => (
                <li key={alert.id} className="p-4 sm:px-6 flex justify-between items-center hover:bg-gray-50">
                  <div>
                    <p className="font-medium text-gray-900">Product ID: {alert.productId}</p>
                    <p className="text-sm text-gray-500">
                      Target price <span className="font-bold text-emerald-600">R{alert.targetPrice}</span>
                    </p>
                  </div>
                  <div className="flex items-center gap-4">
                    <span className={`px-2.5 py-1 rounded-full text-xs font-medium ${alert.active ? 'bg-emerald-100 text-emerald-800' : 'bg-gray-100 text-gray-600'}`}>
                      {alert.active ? 'Watching' : alert.lastNotifiedAt ? 'Target reached' : 'Inactive'}
                    </span>
                    <button className="p-2 text-gray-400 hover:text-red-600 transition-colors" title="Delete alert" onClick={() => deleteAlert(alert.id)}>
                      <Trash2 size={18} />
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}