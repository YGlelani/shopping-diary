export function Coverage({ store }) {
  const { recordedProducts, eligibleProducts, verifiedProducts, recentProducts, latestObserved } = store;
  let label = 'No price observations';
  if (eligibleProducts > 0) {
    label = verifiedProducts === 0 ? 'Current prices are unverified'
      : eligibleProducts < recordedProducts ? 'Partial current price coverage' : 'Current price observations';
  } else if (recordedProducts > 0) {
    label = recentProducts > 0 ? 'Recent observations are ineligible' : 'Historical prices only';
  }
  return <div className="text-sm">
    <p className="font-medium text-gray-800">{label}</p>
    <p className="text-gray-600">{eligibleProducts} eligible · {recordedProducts} recorded · {verifiedProducts} verified eligible</p>
    {latestObserved && <p className="text-gray-600">Last recorded {new Date(latestObserved).toLocaleString('en-ZA', { timeZone: 'Africa/Johannesburg' })}</p>}
  </div>;
}

export function BranchIdentity({ store }) {
  return <div className="flex items-start gap-3">
    {store.logoUrl && <img src={store.logoUrl} alt="" className="h-8 w-12 shrink-0 object-contain" />}
    <div><p className="text-sm text-gray-600">{store.retailer}</p>
      <h2 className="font-semibold">{store.name}</h2>
      <p className="text-sm text-gray-600">{[store.address, store.city, store.province, store.postalCode].filter(Boolean).join(', ')}</p></div>
  </div>;
}

export function Directions({ store }) {
  const query = new URLSearchParams({ api: '1', destination: [store.lat, store.lon].join(',') });
  return <a className="text-sm text-emerald-800 underline" target="_blank" rel="noreferrer"
    href={'https://www.google.com/maps/dir/?' + query}>Open directions</a>;
}
