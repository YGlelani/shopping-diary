import { useState, useEffect, useCallback, lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Discover from './pages/Discover';
import ShoppingLists from './pages/ShoppingLists';
import Optimizer from './pages/Optimizer';
import AppLayout from './components/layout/AppLayout';
import { apiRequest } from './api/client';
import Admin from './pages/Admin';
import PriceAlerts from './pages/PriceAlerts';
const NearbyStores = lazy(() => import('./pages/NearbyStores'));

export default function App() {
  const [token, setToken] = useState(localStorage.getItem('token') || null);
  const handleLogout = () => {
    setToken(null);
    localStorage.removeItem('token');
  };

  return token
    ? <SignedInApp key={token} token={token} onLogout={handleLogout} />
    : <Login onLogin={setToken} />;
}

function SignedInApp({ token, onLogout }) {
  const [user, setUser] = useState(null);
  const [lists, setLists] = useState([]);
  const [listId, setListId] = useState(null);
  const [setupError, setSetupError] = useState('');
  const [location, setLocation] = useState(null);
  const [saving, setSaving] = useState(false);
  const activeList = lists.find(list => list.id === listId);
  const cart = activeList?.items ?? [];

  const changeLocation = useCallback((lat, lon) => setLocation({ lat, lon }), []);

  useEffect(() => {
    let cancelled = false;
    async function initialize() {
      try {
        const profile = await apiRequest('/auth/me', {}, token);
        if (cancelled) return;
        const savedLists = await apiRequest('/lists', {}, token);
        if (cancelled) return;
        setLists(savedLists);
        setListId(savedLists[0]?.id ?? null);
        setUser(profile);
      } catch (error) {
        if (!cancelled) setSetupError(error.message);
      }
    }
    initialize();
    return () => { cancelled = true; };
  }, [token]);

  async function createList(name, budgetAmount) {
    const list = await apiRequest('/lists', {
      method: 'POST', body: JSON.stringify({ name, budgetAmount }),
    }, token);
    setLists(current => [list, ...current]);
    setListId(list.id);
  }

  async function updateItems(endpoint, options) {
    if (!listId || saving) return;
    setSaving(true);
    try {
      await apiRequest(endpoint, options, token);
      const updated = await apiRequest(`/lists/${listId}`, {}, token);
      setLists(current => current.map(list => list.id === updated.id ? updated : list));
    } catch (error) {
      alert(error.message);
    } finally {
      setSaving(false);
    }
  }

  function addToCart(productId) {
    if (!listId) {
      alert('Create a shopping list before adding products.');
      return;
    }
    const item = cart.find(entry => entry.productId === productId);
    return item
      ? updateItems(`/lists/${listId}/items/${item.id}/increment`, { method: 'POST' })
      : updateItems(`/lists/${listId}/items`, {
          method: 'POST', body: JSON.stringify({ productId, quantity: 1 }),
        });
  }

  if (!user) return (
    <main className="p-8">
      <p>{setupError || 'Loading your account...'}</p>
      {setupError && <button onClick={onLogout}>Return to sign in</button>}
    </main>
  );

  return (
    <BrowserRouter>
      <AppLayout user={user} onLogout={onLogout}>
        {setupError && <p role="alert" className="mb-4 text-red-700">{setupError}</p>}
        <Suspense fallback={<p>Loading page...</p>}>
          <Routes>
            <Route path="/" element={<Dashboard lists={lists} token={token} budgetAmount={activeList?.budgetAmount} onSelectList={setListId} />} />
            <Route path="/discover" element={location ? <Discover token={token} onAddItem={addToCart} location={location} /> : <Navigate to="/stores" state={{ returnTo: "/discover" }} replace />} />
            <Route path="/lists" element={<ShoppingLists cart={cart} lists={lists} listId={listId} onSelect={setListId} onCreate={createList} onRemoveItem={id => updateItems(`/lists/${listId}/items/${id}`, { method: 'DELETE' })} />} />
            <Route path="/optimize" element={location ? <Optimizer key={listId} token={token} listId={listId} location={location} budgetAmount={activeList?.budgetAmount} /> : <Navigate to="/stores" state={{ returnTo: "/optimize" }} replace />} />

            <Route path="/admin" element={user.role === 'ADMIN' ? <Admin token={token} /> : <Navigate to="/" replace />} />

            <Route path="/alerts" element={<PriceAlerts token={token} />} />
            <Route path="/stores" element={<NearbyStores location={location} onLocationChange={changeLocation} token={token} />} />

            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </AppLayout>
    </BrowserRouter>
  );
}