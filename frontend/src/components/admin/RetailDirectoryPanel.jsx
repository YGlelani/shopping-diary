import { useEffect, useState } from 'react';
import { apiRequest } from '../../api/client';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';

const BASE = '/admin/directory';
const REVIEWS = '/admin/price-submissions';

export default function RetailDirectoryPanel({ token }) {
  const [directory, setDirectory] = useState([]);
  const [branches, setBranches] = useState([]);
  const [proposals, setProposals] = useState([]);
  const [reviews, setReviews] = useState([]);
  const [retailerId, setRetailerId] = useState('');
  const [branchId, setBranchId] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const retailer = directory.find(row => String(row.id) === retailerId);
  const branch = branches.find(row => String(row.id) === branchId);

  useEffect(() => {
    const controller = new AbortController();
    const options = { signal: controller.signal };
    function report(error) {
      if (!controller.signal.aborted) setMessage(error.message);
    }
    apiRequest(BASE, options, token).then(setDirectory).catch(report);
    apiRequest(REVIEWS, options, token).then(setReviews).catch(report);
    apiRequest(BASE + '/submissions', options, token).then(setProposals).catch(report);
    if (retailerId) apiRequest(BASE + '/' + retailerId + '/branches', options, token).then(setBranches).catch(report);
    return () => controller.abort();
  }, [retailerId, revision, token]);

  async function save(path, options) {
    setBusy(true);
    setMessage('');
    try {
      await apiRequest(path, options, token);
      setRevision(value => value + 1);
      setMessage('Saved.');
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  function saveRetailer(event) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    save(BASE + '/retailers/' + encodeURIComponent(data.get('slug')), {
      method: 'PUT',
      body: JSON.stringify({ name: data.get('name'), website: data.get('website') || null,
        logoUrl: data.get('logoUrl') || null, active: data.has('active') }),
    });
  }

  function saveBranch(event) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const target = BASE + '/' + retailerId + '/branches/' + encodeURIComponent(data.get('code'));
    save(branchId ? target + '?' + new URLSearchParams({ branchId }) : target, {
      method: 'PUT',
      body: JSON.stringify({ name: data.get('name'), address: data.get('address'), city: data.get('city'),
        province: data.get('province'), phone: data.get('phone'), hoursText: data.get('hoursText'),
        sourceUrl: data.get('sourceUrl'), postalCode: data.get('postalCode'), lat: data.get('lat'), lon: data.get('lon'), active: data.has('active') }),
    });
  }

  return (
    <section className="space-y-4 rounded-lg border bg-white p-4">
      <h2 className="text-lg font-semibold">Retailer directory and coverage</h2>
      <p className="text-sm text-gray-600">{directory.filter(row => row.active).length} active retailer brands. {directory.filter(row => row.pricedBranches > 0).length} with usable branch prices.</p>
      {message && <p role="status">{message}</p>}
      <label className="block text-sm">Retailer<select className="ml-2 rounded border p-1" value={retailerId} onChange={event => {
        setRetailerId(event.target.value);
        setBranchId('');
        setBranches([]);
      }}>
        <option value="">Add a retailer</option>
        {directory.map(row => <option key={row.id} value={row.id}>{row.name} ({row.pricedBranches}/{row.branches} priced branches)</option>)}
      </select></label>
      {retailer?.lastCollected && <p className="text-sm text-gray-600">Latest recorded collection {new Date(retailer.lastCollected).toLocaleString('en-ZA')}</p>}
      {retailer && <p className="text-sm text-gray-600">Latest recorded observation {retailer.lastObserved ? new Date(retailer.lastObserved).toLocaleString('en-ZA') : 'unavailable'}</p>}
      <form key={retailer?.id ?? 'new'} onSubmit={saveRetailer} className="grid gap-2 text-sm sm:grid-cols-2">
        <label>Retailer name<Input name="name" defaultValue={retailer?.name} required maxLength={100} /></label>
        <label>Retailer slug<Input name="slug" defaultValue={retailer?.slug} readOnly={Boolean(retailer)} required pattern="[a-z0-9][a-z0-9-]{0,49}" /></label>
        <label>Website<Input name="website" type="url" defaultValue={retailer?.website} /></label>
        <label>Authorised logo URL<Input name="logoUrl" type="url" defaultValue={retailer?.logoUrl} /></label>
        <label><input name="active" type="checkbox" defaultChecked={retailer?.active ?? true} /> Active retailer</label>
        <Button type="submit" disabled={busy}>Save retailer</Button>
      </form>
      {retailer && (
        <div className="space-y-2 border-t pt-3">
          <label className="text-sm">Physical branch<select className="ml-2 rounded border p-1" value={branchId} onChange={event => setBranchId(event.target.value)}>
            <option value="">Add a branch</option>
            {branches.filter(row => row.code !== 'ONLINE').map(row => (
              <option key={row.id} value={row.id}>{row.name} ({row.usable ? 'current prices' : row.lastObserved ? 'no eligible current prices' : 'no observations'})</option>
            ))}
          </select></label>
          <form key={branch?.id ?? retailerId} onSubmit={saveBranch} className="grid gap-2 text-sm sm:grid-cols-2">
            {[
              ['code', 'Branch code'], ['name', 'Branch name'], ['address', 'Street address'],
              ['city', 'Town or city'], ['province', 'Province'], ['lat', 'Latitude'], ['lon', 'Longitude'],
            ].map(([name, label]) => <label key={name}>{label}<Input name={name} required defaultValue={branch?.[name]}
                readOnly={name === 'code' && Boolean(branch?.code)} type={['lat', 'lon'].includes(name) ? 'number' : 'text'} step="any" /></label>)}
            <label>Verified phone<Input name="phone" type="tel" maxLength={50} defaultValue={branch?.phone} /></label>
            <label>Published opening hours<Input name="hoursText" maxLength={500} defaultValue={branch?.hoursText} /></label>
            <label>Official branch source URL<Input name="sourceUrl" type="url" maxLength={500} defaultValue={branch?.sourceUrl} /></label>
            <label>Postal code<Input name="postalCode" maxLength={10} defaultValue={branch?.postalCode} /></label>
            <label><input name="active" type="checkbox" defaultChecked={branch?.active ?? true} /> Active branch</label>
            <Button type="submit" disabled={busy}>Save branch</Button>
          </form>
          <form className="grid gap-2 border-t pt-3 text-sm sm:grid-cols-2" onSubmit={event => {
            event.preventDefault();
            const data = new FormData(event.currentTarget);
            save(BASE + '/' + retailerId + '/aliases', {
              method: 'PUT', body: JSON.stringify({ rawName: data.get('rawName'), barcode: data.get('barcode') }),
            });
          }}>
            <label>Exact retailer feed name<Input name="rawName" required maxLength={500} /></label>
            <label>Verified catalogue barcode<Input name="barcode" required pattern="[0-9]{8,20}" /></label>
            <label><input type="checkbox" required /> I matched the brand, variation and package size.</label>
            <Button type="submit" disabled={busy}>Save verified alias</Button>
          </form>
        </div>
      )}
      <div className="space-y-2 border-t pt-3">
        <h3 className="font-semibold">Pending branch proposals</h3>
        {proposals.length === 0 && <p className="text-sm text-gray-600">No pending branches.</p>}
        {proposals.map(row => <div key={row.id} className="space-y-2 rounded border p-3 text-sm">
          <p><strong>{row.name}</strong> ? {row.retailer}</p>
          <p>{[row.address, row.city, row.province].join(', ')} ? {row.lat}, {row.lon}</p>
          <a href={row.sourceUrl} target="_blank" rel="noreferrer" className="underline">Review location evidence</a>
          <div className="flex flex-wrap gap-2">
            <Button disabled={busy} onClick={() => save(BASE + '/submissions/' + row.id + '?approve=true', { method: 'POST' })}>Verify and register</Button>
            <Button variant="secondary" disabled={busy} onClick={() => save(BASE + '/submissions/' + row.id + '?approve=false', { method: 'POST' })}>Reject</Button>
          </div>
        </div>)}
      </div>
      <div className="space-y-2 border-t pt-3">
        <h3 className="font-semibold">Pending branch prices</h3>
        {reviews.length === 0 && <p className="text-sm text-gray-600">No pending contributions.</p>}
        {reviews.map(row => (
          <div key={row.id} className="space-y-2 rounded border p-3 text-sm">
            <p><strong>{row.product}</strong> {[row.brand, row.sizeText, row.barcode].filter(Boolean).join(' · ')}</p>
            <p>{row.retailer} · {row.branch} · R{row.shelfPrice} · {new Date(row.observedAt).toLocaleString('en-ZA')}</p>
            {row.evidence && <p>Evidence reference {row.evidence}</p>}
            <div className="flex flex-wrap gap-2">
              <Button disabled={busy} onClick={() => save(REVIEWS + '/' + row.id + '?approve=true', { method: 'POST' })}>Verify and publish</Button>
              <Button variant="secondary" disabled={busy} onClick={() => save(REVIEWS + '/' + row.id + '?approve=false', { method: 'POST' })}>Reject</Button>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
