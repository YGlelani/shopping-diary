export function formatProductPrice(value) {
  if (value == null || value === '') return 'Price unavailable';
  const amount = Number(value);
  return Number.isFinite(amount) && amount >= 0 ? 'R' + amount.toFixed(2) : 'Price unavailable';
}

export function formatObservationDate(value) {
  return new Date(value).toLocaleDateString('en-ZA', {
    day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Africa/Johannesburg',
  });
}
