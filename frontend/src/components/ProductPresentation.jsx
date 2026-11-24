import { useState } from 'react';
import { Package } from 'lucide-react';

export function ProductImage({ product }) {
  const [failedUrl, setFailedUrl] = useState(null);
  return (
    <div className="h-40 w-full bg-gray-50 flex items-center justify-center overflow-hidden">
      {product.imageUrl && failedUrl !== product.imageUrl ? (
        <img
          src={product.imageUrl}
          alt={product.canonicalName}
          className="object-contain h-full w-full p-4"
          onError={() => setFailedUrl(product.imageUrl)}
          loading="lazy"
        />
      ) : (
        <div className="flex flex-col items-center gap-2 text-gray-500">
          <Package size={32} aria-hidden="true" />
          <span className="text-xs">Image unavailable</span>
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
      {imageSource && <a className="underline" href={imageSource}>Open Food Facts image (CC BY-SA)</a>}
    </div>
  );
}
