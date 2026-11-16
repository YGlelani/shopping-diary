import { useState, useEffect } from 'react';
import { apiRequest } from './api';

export default function App() {
  const [token, setToken] = useState(localStorage.getItem('token') || null);
  const [email, setEmail] = useState('tester@example.com');
  const [password, setPassword] = useState('TestPass123!');
  const [error, setError] = useState('');

  // Location defaults for testing (Cape Town)
  const lat = -33.91;
  const lon = 18.39;

  const [listId, setListId] = useState(null);
  const [searchQuery, setSearchQuery] = useState('bread'); // Default search
  const [searchResults, setSearchResults] = useState([]);
  const [cart, setCart] = useState([]);
  const [optimization, setOptimization] = useState(null);
  const [isOptimizing, setIsOptimizing] = useState(false);

  const handleLogout = () => {
    setToken(null);
    localStorage.removeItem('token');
    setCart([]);
    setListId(null);
    setOptimization(null);
    setError('');
  };

  // 1. Login Handler
  const handleLogin = async (e) => {
    e.preventDefault();
    setError('');
    try {
      const data = await apiRequest('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      });
      setToken(data.token);
      localStorage.setItem('token', data.token);
    } catch (err) {
      setError(err.message);
    }
  };

  // 2. Fetch or create a list on login
  useEffect(() => {
    if (!token) return;
    
    async function initList() {
      try {
        const lists = await apiRequest('/lists', {}, token);
        if (lists.length > 0) {
          setListId(lists[0].id);
          setCart(lists[0].items);
        } else {
          const newList = await apiRequest('/lists', {
            method: 'POST',
            body: JSON.stringify({ name: 'My Shopping List', budgetAmount: 500 }),
          }, token);
          setListId(newList.id);
        }
      } catch (err) {
        // If token is invalid/expired, force logout
        if (err.message === 'Forbidden' || err.message === 'Unauthorized') {
          handleLogout();
        } else {
          setError(err.message);
        }
      }
    }
    initList();
  }, [token]);

  // 3. Search Products
  const handleSearch = async (e) => {
    e.preventDefault();
    if (!searchQuery.trim()) return;
    try {
      const res = await apiRequest(`/products/search?q=${searchQuery}&lat=${lat}&lon=${lon}&r=10`);
      setSearchResults(res);
      setError('');
    } catch (err) {
      setError(err.message);
    }
  };

  // 4. Add Item to Cart
  const addToCart = async (productId) => {
    try {
      await apiRequest(`/lists/${listId}/items`, {
        method: 'POST',
        body: JSON.stringify({ productId, quantity: 1 }),
      }, token);
      
      // Refresh cart
      const updatedList = await apiRequest(`/lists/${listId}`, {}, token);
      setCart(updatedList.items);
      setOptimization(null); // Reset optimization when cart changes
    } catch (err) {
      setError(err.message);
    }
  };

    const removeFromCart = async (itemId) => {
    try {
      await apiRequest(`/lists/${listId}/items/${itemId}`, {
        method: 'DELETE',
      }, token);
      
      const updatedList = await apiRequest(`/lists/${listId}`, {}, token);
      setCart(updatedList.items);
      setOptimization(null);
    } catch (err) {
      setError(err.message);
    }
  };

  // 5. Optimize
  const handleOptimize = async () => {
    setIsOptimizing(true);
    setError('');
    try {
      const res = await apiRequest(`/lists/${listId}/optimize`, {
        method: 'POST',
        body: JSON.stringify({ lat, lon, radiusKm: 10.0, costPerKm: 4.50 }),
      }, token);
      setOptimization(res);
    } catch (err) {
      setError(err.message);
    } finally {
      setIsOptimizing(false);
    }
  };

    // Add this state at the top with your other states
  const [isRegister, setIsRegister] = useState(false);

  // Update the handleLogin function to handle both Login and Register
  const handleAuth = async (e) => {
    e.preventDefault();
    setError('');
    try {
      const endpoint = isRegister ? '/auth/register' : '/auth/login';
      const body = isRegister 
        ? { email, password, displayName: 'Test User' } 
        : { email, password };

      const data = await apiRequest(endpoint, {
        method: 'POST',
        body: JSON.stringify(body),
      });
      setToken(data.token);
      localStorage.setItem('token', data.token);
    } catch (err) {
      setError(err.message);
    }
  };

  // --- RENDER ---

    if (!token) {
    return (
      <div className="min-h-screen bg-gray-100 flex items-center justify-center p-4">
        {/* Change onSubmit to handleAuth */}
        <form onSubmit={handleAuth} className="bg-white p-8 rounded-xl shadow-md w-full max-w-sm">
          <h1 className="text-2xl font-bold mb-6 text-gray-800 text-center">
            {isRegister ? 'Create Account' : 'Shopping Diary'}
          </h1>
          {error && <p className="text-red-500 text-sm mb-4">{error}</p>}
          <input 
            type="email" value={email} onChange={e => setEmail(e.target.value)} 
            placeholder="Email" className="w-full mb-3 p-2 border rounded" required
          />
          <input 
            type="password" value={password} onChange={e => setPassword(e.target.value)} 
            placeholder="Password" className="w-full mb-4 p-2 border rounded" required
          />
          <button type="submit" className="w-full bg-blue-600 text-white p-2 rounded hover:bg-blue-700 transition mb-4">
            {isRegister ? 'Sign Up' : 'Log In'}
          </button>
          
          <div className="text-center text-sm text-gray-500">
            {isRegister ? 'Already have an account? ' : "Don't have an account? "}
            <button 
              type="button" 
              onClick={() => { setIsRegister(!isRegister); setError(''); }} 
              className="text-blue-600 hover:underline"
            >
              {isRegister ? 'Log In' : 'Register'}
            </button>
          </div>
        </form>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 text-gray-800 font-sans">
      {/* Header */}
      <header className="bg-white shadow-sm p-4 flex justify-between items-center">
        <h1 className="text-xl font-bold text-blue-600">🛒 Shopping Diary</h1>
        <div className="flex gap-4 items-center">
          <span className="text-sm text-gray-500">{email}</span>
          <button onClick={handleLogout} className="text-sm text-red-600 hover:underline">Logout</button>
        </div>
      </header>

      <main className="max-w-6xl mx-auto p-6 grid grid-cols-1 md:grid-cols-2 gap-6">
        
        {/* Left Column: Search */}
        <div className="space-y-4">
          <div className="bg-white p-4 rounded-lg shadow-sm">
            <h2 className="font-semibold mb-3">Find Products</h2>
            <form onSubmit={handleSearch} className="flex gap-2">
              <input 
                type="text" value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
                className="flex-1 p-2 border rounded" placeholder="Search groceries..."
              />
              <button type="submit" className="bg-gray-800 text-white px-4 py-2 rounded hover:bg-gray-900">
                Search
              </button>
            </form>
          </div>

          <div className="space-y-2">
            {searchResults.map(p => (
              <div key={p.productId} className="bg-white p-4 rounded-lg shadow-sm flex justify-between items-center">
                <div>
                  <h3 className="font-medium text-sm">{p.canonicalName}</h3>
                  <p className="text-xs text-gray-500">{p.brand} • {p.sizeText}</p>
                  <p className="text-sm font-bold mt-1">From R{p.minEffectivePrice}</p>
                </div>
                <button 
                  onClick={() => addToCart(p.productId)}
                  className="bg-blue-100 text-blue-700 px-3 py-1 rounded text-sm font-medium hover:bg-blue-200"
                >
                  + Add
                </button>
              </div>
            ))}
          </div>
        </div>

        {/* Right Column: Cart & Optimizer */}
        <div className="space-y-4">
          <div className="bg-white p-4 rounded-lg shadow-sm">
            <h2 className="font-semibold mb-3">Your List ({cart.length} items)</h2>
            {cart.length === 0 ? (
              <p className="text-sm text-gray-500 italic">List is empty.</p>
            ) : (
                            <ul className="divide-y divide-gray-100 mb-4">
                {cart.map((item) => (
                  <li key={item.id} className="py-2 flex justify-between items-center text-sm">
                    <span>{item.productName}</span>
                    <div className="flex gap-3 items-center">
                      <span className="text-gray-500">x{item.quantity}</span>
                      <button onClick={() => removeFromCart(item.id)} className="text-red-500 hover:text-red-700 text-xs font-bold">
                        ✕
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}

            <button 
              onClick={handleOptimize} disabled={cart.length === 0 || isOptimizing}
              className="w-full bg-green-600 text-white p-2 rounded hover:bg-green-700 disabled:opacity-50 transition font-medium"
            >
              {isOptimizing ? 'Calculating...' : 'Find Cheapest Plan'}
            </button>
          </div>

          {error && <div className="bg-red-50 text-red-600 p-3 rounded border border-red-200 text-sm">{error}</div>}

          {optimization && (
            <div className="bg-white p-4 rounded-lg shadow-sm border-t-4 border-green-500">
              <h2 className="font-bold text-lg mb-2">Optimization Result</h2>
              <p className="text-sm text-gray-600 mb-4">
                Total Cost: <span className="text-xl font-bold text-gray-900">R{optimization.recommendation.grandTotal.toFixed(2)}</span>
              </p>

              {optimization.savingsVsBestSingle > 0 && (
                <div className="bg-green-50 text-green-700 p-2 rounded text-sm mb-4">
                  🎉 You save R{optimization.savingsVsBestSingle} by splitting your shop!
                </div>
              )}

              <h3 className="font-semibold text-sm mb-2 text-gray-700">Recommended Stores:</h3>
              <div className="space-y-2">
                {optimization.recommendation.stores.map(s => (
                  <div key={s.storeId} className="text-sm border-l-2 border-gray-300 pl-3">
                    <p className="font-medium">{s.storeName} ({s.distanceKm} km)</p>
                    <p className="text-gray-500">Subtotal: R{s.storeStoreSubtotal || s.storeSubtotal} + Transport: R{s.transportCost}</p>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}