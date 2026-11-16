export default function ShoppingList({ cart, onRemove, onOptimize, isOptimizing }) {
  return (
    <div className="bg-white p-4 rounded-lg shadow-sm">
      <h2 className="font-semibold mb-3">Your List ({cart.length} items)</h2>
      {cart.length === 0 ? <p className="text-sm text-gray-500 italic">List is empty.</p> : (
        <ul className="divide-y divide-gray-100 mb-4">
          {cart.map((item) => (
            <li key={item.id} className="py-2 flex justify-between items-center text-sm">
              <span>{item.productName}</span>
              <div className="flex gap-3 items-center">
                <span className="text-gray-500">x{item.quantity}</span>
                <button onClick={() => onRemove(item.id)} className="text-red-500 hover:text-red-700 text-xs font-bold">✕</button>
              </div>
            </li>
          ))}
        </ul>
      )}
      <button onClick={onOptimize} disabled={cart.length === 0 || isOptimizing} className="w-full bg-green-600 text-white p-2 rounded hover:bg-green-700 disabled:opacity-50 transition font-medium">
        {isOptimizing ? 'Calculating...' : 'Find Cheapest Plan'}
      </button>
    </div>
  );
}