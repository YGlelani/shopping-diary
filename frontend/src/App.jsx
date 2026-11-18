import { useState, useEffect, useCallback } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Discover from './pages/Discover';
import ShoppingLists from './pages/ShoppingLists';
import Optimizer from './pages/Optimizer';
import AppLayout from './components/layout/AppLayout';
import { apiRequest } from './api/client';
import Admin from './pages/Admin';

export default function App() {
  const [token, setToken] = useState(localStorage.getItem('token') || null);
  const [user, setUser] = useState({ email: 'tester@example.com', role: 'ADMIN' });

  const [listId, setListId] = useState(null);
  const [cart, setCart] = useState([]);
  const [location] = useState({ lat: -33.91, lon: 18.39 });

  useEffect(() => {
    if (!token) return;
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
        if (err.message === 'Forbidden') handleLogout();
      }
    }
    initList();
  }, [token]);

  const handleLogout = () => {
    setToken(null);
    localStorage.removeItem('token');
  };

  const addToCart = useCallback(async (productId) => {
    if (!listId) return;
    try {
      const existingItem = cart.find(item => item.productId === productId);
      if (existingItem) {
        await apiRequest(`/lists/${listId}/items/${existingItem.id}`, { method: 'DELETE' }, token);
        await apiRequest(`/lists/${listId}/items`, {
          method: 'POST', body: JSON.stringify({ productId, quantity: existingItem.quantity + 1 })
        }, token);
      } else {
        await apiRequest(`/lists/${listId}/items`, {
          method: 'POST', body: JSON.stringify({ productId, quantity: 1 })
        }, token);
      }
      const updatedList = await apiRequest(`/lists/${listId}`, {}, token);
      setCart(updatedList.items);
    } catch (err) {
      alert("Error adding item: " + err.message);
    }
  }, [listId, cart, token]);

  const removeFromCart = async (itemId) => {
    try {
      await apiRequest(`/lists/${listId}/items/${itemId}`, { method: 'DELETE' }, token);
      const updatedList = await apiRequest(`/lists/${listId}`, {}, token);
      setCart(updatedList.items);
    } catch (err) {
      alert(err.message);
    }
  };

  if (!token) return <Login onLogin={setToken} />;

  return (
    <BrowserRouter>
      <AppLayout user={user} onLogout={handleLogout}>
        <Routes>
          <Route path="/" element={<Dashboard token={token} />} />
          <Route path="/discover" element={<Discover token={token} listId={listId} onAddItem={addToCart} location={location} />} />
          <Route path="/lists" element={<ShoppingLists cart={cart} onRemoveItem={removeFromCart} />} />
          <Route path="/optimize" element={<Optimizer token={token} listId={listId} location={location} />} />

          <Route path="/alerts" element={<div className="p-8 text-gray-500">Alerts coming next.</div>} />
          <Route path="/admin" element={<Admin token={token} />} />

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppLayout>
    </BrowserRouter>
  );
}