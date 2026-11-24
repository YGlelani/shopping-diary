import { savedItem } from '../utils/listEditing';
import { useState } from 'react';
import ListProductPicker from './ListProductPicker';
import { Button } from './ui/Button';
import { Input } from './ui/Input';

const units = {
  item: 'Items', pack: 'Packs', bottle: 'Bottles', carton: 'Cartons', bag: 'Bags',
  kg: 'Kilograms', g: 'Grams', l: 'Litres', ml: 'Millilitres', dozen: 'Dozens',
};
const packageUnits = ['item', 'pack', 'bottle', 'carton', 'bag'];

export default function ListItemEditor({ initial, token, onSave, busy, onCancel, allowLink = true }) {
  const [draft, setDraft] = useState(initial ? savedItem(initial) : {
    productId: null, customName: '', unit: 'item', quantity: '1', preferredBrand: '', notes: '',
    purchased: false, flexibleBrand: false, substituteProductId: null,
  });
  const [selected, setSelected] = useState(null);
  const [alternate, setAlternate] = useState(null);
  const [linking, setLinking] = useState(false);
  const [choosingAlternate, setChoosingAlternate] = useState(false);
  const eligibleUnits = draft.productId ? packageUnits : Object.keys(units);
  const unitOptions = [...new Set([...eligibleUnits, draft.unit])];

  function change(event) {
    const { name, type, value, checked } = event.target;
    setDraft(current => ({
      ...current, [name]: type === 'checkbox' ? checked : value,
      ...(name === 'flexibleBrand' && !checked ? { substituteProductId: null } : {}),
    }));
  }

  return <div className="space-y-2 rounded-lg border bg-gray-50 p-3">
    <form className="grid gap-2 text-sm sm:grid-cols-2" onSubmit={event => { event.preventDefault(); onSave(draft); }}>
      {draft.productId ? <p className="sm:col-span-2">
        <strong>{selected?.canonicalName ?? initial?.productName}</strong>{selected?.sizeText && ' · ' + selected.sizeText}
      </p> : <label className="sm:col-span-2">Custom item name<Input name="customName" value={draft.customName} onChange={change} required maxLength={500} /></label>}
      <label>Purchase quantity<Input name="quantity" type="number" value={draft.quantity} onChange={change}
        required min={['kg', 'g', 'l', 'ml'].includes(draft.unit) ? '0.001' : '1'} max="9999999.999"
        step={['kg', 'g', 'l', 'ml'].includes(draft.unit) ? '0.001' : '1'} /></label>
      <label>Purchase unit<select className="block w-full rounded border p-2" name="unit" value={draft.unit} onChange={change}>
        {unitOptions.map(unit => <option key={unit} value={unit}>{units[unit] ?? unit}</option>)}
      </select></label>
      <label>Preferred brand<Input name="preferredBrand" value={draft.preferredBrand} onChange={change} maxLength={100} /></label>
      <label>Item notes<Input name="notes" value={draft.notes} onChange={change} maxLength={1000} /></label>
      <label className="sm:col-span-2"><input type="checkbox" name="flexibleBrand" checked={draft.flexibleBrand} onChange={change} />
        {' '}Allow an explicitly approved substitute</label>
      {draft.flexibleBrand && draft.productId && <div className="space-y-1 sm:col-span-2">
        {draft.substituteProductId && <p>Approved substitute {alternate?.canonicalName ?? 'catalogue item #' + draft.substituteProductId}</p>}
        <Button type="button" variant="secondary" onClick={() => setChoosingAlternate(value => !value)}>Choose substitute</Button>
        {draft.substituteProductId && <Button type="button" variant="ghost" onClick={() => setDraft(value => ({ ...value, substituteProductId: null }))}>Remove substitute</Button>}
      </div>}
      {initial && <label><input type="checkbox" name="purchased" checked={draft.purchased} onChange={change} /> Purchased</label>}
      {!draft.productId && allowLink && <Button type="button" variant="secondary" onClick={() => setLinking(value => !value)}>Link a catalogue product</Button>}
      <div className="flex gap-2 sm:col-span-2">
        <Button type="submit" disabled={busy}>{busy ? 'Saving…' : initial ? 'Save item' : 'Add custom item'}</Button>
        {onCancel && <Button type="button" variant="secondary" onClick={onCancel}>Cancel edit</Button>}
      </div>
    </form>
    {linking && <ListProductPicker token={token} label="Find the product to link" onChoose={product => {
      setSelected(product);
      setDraft(value => ({ ...value, productId: product.productId, unit: 'pack' }));
      setLinking(false);
    }} />}
    {choosingAlternate && draft.flexibleBrand && <ListProductPicker token={token}
      label="Find a same-size alternative that meets your requirements" onChoose={product => {
        setAlternate(product);
        setDraft(value => ({ ...value, substituteProductId: product.productId }));
        setChoosingAlternate(false);
      }} />}
  </div>;
}
