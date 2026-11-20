import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Calculator, CheckCircle2, TrendingDown, MapPin, Store } from 'lucide-react';
import { Card, CardContent } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { apiRequest } from '../api/client';

export default function Optimizer({ token, listId, location, budgetAmount }) {
  const [optimization, setOptimization] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleOptimize = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await apiRequest(`/lists/${listId}/optimize`, {
        method: 'POST',
        body: JSON.stringify({ lat: location.lat, lon: location.lon, radiusKm: 10.0, costPerKm: 4.50 }),
      }, token);
      setOptimization(res);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  if (!optimization && !loading) {
    return (
      <div className="flex flex-col items-center justify-center h-[60vh] text-center max-w-md mx-auto">
        <div className="w-16 h-16 bg-emerald-100 text-emerald-700 rounded-2xl flex items-center justify-center mb-6">
          <Calculator size={32} />
        </div>
        <h1 className="text-2xl font-bold text-gray-900 mb-2">Basket Optimisation</h1>
        <p className="text-gray-500 mb-8">
          We will calculate whether buying everything at one store, or splitting your list between multiple stores, will save you the most money after transport costs.
        </p>
        {!listId && <Link to="/lists" className="mb-4 text-emerald-700 underline">Create a shopping list</Link>}
        {error && <p className="text-red-600 mb-4">{error}</p>}
        <Button onClick={handleOptimize} disabled={!listId || !location} className="w-full py-3 text-lg">Run Optimisation</Button>
      </div>
    );
  }

  if (loading) {
    return <div className="text-center py-20 text-gray-500">Analyzing prices, distances, and promotions...</div>;
  }

  const { recommendation, bestSingleStore, savingsVsBestSingle, skippedItems, costPerKm } = optimization;
  const omitted = recommendation.missingItems + skippedItems;
  const isSplit = recommendation.stores.length > 1;

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Optimisation Results</h1>
        <p className="text-gray-500 mt-1">Based on your current shopping list and location.</p>
      </div>

      {/* Recommendation Summary */}
      <Card>
      <div className="bg-emerald-50/50 p-4 sm:p-6 border-b border-emerald-100 flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
        <div>
          <p className="text-xs sm:text-sm font-semibold text-emerald-800 uppercase tracking-wider mb-1">Recommended Plan</p>
          <h2 className="text-xl sm:text-2xl font-bold text-gray-900">
            {isSplit ? 'Split across ' + recommendation.stores.length + ' stores' : 'Shop at ' + recommendation.stores[0].storeName}
          </h2>
        </div>
        <div className="text-left md:text-right">
          <p className="text-xs sm:text-sm text-gray-600 font-medium">Total Estimated Cost</p>
          <p className="text-2xl sm:text-3xl font-bold text-gray-900">R{recommendation.grandTotal.toFixed(2)}</p>
        </div>
      </div>

      <CardContent>
      <div className="p-6">
        {omitted > 0 && <p role="status" className="mb-4 text-amber-800">{omitted} items are missing from this estimate.</p>}
        {budgetAmount != null && <p className="mb-4 text-sm">Budget R{budgetAmount.toFixed(2)}: {recommendation.grandTotal > budgetAmount ? "Over budget" : omitted ? "Priced items within budget" : "Within budget"}</p>}
        {isSplit && bestSingleStore && savingsVsBestSingle > 0 ? (
          <div className="flex items-center gap-3 bg-emerald-100 text-emerald-900 p-4 rounded-lg mb-6">
            <TrendingDown size={24} className="text-emerald-700" />
            <div>
              <p className="font-bold">You save R{savingsVsBestSingle.toFixed(2)}!</p>
              <p className="text-sm text-emerald-800">By splitting your shop instead of buying everything at {bestSingleStore.stores[0].storeName}.</p>
            </div>
          </div>
        ) : (
          <div className="flex items-center gap-3 bg-gray-100 text-gray-800 p-4 rounded-lg mb-6">
            <CheckCircle2 size={24} className="text-gray-600" />
            <div>
              <p className="font-bold">{!bestSingleStore ? 'No comparable one-store plan' : isSplit ? 'Split plan matches the one-store estimate' : 'One-store plan recommended'}</p>
              <p className="text-sm text-gray-600">{!bestSingleStore ? 'No single store covers as many items as this plan.' : 'The comparison includes estimated transport.'}</p>
            </div>
          </div>
        )}

        <h3 className="font-semibold text-gray-900 mb-4">Stores and Items</h3>
        <div className="space-y-4">
          {recommendation.stores.map((store) => {
            const storeItems = recommendation.items.filter(i => i.assignedStoreId === store.storeId);
            return (
              <div key={store.storeId} className="border border-gray-200 rounded-lg p-4">
                <div className="flex justify-between items-center mb-3 pb-3 border-b border-gray-100">
                  <div className="flex items-center gap-2">
                    <Store size={18} className="text-emerald-600" />
                    <h4 className="font-bold text-gray-900">{store.storeName}</h4>
                    <span className="text-xs text-gray-500 flex items-center gap-1">
                      <MapPin size={12} /> {store.distanceKm} km
                    </span>
                  </div>
                  <p className="font-semibold text-gray-900">R{store.storeSubtotal.toFixed(2)}</p>
                </div>
                <ul className="space-y-2">
                  {storeItems.map((item, idx) => (
                    <li key={idx} className="flex justify-between text-sm">
                      <span className="text-gray-700">{item.quantity}x {item.productName}</span>
                      <span className="text-gray-500">R{item.lineTotal.toFixed(2)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </div>

        {/* Cost Breakdown */}
        <div className="mt-6 pt-4 border-t border-gray-200 space-y-2 text-sm">
          <div className="flex justify-between text-gray-600">
            <span>Groceries Subtotal</span>
            <span>R{recommendation.totalProductCost.toFixed(2)}</span>
          </div>
          <div className="flex justify-between text-gray-600">
            <span>Estimated transport (R{costPerKm.toFixed(2)}/km)</span>
            <span>R{recommendation.totalTransportCost.toFixed(2)}</span>
          </div>
          <div className="flex justify-between font-bold text-gray-900 text-base pt-2">
            <span>Final Total</span>
            <span>R{recommendation.grandTotal.toFixed(2)}</span>
          </div>
        </div>
      </div>
    </CardContent>
      </Card >
    </div >
  );
}