import { useState, useEffect } from 'react';
import { ShoppingCart, LogOut } from 'lucide-react'; // <-- Import header icons
import { apiRequest } from '../api/client';
import ProductSearch from '../components/ProductSearch';
import ShoppingList from '../components/ShoppingList';
import OptimizerResult from '../components/OptimizerResult';
import LocationPicker from '../components/LocationPicker';
import ProductDetailsModal from '../components/ProductDetailsModal'; // <-- Import Modal

export default function Dashboard({ token, email, onLogout }) {
  const [listId, setListId] = useState(null);
  const [cart, setCart] = useState([]);
  const [optimization, setOptimization] = useState(null);
  const [isOptimizing, setIsOptimizing] = useState(false);
  const [error, setError] = useState('');
  const [location, setLocation] = useState({ lat: -33.91, lon: 18.39 });
  
  // Modal State
  const [selectedProduct, setSelectedProduct] = useState(null);

  useEffect(() => {
    async function initList() {
      try {
        const lists = await apiRequest('/lists', {}, token);
        if (lists.length > 0) {
          setListId(lists[0].id);
          setCart(lists[0].items);
        } else {
          const newList = await apiRequest('/lists', { method: 'POST', body: JSON.stringify({ name: 'My List', budgetAmount: 500 }) }, token);
          setListId(newList.id);
        }
      } catch (err) {
        if (err.message === 'Forbidden' || err.message === 'Unauthorized') onLogout();
        else setError(err.message);
      }
    }
    initList();
  }, [token, onLogout]);

  const handleLocationChange = (lat, lon) => {
    setLocation({ lat, lon });
    setOptimization(null); 
  };

  const addToCart = async (productId) => {
    try {
      await apiRequest(`/lists/${listId}/items`, { method: 'POST', body: JSON.stringify({ productId, quantity: 1 }) }, token);
      const updatedList = await apiRequest(`/lists/${listId}`, {}, token);
      setCart(updatedList.items);
      setOptimization(null); 
    } catch (err) { setError(err.message); }
  };

  const removeFromCart = async (itemId) => {
    try {
      await apiRequest(`/lists/${listId}/items/${itemId}`, { method: 'DELETE' }, token);
      const updatedList = await apiRequest(`/lists/${listId}`, {}, token);
      setCart(updatedList.items);
      setOptimization(null); 
    } catch (err) { setError(err.message); }
  };

  const handleOptimize = async () => {
    setIsOptimizing(true);
    setError('');
    try {
      const res = await apiRequest(`/lists/${listId}/optimize`, {
        method: 'POST',
        body: JSON.stringify({ lat: location.lat, lon: location.lon, radiusKm: 10.0, costPerKm: 4.50 }),
      }, token);
      setOptimization(res);
    } catch (err) { setError(err.message); } finally { setIsOptimizing(false); }
  };

  return (
    <div className="min-h-screen bg-gray-50 text-gray-800 font-sans">
      <header className="bg-white shadow-sm p-4 flex justify-between items-center">
        <h1 className="text-xl font-bold text-blue-600 flex items-center gap-2">
          <ShoppingCart size={24} /> Shopping Diary
        </h1>
        <div className="flex gap-4 items-center">
          <span className="text-sm text-gray-500">{email}</span>
          <button onClick={onLogout} className="text-sm text-red-600 hover:text-red-800 flex items-center gap-1 font-medium transition">
            <LogOut size={16} /> Logout
          </button>
        </div>
      </header>

      <main className="max-w-6xl mx-auto p-6 grid grid-cols-1 md:grid-cols-2 gap-6">
        <div className="space-y-4">
          <LocationPicker onLocationChange={handleLocationChange} defaultLat={location.lat} defaultLon={location.lon} />
          <ProductSearch 
            token={token} 
            onAddProduct={addToCart} 
            lat={location.lat} 
            lon={location.lon} 
            onViewDetails={setSelectedProduct} // <-- Pass the setter
          />
        </div>

        <div className="space-y-4">
          <ShoppingList cart={cart} onRemove={removeFromCart} onOptimize={handleOptimize} isOptimizing={isOptimizing} />
          {error && <div className="bg-red-50 text-red-600 p-3 rounded border border-red-200 text-sm">{error}</div>}
          <OptimizerResult optimization={optimization} />
        </div>
      </main>

      {/* THE MODAL RENDERS HERE */}
      {selectedProduct && (
        <ProductDetailsModal 
          product={selectedProduct} 
          onClose={() => setSelectedProduct(null)} 
        />
      )}
    </div>
  );
}