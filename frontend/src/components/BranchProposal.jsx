import { useState } from 'react';
import { apiRequest } from '../api/client';
import { Button } from './ui/Button';
import { Input } from './ui/Input';

export default function BranchProposal({ retailers, token }) {
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = Object.fromEntries(new FormData(form));
    setBusy(true);
    try {
      await apiRequest('/stores/submissions', {
        method: 'POST', body: JSON.stringify(fields),
      }, token);
      form.reset();
      setMessage('Branch proposed for verification.');
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  return <details className="rounded-xl border bg-white p-4">
    <summary className="cursor-pointer font-medium">Suggest a missing branch</summary>
    <form className="mt-3 grid gap-3 text-sm sm:grid-cols-2" onSubmit={submit}>
      <label>Retailer<select name="retailerId" required className="block w-full rounded border p-2">
        <option value="">Choose a retailer</option>
        {retailers.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
      </select></label>
      <label>Branch name<Input name="name" required maxLength={255} /></label>
      <label>Physical address<Input name="address" required maxLength={500} /></label>
      <label>Town or city<Input name="city" required maxLength={100} /></label>
      <label>Province<Input name="province" required maxLength={100} /></label>
      <label>Branch latitude<Input name="lat" type="number" min="-90" max="90" step="any" required /></label>
      <label>Branch longitude<Input name="lon" type="number" min="-180" max="180" step="any" required /></label>
      <label>Retailer locator or branch source<Input name="sourceUrl" type="url" required maxLength={500} /></label>
      <Button type="submit" disabled={busy}>Submit branch for review</Button>
    </form>
    {message && <p role="status" className="mt-2 text-sm">{message}</p>}
  </details>;
}
