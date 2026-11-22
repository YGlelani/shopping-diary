
import {
  lazy,
  Suspense,
  useRef,
  useState,
} from 'react';

import {
  Search,
  Plus,
  MapPin,
  Package,
} from 'lucide-react';

import {
  Card,
  CardContent,
} from '../components/ui/Card';

import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { apiRequest } from '../api/client';

const ProductDetailsModal = lazy(
  () => import('../components/ProductDetailsModal')
);

// Reset search state by remounting only when the
// selected shopping coordinates actually change.
// This avoids calling setState inside useEffect.
export default function Discover({
  token,
  onAddItem,
  location,
}) {
  const locationKey =
    `${location.lat}:${location.lon}`;

  return (
    <DiscoverContent
      key={locationKey}
      token={token}
      onAddItem={onAddItem}
      location={location}
    />
  );
}

function ProductImage({ product }) {
  const [failed, setFailed] = useState(false);

  const showImage =
    Boolean(product.imageUrl) && !failed;

  return (
    <div className="h-40 w-full bg-gray-50 border-b border-gray-100 flex items-center justify-center overflow-hidden">
      {showImage ? (
        <img
          src={product.imageUrl}
          alt={product.canonicalName}
          className="object-contain h-full w-full p-4 hover:scale-105 transition-transform"
          onError={() => setFailed(true)}
          loading="lazy"
        />
      ) : (
        <div className="flex flex-col items-center text-gray-400">
          <Package
            size={32}
            className="mb-2 opacity-50"
          />
          <span className="text-xs font-medium">
            No Image
          </span>
        </div>
      )}
    </div>
  );
}

function formatPrice(value) {
  if (value == null || value === '') {
    return 'Price unavailable';
  }

  const number = Number(value);

  if (!Number.isFinite(number)) {
    return 'Price unavailable';
  }

  return `R${number.toFixed(2)}`;
}

function DiscoverContent({
  token,
  onAddItem,
  location,
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [error, setError] = useState('');
  const [lastSearch, setLastSearch] = useState('');
  const [selectedProduct, setSelectedProduct] =
    useState(null);

  // Prevent an earlier request from replacing
  // results returned by a newer request.
  const searchRequestId = useRef(0);

  async function runSearch(searchTerm) {
    const search = searchTerm.trim();

    if (search.length < 2) {
      setError(
        'Enter at least two characters to search.'
      );
      setResults([]);
      setHasSearched(false);
      return;
    }

    const requestId = ++searchRequestId.current;

    setLoading(true);
    setError('');
    setResults([]);
    setHasSearched(false);
    setLastSearch(search);

    try {
      const params = new URLSearchParams({
        q: search,
        lat: String(location.lat),
        lon: String(location.lon),
        r: '15',
      });

      const response = await apiRequest(
        `/products/search?${params.toString()}`,
        {},
        token
      );

      if (requestId !== searchRequestId.current) {
        return;
      }

      if (!Array.isArray(response)) {
        throw new Error(
          'Unexpected response from the product search service.'
        );
      }

      setResults(response);
      setHasSearched(true);
    } catch (err) {
      if (requestId !== searchRequestId.current) {
        return;
      }

      setResults([]);
      setHasSearched(false);
      setError(
        err.message ||
          'Unable to search products right now.'
      );
    } finally {
      if (requestId === searchRequestId.current) {
        setLoading(false);
      }
    }
  }

  function handleSearch(event) {
    event.preventDefault();
    runSearch(query);
  }

  function handleRetry() {
    runSearch(lastSearch);
  }

  const groupedResults = results.reduce(
    (groups, product) => {
      const category =
        product.category ||
        'Other / Uncategorized';

      if (!groups[category]) {
        groups[category] = [];
      }

      groups[category].push(product);

      return groups;
    },
    {}
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">
          Discover Products
        </h1>

        <p className="text-gray-500 mt-1">
          Search for groceries and compare
          recently observed prices at nearby stores.
        </p>
      </div>

      <Card>
        <CardContent className="p-4">
          <form
            onSubmit={handleSearch}
            className="flex flex-col sm:flex-row gap-3"
          >
            <div className="relative flex-1">
              <Search
                className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"
                size={18}
              />

              <Input
                type="text"
                placeholder="Search for milk, bread..."
                className="pl-10"
                value={query}
                onChange={event =>
                  setQuery(event.target.value)
                }
              />
            </div>

            <Button
              type="submit"
              disabled={loading}
              className="w-full sm:w-auto"
            >
              {loading
                ? 'Searching...'
                : 'Search'}
            </Button>
          </form>

          <div className="flex items-center gap-2 mt-3 text-xs text-gray-500">
            <MapPin size={14} />
            Searching near your selected
            shopping location
          </div>
        </CardContent>
      </Card>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-200 bg-red-50 p-4"
        >
          <p className="font-medium text-red-800">
            Search unavailable
          </p>

          <p className="text-sm text-red-700 mt-1">
            {error}
          </p>

          {lastSearch && (
            <Button
              type="button"
              variant="secondary"
              className="mt-3"
              onClick={handleRetry}
              disabled={loading}
            >
              Try Again
            </Button>
          )}
        </div>
      )}

      {loading && (
        <p
          role="status"
          className="text-gray-500 text-center py-8"
        >
          Searching nearby products...
        </p>
      )}

      {hasSearched &&
        !loading &&
        !error &&
        results.length === 0 && (
          <div className="text-center py-12">
            <p className="font-medium text-gray-800">
              No matching products found
            </p>

            <p className="text-gray-500 mt-2">
              No eligible nearby prices were
              found for "{lastSearch}".
              Try a different search term
              or shopping location.
            </p>
          </div>
        )}

      <div className="space-y-8">
        {Object.entries(groupedResults).map(
          ([category, products]) => (
            <div key={category}>
              <h2 className="text-lg font-bold text-gray-800 mb-4 pb-2 border-b border-gray-200">
                {category}
              </h2>

              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
                {products.map(product => (
                  <Card
                    key={product.productId}
                    className="hover:shadow-md transition-shadow flex flex-col"
                  >
                    <ProductImage product={product} />

                    <CardContent className="p-4 flex-1 flex flex-col justify-between">
                      <div>
                        <h3 className="font-semibold text-gray-900 leading-tight line-clamp-2">
                          {product.canonicalName}
                        </h3>

                        <p className="text-xs text-gray-500 mt-1 mb-3">
                          {[
                            product.brand,
                            product.sizeText,
                          ]
                            .filter(Boolean)
                            .join(' • ')}
                        </p>

                        <div className="flex items-end justify-between">
                          <div>
                            <p className="text-xs text-gray-500 font-medium">
                              Lowest eligible price
                            </p>

                            <p className="text-xl font-bold text-emerald-700">
                              {formatPrice(
                                product.minEffectivePrice
                              )}
                            </p>
                          </div>

                          <p className="text-xs text-gray-500">
                            {product.storeCount} stores
                          </p>
                        </div>
                      </div>

                      <div className="mt-4 pt-4 border-t border-gray-100 flex gap-2">
                        <Button
                          type="button"
                          variant="secondary"
                          className="flex-1 text-xs"
                          onClick={() =>
                            setSelectedProduct(product)
                          }
                        >
                          Compare
                        </Button>

                        <Button
                          type="button"
                          className="flex-1 text-xs"
                          onClick={() =>
                            onAddItem(product.productId)
                          }
                        >
                          <Plus
                            size={14}
                            className="mr-1"
                          />
                          Add
                        </Button>
                      </div>
                    </CardContent>
                  </Card>
                ))}
              </div>
            </div>
          )
        )}
      </div>

      {selectedProduct && (
        <Suspense
          fallback={
            <p>Loading product details...</p>
          }
        >
          <ProductDetailsModal
            product={selectedProduct}
            onClose={() =>
              setSelectedProduct(null)
            }
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
