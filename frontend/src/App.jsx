
import {
  useState,
  useEffect,
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
import AppLayout from './components/layout/AppLayout';
import Admin from './pages/Admin';
import PriceAlerts from './pages/PriceAlerts';
import { apiRequest } from './api/client';

const NearbyStores = lazy(
  () => import('./pages/NearbyStores')
);

const LOCATION_KEY = 'shoppingDiary.location';

function readSavedLocation() {
  try {
    const stored = localStorage.getItem(
      LOCATION_KEY
    );

    if (!stored) return null;

    const location = JSON.parse(stored);

    const lat = Number(location.lat);
    const lon = Number(location.lon);

    if (
      !Number.isFinite(lat) ||
      !Number.isFinite(lon) ||
      lat < -90 ||
      lat > 90 ||
      lon < -180 ||
      lon > 180
    ) {
      return null;
    }

    return { lat, lon };
  } catch {
    return null;
  }
}

export default function App() {
  const [token, setToken] = useState(
    () => localStorage.getItem('token') || null
  );

  const handleLogout = useCallback(() => {
    localStorage.removeItem('token');
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

  const [location, setLocation] = useState(
    readSavedLocation
  );

  const [saving, setSaving] = useState(false);
  const [radius, setRadius] = useState(15);

  const activeList = lists.find(
    list => list.id === listId
  );

  const cart = activeList?.items ?? [];

  const changeLocation = useCallback(
    (lat, lon) => {
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
      };

      setLocation(next);

      localStorage.setItem(
        LOCATION_KEY,
        JSON.stringify(next)
      );
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

  async function createList(
    name,
    budgetAmount
  ) {
    const created = await apiRequest(
      '/lists',
      {
        method: 'POST',
        body: JSON.stringify({
          name,
          budgetAmount,
        }),
      },
      token
    );

    setLists(current => [
      created,
      ...current,
    ]);

    selectList(created.id);
  }

  async function updateItems(
    endpoint,
    options
  ) {
    if (!listId || saving) return false;

    setSaving(true);

    try {
      await apiRequest(
        endpoint,
        options,
        token
      );

      const updated = await apiRequest(
        `/lists/${listId}`,
        {},
        token
      );

      setLists(current =>
        current.map(list =>
          list.id === updated.id
            ? updated
            : list
        )
      );
      return true;
    } catch (error) {
      alert(error.message);
      return false;
    } finally {
      setSaving(false);
    }
  }

  function addToCart(productId) {
    if (!listId) {
      alert(
        'Create a shopping list before adding products.'
      );
      return false;
    }

    const existingItem = cart.find(
      entry => entry.productId === productId
    );

    return existingItem
      ? updateItems(
          `/lists/${listId}/items/${existingItem.id}/increment`,
          { method: 'POST' }
        )
      : updateItems(
          `/lists/${listId}/items`,
          {
            method: 'POST',
            body: JSON.stringify({
              productId,
              quantity: 1,
            }),
          }
        );
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

        <Suspense
          fallback={
            <p>Loading page...</p>
          }
        >
          <Routes>
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
                <ShoppingLists
                  cart={cart}
                  lists={lists}
                  listId={listId}
                  onSelect={selectList}
                  onCreate={createList}
                  onRemoveItem={id =>
                    updateItems(
                      `/lists/${listId}/items/${id}`,
                      { method: 'DELETE' }
                    )
                  }
                />
              }
            />

            <Route
              path="/optimize"
              element={
                location ? (
                  <Optimizer
                    radius={radius}
                    key={listId}
                    token={token}
                    listId={listId}
                    location={location}
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
