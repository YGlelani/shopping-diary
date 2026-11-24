import { useEffect, useRef, useState } from 'react';
import { Button } from './ui/Button';
import { Input } from './ui/Input';
import { apiRequest } from '../api/client';

export default function AddToListDialog({ productId, lists, listId, token, onCreate, onSaved, onClose }) {
  const dialog = useRef(null);
  const request = useRef(null);
  const submitting = useRef(false);
  const editable = lists.filter(list => list.status !== 'ARCHIVED');
  const [target, setTarget] = useState(editable.some(list => list.id === listId) ? String(listId) : String(editable[0]?.id ?? ''));
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const element = dialog.current;
    element.showModal();
    return () => element.close();
  }, []);

  async function save(event) {
    event.preventDefault();
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setError('');
    try {
      const id = target ? Number(target) : (await onCreate({ name: name.trim() })).id;
      setTarget(String(id));
      if (request.current?.id !== id) request.current = { id, key: crypto.randomUUID() };
      await apiRequest('/lists/' + id + '/items', {
        method: 'POST', headers: { 'Idempotency-Key': request.current.key },
        body: JSON.stringify({ productId, quantity: 1 }),
      }, token);
      await onSaved(id);
      onClose(true);
    } catch (failure) {
      setError(failure.message);
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }

  return (
    <dialog ref={dialog} onCancel={event => { event.preventDefault(); if (!busy) onClose(false); }}
      aria-labelledby="list-destination-title" className="m-auto w-[min(92vw,28rem)] space-y-3 rounded-xl bg-white p-5 shadow-xl backdrop:bg-black/40">
      <h2 id="list-destination-title" className="text-lg font-semibold">Add product to a list</h2>
      <form className="space-y-3" onSubmit={save}>
        <label className="block text-sm">Shopping list
          <select className="block w-full rounded border p-2" value={target} onChange={event => setTarget(event.target.value)} disabled={busy}>
            {editable.map(list => <option value={list.id} key={list.id}>{list.name}</option>)}
            <option value="">Create a list</option>
          </select>
        </label>
        {!target && <label className="block text-sm">New list name<Input value={name} onChange={event => setName(event.target.value)} required maxLength={200} /></label>}
        {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" disabled={busy} onClick={() => onClose(false)}>Cancel</Button>
          <Button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Add product'}</Button>
        </div>
      </form>
    </dialog>
  );
}
