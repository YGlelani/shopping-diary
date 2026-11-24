import AddToListDialog from './components/AddToListDialog';

import {
  useState,
  useEffect,
  useRef,
  useCallback,
  lazy,
  Suspense,
} from 'react';

import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
} from 'react-router-dom';

import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Discover from './pages/Discover';
import ShoppingLists from './pages/ShoppingLists';
import Optimizer from './pages/Optimizer';
import Budgets from './pages/Budgets';
import AppLayout from './components/layout/AppLayout';
import Admin from './pages/Admin';
import PriceAlerts from './pages/PriceAlerts';
import { apiRequest } from './api/client';

const StoreDetails = lazy(() => import('./pages/StoreDetails'));
const NearbyStores = lazy(
  () => import('./pages/NearbyStores')
);

export default function App() {
  const [token, setToken] = useState(
    () => localStorage.getItem('token') || null
  );

  const handleLogout = useCallback(() => {
    localStorage.removeItem('token');
    localStorage.removeItem('shoppingDiary.location');
    localStorage.removeItem(
      'shoppingDiary.activeListId'
    );
    setToken(null);
  }, []);

  return token ? (
    <SignedInApp
      key={token}
      token={token}
      onLogout={handleLogout}
    />
  ) : (
    <Login onLogin={setToken} />
  );
}

function SignedInApp({
  token,
  onLogout,
}) {
  const [user, setUser] = useState(null);
  const [lists, setLists] = useState([]);
  const [listId, setListId] = useState(null);
  const [setupError, setSetupError] = useState('');

  const [location, setLocation] = useState(null);

  useEffect(() => {
    try { localStorage.removeItem('shoppingDiary.location'); } catch { /* storage unavailable */ }
  }, []);

  const [destination, setDestination] = useState(null);
  const createRequest = useRef(null);
  const [radius, setRadius] = useState(15);

  const activeList = lists.find(
    list => list.id === listId
  );

  const planLocation = activeList?.locationLat != null
    ? { lat: activeList.locationLat, lon: activeList.locationLon, label: activeList.locationLabel }
    : location;

  const changeLocation = useCallback(
    (lat, lon, label) => {
      if (lat == null && lon == null) {
        setLocation(null);
        return;
      }
      const latitude = Number(lat);
      const longitude = Number(lon);

      if (
        !Number.isFinite(latitude) ||
        !Number.isFinite(longitude) ||
        latitude < -90 ||
        latitude > 90 ||
        longitude < -180 ||
        longitude > 180
      ) {
        return;
      }

      const next = {
        lat: latitude,
        lon: longitude,
        label,
      };

      setLocation(next);


    },
    []
  );

  const selectList = useCallback(id => {
    setListId(id);

    if (id == null) {
      localStorage.removeItem(
        'shoppingDiary.activeListId'
      );
    } else {
      localStorage.setItem(
        'shoppingDiary.activeListId',
        String(id)
      );
    }
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function initialize() {
      try {
        const profile = await apiRequest(
          '/auth/me',
          {},
          token
        );

        const savedLists = await apiRequest(
          '/lists',
          {},
          token
        );

        if (cancelled) return;

        const validLists = Array.isArray(
          savedLists
        ) ? savedLists : [];

        const storedListId = Number(
          localStorage.getItem(
            'shoppingDiary.activeListId'
          )
        );

        const selected =
          validLists.find(
            list => list.id === storedListId
          )?.id ??
          validLists[0]?.id ??
          null;

        setUser(profile);
        setLists(validLists);
        setListId(selected);
        setSetupError('');

      } catch (error) {
        if (!cancelled) {
          setSetupError(error.message);
        }
      }
    }

    initialize();

    return () => {
      cancelled = true;
    };
  }, [token]);

  function rememberList(updated) {
    setLists(current => {
      const present = current.find(list => list.id === updated.id);
      if (!present) return [updated, ...current];
      if ((present.revision ?? 0) > (updated.revision ?? 0)) return current;
      return current.map(list => list.id === updated.id ? updated : list);
    });
  }

  async function createList(values) {
    const body = JSON.stringify(values);
    if (createRequest.current?.body !== body) createRequest.current = { body, key: crypto.randomUUID() };
    const created = await apiRequest('/lists', {
      method: 'POST', body, headers: { 'Idempotency-Key': createRequest.current.key },
    }, token);
    createRequest.current = null;
    rememberList(created);
    selectList(created.id);
    return created;
  }

  async function refreshList(id) {
    const updated = await apiRequest('/lists/' + id, {}, token);
    rememberList(updated);
    return updated;
  }

  function forgetList(id) {
    setLists(current => current.filter(list => list.id !== id));
    if (listId === id) selectList(null);
  }

  function addToCart(productId) {
    if (destination) return Promise.resolve(false);
    return new Promise(resolve => setDestination({ productId, resolve }));
  }

  function finishDestination(saved) {
    destination?.resolve(saved);
    setDestination(null);
  }

  if (!user) {
    return (
      <main className="p-8">
        <p>
          {setupError ||
            'Loading your account...'}
        </p>

        {setupError && (
          <button
            type="button"
            onClick={onLogout}
          >
            Return to sign in
          </button>
        )}
      </main>
    );
  }

  return (
    <BrowserRouter>
      <AppLayout
        user={user}
        onLogout={onLogout}
      >
        {setupError && (
          <p
            role="alert"
            className="mb-4 text-red-700"
          >
            {setupError}
          </p>
        )}

        {destination && <AddToListDialog productId={destination.productId} lists={lists} listId={listId}
          token={token} onCreate={createList} onSaved={refreshList} onClose={finishDestination} />}

        <Suspense
          fallback={
            <p>Loading page...</p>
          }
        >
          <Routes>
            <Route path="/budgets" element={<Budgets token={token} lists={lists} location={location} radius={radius} />} />
            <Route
              path="/"
              element={
                <Dashboard
                  lists={lists}
                  token={token}
                  budgetAmount={
                    activeList?.budgetAmount
                  }
                  onSelectList={selectList}
                />
              }
            />

            <Route
              path="/discover"
              element={
                <Discover token={token} onAddItem={addToCart} location={location} radius={radius} onRadiusChange={setRadius} />
              }
            />

            <Route
              path="/lists"
              element={
                <ShoppingLists lists={lists} listId={listId} onSelect={selectList} onCreate={createList}
                  token={token} location={location} radius={radius} onRemember={rememberList}
                  onRefresh={refreshList} onForget={forgetList} onAddItem={addToCart} />
              }
            />

            <Route
              path="/optimize"
              element={
                planLocation ? (
                  <Optimizer
                    radius={radius}
                    key={[listId, activeList?.revision, planLocation.lat, planLocation.lon, radius].join(':')}
                    token={token}
                    listId={listId}
                    location={planLocation}
                    budgetAmount={
                      activeList?.budgetAmount
                    }
                  />
                ) : (
                  <Navigate
                    to="/stores"
                    state={{
                      returnTo: '/optimize',
                    }}
                    replace
                  />
                )
              }
            />

            <Route
              path="/admin"
              element={
                user.role === 'ADMIN' ? (
                  <Admin token={token} />
                ) : (
                  <Navigate
                    to="/"
                    replace
                  />
                )
              }
            />

            <Route
              path="/alerts"
              element={
                <PriceAlerts token={token} />
              }
            />

            <Route
              path="/stores"
              element={
                <NearbyStores
                  radius={radius}
                  onRadiusChange={setRadius}
                  location={location}
                  onLocationChange={
                    changeLocation
                  }
                  token={token}
                />
              }
            />

            <Route path="/stores/:id" element={
              <StoreDetails location={location} token={token} onAddItem={addToCart} />
            } />

            <Route
              path="*"
              element={
                <Navigate
                  to="/"
                  replace
                />
              }
            />
          </Routes>
        </Suspense>
      </AppLayout>
    </BrowserRouter>
  );
}
