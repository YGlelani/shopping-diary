import { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader } from '../components/ui/Card';
import { Wallet, ListChecks, Bell, ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { apiRequest } from '../api/client';

export default function Dashboard({ token, budgetAmount, lists, onSelectList }) {
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function fetchData() {
      try {
        const alertsData = await apiRequest('/alerts', {}, token);
        setAlerts(alertsData.filter(alert => alert.active));
      } catch (err) {
        console.error(err);
      } finally {
        setLoading(false);
      }
    }
    fetchData();
  }, [token]);

  if (loading) return <div className="text-gray-500">Loading dashboard...</div>;

  return (
    <div className="space-y-8">
      {/* Page Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p className="text-gray-500 mt-1">Your shopping lists and price alerts.</p>
      </div>

      {/* Stat Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <Card>
          <CardContent className="flex items-center gap-4">
            <div className="p-3 bg-emerald-50 text-emerald-700 rounded-lg"><Wallet size={24} /></div>
            <div>
              <p className="text-sm font-medium text-gray-500">Selected List Budget</p>
              <p className="text-2xl font-bold text-gray-900">{budgetAmount == null ? 'No budget set' : `R${budgetAmount.toFixed(2)}`}</p>
            </div>
          </CardContent>
        </Card>
        
        <Card>
          <CardContent className="flex items-center gap-4">
            <div className="p-3 bg-blue-50 text-blue-700 rounded-lg"><ListChecks size={24} /></div>
            <div>
              <p className="text-sm font-medium text-gray-500">Active Lists</p>
              <p className="text-2xl font-bold text-gray-900">{lists.length}</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="flex items-center gap-4">
            <div className="p-3 bg-amber-50 text-amber-700 rounded-lg"><Bell size={24} /></div>
            <div>
              <p className="text-sm font-medium text-gray-500">Active Alerts</p>
              <p className="text-2xl font-bold text-gray-900">{alerts.length}</p>
            </div>
          </CardContent>
        </Card>
      </div>

      <div className="space-y-6">
        {/* Recent Lists */}
        <Card>
          <CardHeader className="flex justify-between items-center">
            <h2 className="text-lg font-semibold text-gray-900">Recent Shopping Lists</h2>
            <Link to="/lists" className="text-sm font-medium text-emerald-600 hover:text-emerald-700 flex items-center gap-1">
              View all <ArrowRight size={16} />
            </Link>
          </CardHeader>
          <CardContent>
            {lists.length === 0 ? (
              <div className="text-center py-6">
                <p className="text-gray-500 text-sm mb-4">You haven't created any shopping lists yet.</p>
                <Link className="text-emerald-700 underline" to="/lists">Start a list</Link>
              </div>
            ) : (
              <ul className="divide-y divide-gray-100">
                {lists.slice(0, 3).map(list => (
                  <li key={list.id} className="py-3 flex justify-between items-center">
                    <div>
                      <p className="font-medium text-gray-900">{list.name}</p>
                      <p className="text-xs text-gray-500">{list.items?.length || 0} items</p>
                    </div>
                    <Link to="/optimize" onClick={() => onSelectList(list.id)} className="text-sm font-medium text-gray-600 hover:text-gray-900">
                      Optimize
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>

      </div>
    </div>
  );
}