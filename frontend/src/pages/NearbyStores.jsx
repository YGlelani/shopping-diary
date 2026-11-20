import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { apiRequest } from '../api/client';
import LocationPicker from '../components/LocationPicker';

export default function NearbyStores({ location, onLocationChange, token }) {
  const returnTo = useLocation().state?.returnTo;
  const [result, setResult] = useState(null);

  useEffect(() => {
    if (!location) return;
    const controller = new AbortController();
    apiRequest(`/stores/nearby?lat=${location.lat}&lon=${location.lon}`, { signal: controller.signal }, token)
      .then(stores => setResult({ location, stores }))
      .catch(error => {
        if (!controller.signal.aborted) setResult({ location, error: error.message });
      });
    return () => controller.abort();
  }, [location, token]);

  const current = result?.location === location ? result : null;
  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <h1 className="text-2xl font-bold text-gray-900">Nearby Stores</h1>
      <LocationPicker onLocationChange={onLocationChange} defaultLat={location?.lat ?? -33.91} defaultLon={location?.lon ?? 18.39} />
      {!location && <p>Use GPS or select your shopping location on the map.</p>}
      {location && returnTo && <Link className="text-emerald-700 underline" to={returnTo}>Continue</Link>}
      {location && !current && <p>Finding nearby stores...</p>}
      {current?.error && <p role="alert">{current.error}</p>}
      {current?.stores?.length === 0 && <p>No stores found within 15 km.</p>}
      <ul className="grid gap-4 sm:grid-cols-2">
        {current?.stores?.map(store => (
          <li key={store.id} className="rounded-lg border bg-white p-4">
            <h2 className="font-semibold">{store.name}</h2>
            <p>{store.retailer} ({store.distanceKm.toFixed(1)} km away)</p>
            <a className="text-emerald-700 underline" target="_blank" rel="noreferrer"
              href={`https://www.google.com/maps/dir/?api=1&destination=${store.lat},${store.lon}`}>
              Get directions
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
