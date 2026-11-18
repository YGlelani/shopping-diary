import { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { Wallet, TrendingDown, ListChecks, Bell, ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { apiRequest } from '../api/client';

export default function Dashboard({ token }) {
  const [lists, setLists] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function fetchData() {
      try {
        const [listsData, alertsData] = await Promise.all([
          apiRequest('/lists', {}, token),
          apiRequest('/alerts', {}, token)
        ]);
        setLists(listsData);
        setAlerts(alertsData);
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
        <p className="text-gray-500 mt-1">An overview of your grocery spending and active monitoring.</p>
      </div>

      {/* Stat Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <Card>
          <CardContent className="flex items-center gap-4">
            <div className="p-3 bg-emerald-50 text-emerald-700 rounded-lg"><Wallet size={24} /></div>
            <div>
              <p className="text-sm font-medium text-gray-500">Monthly Budget</p>
              <p className="text-2xl font-bold text-gray-900">R2,500.00</p>
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
            <div className="p-3 bg-purple-50 text-purple-700 rounded-lg"><TrendingDown size={24} /></div>
            <div>
              <p className="text-sm font-medium text-gray-500">Potential Savings</p>
              <p className="text-2xl font-bold text-gray-900">R142.50</p>
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

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
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
                <Button onClick={() => window.location.href='/discover'}>Start a List</Button>
              </div>
            ) : (
              <ul className="divide-y divide-gray-100">
                {lists.slice(0, 3).map(list => (
                  <li key={list.id} className="py-3 flex justify-between items-center">
                    <div>
                      <p className="font-medium text-gray-900">{list.name}</p>
                      <p className="text-xs text-gray-500">{list.items?.length || 0} items</p>
                    </div>
                    <Link to="/optimize" className="text-sm font-medium text-gray-600 hover:text-gray-900">
                      Optimize
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>

        {/* Market Watch (Mocked) */}
        <Card>
          <CardHeader>
            <h2 className="text-lg font-semibold text-gray-900">Market Watch</h2>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex justify-between items-center p-3 bg-gray-50 rounded-lg border border-gray-100">
              <div>
                <p className="font-medium text-gray-900">White Bread 700g</p>
                <p className="text-xs text-gray-500">Lowest at Checkers</p>
              </div>
              <div className="text-right">
                <p className="font-bold text-emerald-600">R17.49</p>
                <p className="text-xs text-gray-400">↓ R1.50 this week</p>
              </div>
            </div>
            <div className="flex justify-between items-center p-3 bg-gray-50 rounded-lg border border-gray-100">
              <div>
                <p className="font-medium text-gray-900">Full Cream Milk 2L</p>
                <p className="text-xs text-gray-500">Lowest at Pick n Pay</p>
              </div>
              <div className="text-right">
                <p className="font-bold text-gray-900">R31.99</p>
                <p className="text-xs text-gray-400">Stable</p>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}