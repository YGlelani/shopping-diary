import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { apiRequest } from '../api/client';
import LocationPicker from '../components/LocationPicker';

export default function NearbyStores({ location, onLocationChange, token, radius, onRadiusChange }) {
  const returnTo = useLocation().state?.returnTo;
  const [result, setResult] = useState(null);
  const [areaQuery, setAreaQuery] = useState('');
  const [areaResult, setAreaResult] = useState(null);

  async function findAreas(event) {
    event.preventDefault();
    setAreaResult({ loading: true });
    try {
      const rows = await apiRequest('/stores/areas?' + new URLSearchParams({ q: areaQuery.trim() }), {}, token);
      setAreaResult({ rows });
    } catch (error) {
      setAreaResult({ error: error.message });
    }
  }

  useEffect(() => {
    if (!location) return;
    const controller = new AbortController();
    apiRequest(`/stores/nearby?lat=${location.lat}&lon=${location.lon}&r=${radius}`, { signal: controller.signal }, token)
      .then(stores => setResult({ location, radius, stores }))
      .catch(error => {
        if (!controller.signal.aborted) setResult({ location, radius, error: error.message });
      });
    return () => controller.abort();
  }, [location, radius, token]);

  const current = result?.location === location && result?.radius === radius ? result : null;
  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <h1 className="text-2xl font-bold text-gray-900">Nearby Stores</h1>
      <form onSubmit={findAreas} className="flex flex-wrap items-end gap-2">
        <label className="text-sm">Town or city<input required minLength={2} maxLength={100} value={areaQuery}
          onChange={event => setAreaQuery(event.target.value)} className="block rounded border px-2 py-1" /></label>
        <button className="rounded bg-emerald-700 px-3 py-1 text-white" disabled={areaResult?.loading}>Find area</button>
      </form>
      {areaResult?.error && <p role="alert">{areaResult.error}</p>}
      {areaResult?.rows?.length === 0 && <p>No registered branches match that area.</p>}
      {areaResult?.rows?.map(area => (
        <button key={area.city + area.province} onClick={() => { onLocationChange(area.lat, area.lon); setAreaResult(null); }}
          className="mr-2 rounded border px-2 py-1">{[area.city, area.province].filter(Boolean).join(', ')}</button>
      ))}
      <label className="block text-sm">Radius
        <select value={radius} onChange={event => onRadiusChange(Number(event.target.value))} className="ml-2 rounded border p-1">
          {[5, 10, 15, 25, 50, 100].map(km => <option key={km} value={km}>{km} km</option>)}
        </select>
      </label>
      <LocationPicker key={location ? [location.lat, location.lon].join() : "unset"} onLocationChange={onLocationChange} defaultLat={location?.lat ?? -33.91} defaultLon={location?.lon ?? 18.39} />
      {!location && <p>Use GPS or select your shopping location on the map.</p>}
      {location && returnTo && <Link className="text-emerald-700 underline" to={returnTo}>Continue</Link>}
      {location && !current && <p>Finding nearby stores...</p>}
      {current?.error && <p role="alert">{current.error}</p>}
      {current?.stores?.length === 0 && <p>No stores found within {radius} km.</p>}
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
