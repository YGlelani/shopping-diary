export function savedItem(item) {
  return {
    productId: item.productId ?? null, customName: item.customName ?? '',
    unit: item.unit ?? (item.productId ? 'pack' : 'item'), quantity: String(item.quantity),
    preferredBrand: item.preferredBrand ?? '', notes: item.notes ?? '',
    purchased: Boolean(item.purchased), flexibleBrand: Boolean(item.flexibleBrand),
    substituteProductId: item.substituteProductId ?? null,
  };
}
