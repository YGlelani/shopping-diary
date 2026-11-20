import { useState } from 'react';
import { Trash2, ShoppingBag, ArrowRight } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Card, CardContent, CardHeader } from '../components/ui/Card';
import { Button } from '../components/ui/Button';

export default function ShoppingLists({ cart, lists, listId, onSelect, onCreate, onRemoveItem }) {
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);

  async function handleCreate(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    const budget = values.get('budget');
    setCreating(true);
    setError('');
    try {
      await onCreate(values.get('name'), budget === '' ? null : Number(budget));
      form.reset();
    } catch (err) {
      setError(err.message);
    } finally {
      setCreating(false);
    }
  }

  const navigate = useNavigate();
  const totalItems = cart.reduce((sum, item) => sum + item.quantity, 0);

  return (
    <div className="space-y-6 max-w-3xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">My Shopping List</h1>
        <p className="text-gray-500 mt-1">Review your items before finding the cheapest plan.</p>
      </div>

      {lists.length > 0 && (
        <label className="block">
          Active list
          <select className="ml-3 rounded border p-2" value={listId ?? ''} onChange={event => onSelect(Number(event.target.value))}>
            {lists.map(list => <option key={list.id} value={list.id}>{list.name}</option>)}
          </select>
        </label>
      )}
      <form onSubmit={handleCreate} className="flex flex-wrap gap-3">
        <input className="rounded border p-2" name="name" aria-label="List name" placeholder="List name" required />
        <input className="rounded border p-2" name="budget" aria-label="Budget in rand" placeholder="Budget in rand" type="number" min="0" step="0.01" />
        <Button type="submit" disabled={creating}>Create list</Button>
      </form>
      {error && <p role="alert" className="text-red-700">{error}</p>}

      <Card>
        <CardHeader className="flex justify-between items-center bg-gray-50/50">
          <div className="flex items-center gap-2">
            <ShoppingBag className="text-gray-500" size={20} />
            <h2 className="text-lg font-semibold text-gray-900">Current List</h2>
          </div>
          <span className="bg-emerald-100 text-emerald-800 text-xs font-bold px-2.5 py-1 rounded-full">
            {totalItems} items
          </span>
        </CardHeader>
        
        <CardContent className="p-0">
          {cart.length === 0 ? (
            <div className="text-center py-12 px-4">
              <p className="text-gray-500 mb-4">Your list is currently empty.</p>
              <Button onClick={() => navigate('/discover')}>Discover Products</Button>
            </div>
          ) : (
            <>
              <ul className="divide-y divide-gray-100">
                {cart.map((item) => (
                  <li key={item.id} className="p-4 sm:px-6 flex justify-between items-center hover:bg-gray-50 transition-colors">
                    <div>
                      <p className="font-medium text-gray-900">{item.productName}</p>
                      <p className="text-sm text-gray-500">Quantity: {item.quantity}</p>
                    </div>
                    <button 
                      onClick={() => onRemoveItem(item.id)}
                      className="p-2 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                      title="Remove item"
                    >
                      <Trash2 size={18} />
                    </button>
                  </li>
                ))}
              </ul>
              <div className="p-6 bg-gray-50 border-t border-gray-200 flex justify-end">
                <Button onClick={() => navigate('/optimize')} className="w-full sm:w-auto">
                  Optimize This List <ArrowRight size={16} className="ml-2" />
                </Button>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}