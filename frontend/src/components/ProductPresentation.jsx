import { useState } from 'react';
import { Package } from 'lucide-react';
import { formatProductPrice } from '../utils/productFormatting';

export function CataloguePrice({ product }) {
  if (product.catalogueAmount == null) return null;
  return (
    <p className="text-sm text-gray-700">
      <strong>{formatProductPrice(product.catalogueAmount)}</strong> at {product.catalogueRetailer} (indicative catalogue price)
      {' '}<a className="underline" href="https://loyaltyhub.co.za/">Prices via Loyalty Hub</a>
    </p>
  );
}



export function ProductImage({ product, compact = false }) {
  const [failedUrl, setFailedUrl] = useState(null);
  return (
    <div className={(compact ? 'h-14 w-14 shrink-0' : 'h-40 w-full') + ' bg-gray-50 flex items-center justify-center overflow-hidden'}>
      {product.imageUrl && failedUrl !== product.imageUrl ? (
        <img
          src={product.imageUrl}
          alt={product.canonicalName}
          className={'object-contain h-full w-full ' + (compact ? 'p-1' : 'p-4')}
          onError={() => setFailedUrl(product.imageUrl)}
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
        />
      ) : (
        <div className="flex flex-col items-center gap-2 text-gray-500">
          <Package size={compact ? 20 : 32} aria-hidden="true" />
          <span className={compact ? 'sr-only' : 'text-xs'}>Image unavailable</span>
        </div>
      )}
    </div>
  );
}

export function ProductCredits({ product }) {
  const source = product.sourceUrl;
  const imageSource = product.imageSourceUrl;
  if (!source && !imageSource) return null;
  return (
    <div className="flex flex-wrap gap-x-2 text-xs text-gray-600">
      {source && <a className="underline" href={source}>Open Food Facts data (ODbL)</a>}
      {imageSource && <a className="underline" href={imageSource}>{imageSource === 'https://loyaltyhub.co.za/' ? 'Retailer image via Loyalty Hub' : 'Open Food Facts image (CC BY-SA)'}</a>}
    </div>
  );
}
