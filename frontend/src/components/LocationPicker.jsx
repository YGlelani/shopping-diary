import { useEffect, useState } from 'react';
import { CircleMarker, MapContainer, Popup, TileLayer, useMap } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';

function Focus({ lat, lon }) {
  const map = useMap();
  useEffect(() => { map.panTo([lat, lon]); }, [map, lat, lon]);
  return null;
}

export default function StoreMap({ location, stores, selected, onSelect }) {
  const [tileError, setTileError] = useState(false);
  const origin = location ?? stores[0];
  const target = stores.find(store => store.id === selected) ?? origin;

  return (
    <div className="space-y-2" aria-label="Branch map">
      <div className="relative z-0 h-72 overflow-hidden rounded-xl border sm:h-96">
        <MapContainer center={[origin.lat, origin.lon]} zoom={12} style={{ height: '100%', width: '100%' }}>
          <TileLayer
            url={import.meta.env.VITE_MAP_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'}
            attribution={import.meta.env.VITE_MAP_ATTRIBUTION || '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'}
            eventHandlers={{ tileerror: () => setTileError(true) }}
          />
          <Focus lat={target.lat} lon={target.lon} />
          {location && <CircleMarker center={[location.lat, location.lon]} radius={7} pathOptions={{ color: '#1d4ed8', fillOpacity: 0.9 }}>
            <Popup>Selected shopping location</Popup>
          </CircleMarker>}
          {stores.map(store => (
            <CircleMarker key={store.id} center={[store.lat, store.lon]} radius={store.id === selected ? 11 : 8}
              pathOptions={{ color: '#047857', fillColor: '#10b981', fillOpacity: 0.85 }}
              eventHandlers={{ click: () => onSelect(store.id) }}>
              <Popup>{store.retailer} · {store.name}</Popup>
            </CircleMarker>
          ))}
        </MapContainer>
      </div>
      {tileError && <p role="status" className="text-sm text-gray-600">Map tiles could not load. Branch details are available in the list.</p>}
    </div>
  );
}
