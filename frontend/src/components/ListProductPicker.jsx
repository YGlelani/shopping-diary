import { useRef, useState } from 'react';
import { apiRequest } from '../api/client';
import { Button } from './ui/Button';
import { Input } from './ui/Input';

export default function ListProductPicker({ token, label, onChoose }) {
  const [query, setQuery] = useState('');
  const [result, setResult] = useState(null);
  const sequence = useRef(0);

  async function search(event) {
    event.preventDefault();
    if (query.trim().length < 2) return;
    const id = ++sequence.current;
    setResult({ loading: true });
    try {
      const params = new URLSearchParams({ q: query.trim(), limit: '10' });
      const products = await apiRequest('/products/search?' + params, {}, token);
      if (id === sequence.current) setResult({ products });
    } catch (error) {
      if (id === sequence.current) setResult({ error: error.message });
    }
  }

  return <div className="space-y-2">
    <form className="flex flex-wrap items-end gap-2" onSubmit={search}>
      <label className="flex-1 text-sm">{label}
        <Input value={query} onChange={event => setQuery(event.target.value)} minLength={2} maxLength={120} required />
      </label>
      <Button type="submit" variant="secondary" disabled={result?.loading}>Search catalogue</Button>
    </form>
    {result?.loading && <p role="status" className="text-sm">Searching…</p>}
    {result?.error && <p role="alert" className="text-sm text-red-700">{result.error}</p>}
    {result?.products?.length === 0 && <p className="text-sm">No matching catalogue products.</p>}
    {result?.products?.length > 0 && <ul className="max-h-52 overflow-y-auto rounded border">
      {result.products.map(product => <li key={product.productId}>
        <button className="block w-full border-b p-2 text-left text-sm hover:bg-emerald-50" type="button"
          onClick={() => { onChoose(product); setResult(null); }}>
          {[product.canonicalName, product.brand, product.sizeText, product.barcode].filter(Boolean).join(' · ')}
        </button>
      </li>)}
    </ul>}
  </div>;
}
