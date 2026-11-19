import { MapPin, Navigation } from 'lucide-react';
import { Card, CardContent } from '../components/ui/Card';

export default function NearbyStores({ location }) {
  // Note: You will eventually fetch this from a /stores/nearby endpoint.
  // For now, we display the mock data we know is in the DB.
  const mockStores = [
    { id: 1, name: 'Checkers Sea Point', retailer: 'Checkers', distance: '0.9 km', type: 'Supermarket' },
    { id: 2, name: 'Pick n Pay Green Point', retailer: 'Pick n Pay', distance: '1.4 km', type: 'Supermarket' },
    { id: 3, name: 'Woolworths Online', retailer: 'Woolworths', distance: 'Online', type: 'Delivery' },
    { id: 4, name: 'Checkers Online', retailer: 'Checkers', distance: 'Online', type: 'Delivery' },
  ];

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Nearby Stores</h1>
        <p className="text-gray-500 mt-1">Discover supermarkets around your current location.</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {mockStores.map(store => (
          <Card key={store.id}>
            <CardContent className="p-5 flex items-start gap-4">
              <div className="p-3 bg-emerald-50 text-emerald-700 rounded-lg">
                <MapPin size={24} />
              </div>
              <div className="flex-1">
                <div className="flex justify-between items-start">
                  <div>
                    <h3 className="font-bold text-gray-900">{store.name}</h3>
                    <p className="text-sm text-gray-500">{store.retailer} • {store.type}</p>
                  </div>
                  <span className="text-xs font-semibold bg-gray-100 text-gray-600 px-2 py-1 rounded">
                    {store.distance}
                  </span>
                </div>
                <div className="mt-4 flex gap-2">
                  <button className="text-sm text-emerald-600 font-medium hover:underline flex items-center gap-1">
                    <Navigation size={14} /> Get Directions
                  </button>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}