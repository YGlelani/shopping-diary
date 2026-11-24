import { useEffect, useState } from 'react';
import { apiRequest } from '../api/client';
import { Button } from './ui/Button';
import { Input } from './ui/Input';

export default function PriceContribution({ productId, lat, lon, radiusKm, token }) {
  const [open, setOpen] = useState(false);
  const [branches, setBranches] = useState(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ lat, lon, r: radiusKm });
    apiRequest('/stores/nearby?' + params, { signal: controller.signal }, token)
      .then(rows => { if (!controller.signal.aborted) setBranches(rows); })
      .catch(error => { if (!controller.signal.aborted) setMessage(error.message); });
    return () => controller.abort();
  }, [open, lat, lon, radiusKm, token]);

  async function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    setBusy(true);
    try {
      await apiRequest('/price-submissions', {
        method: 'POST',
        body: JSON.stringify({
          productId, storeId: Number(values.get('storeId')), price: values.get('price'),
          observedAt: new Date(values.get('observedAt')).toISOString(), inStock: values.has('inStock'), evidence: values.get('evidence'),
        }),
      }, token);
      form.reset();
      setMessage('Price submitted for review.');
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-2">
      <Button variant="secondary" aria-expanded={open} onClick={() => setOpen(value => !value)}>Contribute a branch price</Button>
      {open && branches?.length > 0 && (
        <form onSubmit={submit} className="grid gap-2 text-sm sm:grid-cols-2">
          <label>Branch<select name="storeId" required className="block w-full rounded border p-2">
            {branches.map(branch => <option key={branch.id} value={branch.id}>{branch.retailer} · {branch.name}</option>)}
          </select></label>
          <label>Shelf price (R)<Input name="price" required type="number" min="0.01" max="99999999.99" step="0.01" /></label>
          <label>When observed<Input name="observedAt" required type="datetime-local" /></label>
          <label>Evidence reference<Input name="evidence" maxLength={500} placeholder="Optional receipt or shelf-label reference" /></label>
          <label className="sm:col-span-2"><input name="inStock" type="checkbox" required /> I observed this exact product, package size and shelf price in stock at this branch.</label>
          <Button type="submit" disabled={busy}>Submit for review</Button>
        </form>
      )}
      {open && branches?.length === 0 && <p>No nearby branches are registered for contributions.</p>}
      {message && <p role="status">{message}</p>}
    </section>
  );
}
