export function formatProductPrice(value) {
  const amount = Number(value);
  return Number.isFinite(amount) && amount > 0 ? 'R' + amount.toFixed(2) : 'Price unavailable';
}

export function formatObservationDate(value) {
  return new Date(value).toLocaleDateString('en-ZA', {
    day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Africa/Johannesburg',
  });
}
