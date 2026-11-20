import { lazy, Suspense, useState } from 'react';
import { Search, Plus, MapPin, Package } from 'lucide-react';
import { Card, CardContent } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { apiRequest } from '../api/client';
const ProductDetailsModal = lazy(() => import('../components/ProductDetailsModal'));

export default function Discover({ token, onAddItem, location }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [selectedProduct, setSelectedProduct] = useState(null);

  const handleSearch = async (e) => {
    e.preventDefault();
    if (!query.trim()) return;

    setLoading(true);
    setHasSearched(true);
    try {
      const res = await apiRequest(`/products/search?q=${encodeURIComponent(query)}&lat=${location.lat}&lon=${location.lon}&r=15`);
      setResults(res);
    } catch (err) {
      alert(err.message);
    } finally {
      setLoading(false);
    }
  };

  // Group results by category
  const groupedResults = results.reduce((acc, product) => {
    const category = product.category || 'Other / Uncategorized';
    if (!acc[category]) acc[category] = [];
    acc[category].push(product);
    return acc;
  }, {});

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Discover Products</h1>
        <p className="text-gray-500 mt-1">Search for groceries and compare live prices across nearby stores.</p>
      </div>

      <Card>
        <CardContent className="p-4">
          <form onSubmit={handleSearch} className="flex flex-col sm:flex-row gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" size={18} />
              <Input
                type="text"
                placeholder="Search for milk, bread..."
                className="pl-10"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
            </div>
            <Button type="submit" disabled={loading} className="w-full sm:w-auto">
              {loading ? 'Searching...' : 'Search'}
            </Button>
          </form>
          <div className="flex items-center gap-2 mt-3 text-xs text-gray-500">
            <MapPin size={14} />
            Searching near your selected location
          </div>
        </CardContent>
      </Card>

      {hasSearched && !loading && results.length === 0 && (
        <div className="text-center py-12">
          <p className="text-gray-500">No products found for "{query}". Try a different spelling.</p>
        </div>
      )}

      {/* Render Grouped Results */}
      <div className="space-y-8">
        {Object.entries(groupedResults).map(([category, products]) => (
          <div key={category}>
            <h2 className="text-lg font-bold text-gray-800 mb-4 pb-2 border-b border-gray-200">
              {category}
            </h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
              {products.map(product => (
                <Card key={product.productId} className="hover:shadow-md transition-shadow cursor-pointer flex flex-col">

                  {/* Product Image / Fallback */}
                  <div className="h-40 w-full bg-gray-50 border-b border-gray-100 flex items-center justify-center overflow-hidden">
                    {product.imageUrl ? (
                      <img
                        src={product.imageUrl}
                        alt={product.canonicalName}
                        className="object-contain h-full w-full p-4 hover:scale-105 transition-transform"
                        onError={(e) => { e.target.style.display = 'none'; e.target.nextSibling.style.display = 'flex'; }}
                      />
                    ) : null}
                    <div className={`flex flex-col items-center text-gray-400 ${product.imageUrl ? 'hidden' : 'flex'}`}>
                      <Package size={32} className="mb-2 opacity-50" />
                      <span className="text-xs font-medium">No Image</span>
                    </div>
                  </div>

                  <CardContent className="p-4 flex-1 flex flex-col justify-between">
                    <div>
                      <h3 className="font-semibold text-gray-900 leading-tight line-clamp-2">{product.canonicalName}</h3>
                      <p className="text-xs text-gray-500 mt-1 mb-3">{product.brand} • {product.sizeText}</p>

                      <div className="flex items-end justify-between">
                        <div>
                          <p className="text-xs text-gray-500 font-medium">Lowest Price</p>
                          <p className="text-xl font-bold text-emerald-700">R{product.minEffectivePrice}</p>
                        </div>
                        <div className="text-right">
                          <p className="text-xs text-gray-500">{product.storeCount} stores</p>
                        </div>
                      </div>
                    </div>

                    <div className="mt-4 pt-4 border-t border-gray-100 flex gap-2">
                      <Button
                        variant="secondary"
                        className="flex-1 text-xs"
                        onClick={() => setSelectedProduct(product)}
                      >
                        Compare
                      </Button>
                      <Button
                        className="flex-1 text-xs"
                        onClick={(e) => { e.stopPropagation(); onAddItem(product.productId); }}
                      >
                        <Plus size={14} className="mr-1" /> Add
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </div>
        ))}
      </div>

      {selectedProduct && (
        <Suspense fallback={<p>Loading product details...</p>}>
          <ProductDetailsModal
            product={selectedProduct}
            onClose={() => setSelectedProduct(null)}
            lat={location.lat}
            lon={location.lon}
            token={token}
            onAddItem={onAddItem}
          />
        </Suspense>
      )}
    </div>
  );
}