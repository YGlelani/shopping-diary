import { useEffect, useRef, useState } from 'react';
import { Crosshair } from 'lucide-react';
import { apiRequest } from '../api/client';
import { Button } from './ui/Button';
import { Input } from './ui/Input';

export default function ShoppingLocationSelector({ location, onLocationChange, token }) {
  const [query, setQuery] = useState('');
  const [province, setProvince] = useState('');
  const [provinces, setProvinces] = useState([]);
  const [result, setResult] = useState(null);
  const [candidate, setCandidate] = useState(null);
  const [gps, setGps] = useState({ status: 'idle' });
  const request = useRef(null);
  const gpsSequence = useRef(0);

  useEffect(() => {
    const sequence = gpsSequence;
    const controller = new AbortController();
    apiRequest('/stores/provinces', { signal: controller.signal }, token)
      .then(rows => { if (!controller.signal.aborted) setProvinces(rows); }).catch(() => {});
    return () => {
      controller.abort();
      request.current?.abort();
      sequence.current++;
    };
  }, [token]);

  useEffect(() => {
    if (!navigator.permissions?.query) return;
    let status;
    let active = true;
    function update() {
      if (!active) return;
      if (status.state === 'denied') {
        gpsSequence.current++;
        setGps({ status: 'denied', message: 'Location permission was denied. Choose a town or enter coordinates.' });
      } else {
        setGps(current => current.status === 'denied' ? { status: 'idle' } : current);
      }
    }
    navigator.permissions.query({ name: 'geolocation' }).then(value => {
      if (!active) return;
      status = value;
      status.addEventListener('change', update);
      update();
    }).catch(() => {});
    return () => {
      active = false;
      status?.removeEventListener('change', update);
    };
  }, []);

  async function search(event) {
    event.preventDefault();
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setCandidate(null);
    setResult({ loading: true });
    try {
      const params = new URLSearchParams({ q: query.trim() || province });
      if (province) params.set('province', province);
      const rows = await apiRequest('/stores/areas?' + params, { signal: controller.signal }, token);
      if (!controller.signal.aborted) setResult({ rows });
    } catch {
      if (!controller.signal.aborted) setResult({ error: 'Location search is unavailable. Try again or enter coordinates.' });
    }
  }

  function useGps() {
    if (gps.status === 'denied' || gps.status === 'loading') return;
    if (!navigator.geolocation) {
      setGps({ status: 'unavailable', message: 'This browser cannot provide GPS. Search for your town or enter coordinates.' });
      return;
    }
    const sequence = ++gpsSequence.current;
    setGps({ status: 'loading' });
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        if (sequence !== gpsSequence.current) return;
        if (!Number.isFinite(coords.latitude) || !Number.isFinite(coords.longitude)
            || Math.abs(coords.latitude) > 90 || Math.abs(coords.longitude) > 180) {
          setGps({ status: 'error', message: 'Your device returned an invalid location. Use manual search.' });
          return;
        }
        if (!Number.isFinite(coords.accuracy) || coords.accuracy < 0 || coords.accuracy > 1000) {
          setGps({ status: 'error', message: 'The GPS reading is too imprecise for nearby stores. Choose your town or try again.' });
          return;
        }
        setGps({ status: 'idle' });
        setCandidate(null);
        onLocationChange(coords.latitude, coords.longitude, 'GPS location');
      },
      error => {
        if (sequence !== gpsSequence.current) return;
        const message = error.code === 1
          ? 'Location permission was denied. Choose a town or enter coordinates.'
          : error.code === 3
            ? 'GPS timed out. Try again or choose a location manually.'
            : 'Your device could not determine its location. Check location services or use manual search.';
        setGps({ status: error.code === 1 ? 'denied' : 'error', message });
      },
      { timeout: 10000, maximumAge: 0, enableHighAccuracy: false },
    );
  }

  function confirm(lat, lon, label) {
    gpsSequence.current++;
    setGps(current => current.status === 'denied' ? current : { status: 'idle' });
    onLocationChange(lat, lon, label);
    setCandidate(null);
    setResult(null);
  }

  return (
    <section className="space-y-3 rounded-xl border bg-white p-4" aria-label="Shopping location">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div><h2 className="font-semibold">Shopping location</h2>
          <p className="text-sm text-gray-600">{location?.label ?? (location ? 'Selected location' : 'Choose a location to find nearby branches.')}</p></div>
        {location && <Button variant="secondary" onClick={() => confirm(null, null)}>Clear location</Button>}
      </div>
      <form onSubmit={search} className="flex flex-wrap items-end gap-2">
        <label className="min-w-48 flex-1 text-sm">Town, suburb, shopping area or postal code
          <Input value={query} maxLength={100} onChange={event => setQuery(event.target.value)} />
        </label>
        <label className="text-sm">Province
          <select value={province} onChange={event => setProvince(event.target.value)} className="block rounded border p-2">
            <option value="">All provinces</option>{provinces.map(name => <option key={name}>{name}</option>)}
          </select>
        </label>
        <Button type="submit" disabled={result?.loading || (query.trim().length < 2 && !province)}>Search locations</Button>
      </form>
      {result?.loading && <p role="status">Searching locations…</p>}
      {result?.error && <p role="alert">{result.error}</p>}
      {result?.rows?.length === 0 && <p>No matching locations. Try another name, province or postal code.</p>}
      {result?.rows?.length > 0 && (
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">Choose a search result</legend>
          <div className="max-h-52 space-y-1 overflow-y-auto">
            {result.rows.map(place => (
              <label key={place.id} className="flex items-start gap-2 rounded border p-2 text-sm">
                <input type="radio" name="locality" checked={candidate?.id === place.id} onChange={() => setCandidate(place)} />
                <span>{place.city}{place.province && place.city !== place.province ? ', ' + place.province : ''}
                  {place.area && <small className="block text-gray-600">{place.area}</small>}</span>
              </label>
            ))}
          </div>
          <Button disabled={!candidate} onClick={() => confirm(candidate.lat, candidate.lon,
            [...new Set([candidate.city, candidate.province].filter(Boolean))].join(', '))}>Confirm location</Button>
          <p className="text-xs text-gray-500">Locality reference points from <a className="underline" href="https://www.geonames.org/">GeoNames</a>.</p>
        </fieldset>
      )}
      <p className="text-sm text-gray-600">GPS is optional. It uses a one-time reading to find nearby branches.</p>
      <Button variant="secondary" disabled={['denied', 'loading', 'unavailable'].includes(gps.status)} onClick={useGps}>
        <Crosshair size={16} className="mr-2" />{gps.status === 'loading' ? 'Locating…' : 'Use my location'}
      </Button>
      {gps.message && <p role="status" className="text-sm">{gps.message}</p>}
      <details className="text-sm">
        <summary>Enter coordinates manually</summary>
        <form className="mt-2 flex flex-wrap items-end gap-2" onSubmit={event => {
          event.preventDefault();
          const data = new FormData(event.currentTarget);
          confirm(Number(data.get('lat')), Number(data.get('lon')), 'Manual coordinates');
        }}>
          <label>Latitude<Input name="lat" type="number" min="-90" max="90" step="any" required /></label>
          <label>Longitude<Input name="lon" type="number" min="-180" max="180" step="any" required /></label>
          <Button type="submit">Use coordinates</Button>
        </form>
      </details>
    </section>
  );
}
