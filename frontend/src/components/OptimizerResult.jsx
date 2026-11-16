export default function OptimizerResult({ optimization }) {
  if (!optimization) return null;
  return (
    <div className="bg-white p-4 rounded-lg shadow-sm border-t-4 border-green-500">
      <h2 className="font-bold text-lg mb-2">Optimization Result</h2>
      <p className="text-sm text-gray-600 mb-4">Total Cost: <span className="text-xl font-bold text-gray-900">R{optimization.recommendation.grandTotal.toFixed(2)}</span></p>
      {optimization.savingsVsBestSingle > 0 && (
        <div className="bg-green-50 text-green-700 p-2 rounded text-sm mb-4">🎉 You save R{optimization.savingsVsBestSingle} by splitting your shop!</div>
      )}
      <h3 className="font-semibold text-sm mb-2 text-gray-700">Recommended Stores:</h3>
      <div className="space-y-2">
        {optimization.recommendation.stores.map(s => (
          <div key={s.storeId} className="text-sm border-l-2 border-gray-300 pl-3">
            <p className="font-medium">{s.storeName} ({s.distanceKm} km)</p>
            <p className="text-gray-500">Subtotal: R{s.storeSubtotal} + Transport: R{s.transportCost}</p>
          </div>
        ))}
      </div>
    </div>
  );
}