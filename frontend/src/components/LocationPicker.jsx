import { useState } from 'react';
import { MapContainer, TileLayer, Marker, useMapEvents } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';
import L from 'leaflet';

// Fix for missing default marker icons in React-Leaflet
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon-2x.png',
  iconUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon.png',
  shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-shadow.png',
});

function LocationMarker({ position, setPosition }) {
  useMapEvents({
    click(e) {
      setPosition(e.latlng);
    },
  });
  return position === null ? null : <Marker position={position} />;
}

export default function LocationPicker({ onLocationChange, defaultLat, defaultLon }) {
  const [position, setPosition] = useState({ lat: defaultLat, lng: defaultLon });
  const [locating, setLocating] = useState(false);

  function selectPosition(nextPosition) {
    setPosition(nextPosition);
    onLocationChange(nextPosition.lat, nextPosition.lng);
  }

  const handleGetLocation = () => {
    setLocating(true);
    if (!navigator.geolocation) {
      alert('Geolocation is not supported by your browser');
      setLocating(false);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        selectPosition({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setLocating(false);
      },
      () => {
        alert('Unable to retrieve your location. Please click the map to set it manually.');
        setLocating(false);
      },
      { timeout: 10000 }
    );
  };

  return (
    <div className="bg-white p-4 rounded-lg shadow-sm space-y-3">
      <div className="flex justify-between items-center">
        <h2 className="font-semibold text-gray-800">Your Location</h2>
        <button onClick={handleGetLocation} disabled={locating} className="text-sm bg-blue-100 text-blue-700 px-3 py-1 rounded hover:bg-blue-200">
          {locating ? 'Locating...' : '📍 Use My GPS'}
        </button>
      </div>
      
      <div className="h-64 rounded-lg overflow-hidden border border-gray-200 z-0">
        <MapContainer center={[position.lat, position.lng]} zoom={13} style={{ height: '100%', width: '100%' }}>
          <TileLayer
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
          />
          <LocationMarker position={position} setPosition={selectPosition} />
        </MapContainer>
      </div>
      <p className="text-xs text-gray-500 text-center">Click the map to adjust your location manually.</p>
    </div>
  );
}