import { useState } from 'react';
import { Search, Plus } from 'lucide-react'; // <-- Import icons
import { apiRequest } from '../api/client';
import { formatProductPrice } from '../utils/productFormatting';

// Add onViewDetails prop
export default function ProductSearch({ onAddProduct, lat, lon, onViewDetails }) {
  const [query, setQuery] = useState('milk');
  const [results, setResults] = useState([]);

  const handleSearch = async (e) => {
    e.preventDefault();
    if (!query.trim()) return;
    try {
      const params = new URLSearchParams({ q: query.trim(), r: '10' });
      if (Number.isFinite(lat) && Number.isFinite(lon)) {
        params.set('lat', lat);
        params.set('lon', lon);
      }
      const res = await apiRequest('/products/search?' + params);
      setResults(res);
    } catch (err) { alert(err.message); }
  };

  return (
    <div className="space-y-4">
      <div className="bg-white p-4 rounded-lg shadow-sm">
        <h2 className="font-semibold mb-3">Find Products</h2>
        <form onSubmit={handleSearch} className="flex gap-2">
          <input type="text" value={query} onChange={e => setQuery(e.target.value)} className="flex-1 p-2 border rounded" placeholder="Search groceries..." />
          <button type="submit" className="bg-gray-800 text-white px-4 py-2 rounded hover:bg-gray-900 flex items-center gap-2">
            <Search size={18} /> Search
          </button>
        </form>
      </div>
      
      <div className="space-y-2">
        {results.map(p => (
          <div key={p.productId} className="bg-white p-4 rounded-lg shadow-sm flex justify-between items-center">
            {/* CLICKABLE AREA */}
            <div className="cursor-pointer hover:opacity-70 transition" onClick={() => onViewDetails(p)}>
              <h3 className="font-medium text-sm text-blue-600 hover:underline">{p.canonicalName}</h3>
              <p className="text-xs text-gray-500">{p.brand} • {p.sizeText}</p>
              <p className="text-sm font-bold mt-1">{formatProductPrice(p.minEffectivePrice)}</p>
            </div>
            <button onClick={() => onAddProduct(p.productId)} className="bg-blue-50 text-blue-700 px-3 py-1 rounded text-sm font-medium hover:bg-blue-100 flex items-center gap-1">
              <Plus size={16} /> Add
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}