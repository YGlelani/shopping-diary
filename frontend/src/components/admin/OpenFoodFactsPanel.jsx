
import { useEffect, useState } from 'react';

import {
    CheckCircle2,
    Database,
    ExternalLink,
    ImageOff,
    Search,
    ShieldAlert,
} from 'lucide-react';

import { apiRequest } from '../../api/client';
import { Button } from '../ui/Button';
import {
    Card,
    CardContent,
    CardHeader,
} from '../ui/Card';
import { Input } from '../ui/Input';

const BASE = '/admin/catalogue/open-food-facts';

function statusText(status) {

    const labels = {
        READY_TO_IMPORT: 'Ready to import',
        READY_IMAGE_UPDATE: 'Image can be added',
        ALREADY_EXISTS: 'Already exists',
        NAME_REVIEW_REQUIRED: 'Matching unbarcoded product needs review',
        IMPORTED: 'Imported',
        IMAGE_UPDATED: 'Image added',
    };


    return labels[status] || status || 'Unknown';
}

export default function OpenFoodFactsPanel({ token }) {

    const [query, setQuery] = useState('');
    const [results, setResults] = useState([]);
    const [selected, setSelected] = useState([]);

    const [preview, setPreview] = useState(null);
    const [confirmed, setConfirmed] = useState(false);

    const [busy, setBusy] = useState('');
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [importEnabled, setImportEnabled] = useState(false);

    useEffect(() => {
        const controller = new AbortController();
        apiRequest(BASE + '/capabilities', { signal: controller.signal }, token)
            .then(data => {
                if (!controller.signal.aborted) setImportEnabled(data.importEnabled === true);
            })
            .catch(() => {
                if (!controller.signal.aborted) setImportEnabled(false);
            });
        return () => controller.abort();
    }, [token]);

    function resetReview() {
        setPreview(null);
        setConfirmed(false);
    }

    async function search(event) {
        event.preventDefault();

        if (busy) return;

        if (query.trim().length < 2) {
            setError('Enter at least two characters.');
            return;
        }

        setBusy('search');
        setError('');
        setNotice('');
        setResults([]);
        setSelected([]);
        resetReview();

        try {
            const params = new URLSearchParams({
                query: query.trim(),
                limit: '20',
            });

            const barcode = query.trim();
            const data = /^[0-9]{8,14}$/.test(barcode)
                ? [await apiRequest(BASE + '/preview/' + barcode, {}, token)]
                : await apiRequest(BASE + '/search?' + params, {}, token);

            if (!Array.isArray(data)) {
                throw new Error('Unexpected search response');
            }

            setResults(data);

            setNotice(
                data.length
                    ? `Found ${data.length} products. Select the ones you want to review.`
                    : 'No matching South African products found.'
            );

        } catch (err) {
            setError(err.message || 'Search failed.');

        } finally {
            setBusy('');
        }
    }

    function toggle(barcode) {
        setSelected(current =>
            current.includes(barcode)
                ? current.filter(code => code !== barcode)
                : [...current, barcode]
        );

        resetReview();
        setError('');
        setNotice('');
    }

    function selectAll() {
        setSelected(
            selected.length === results.length
                ? []
                : results.map(product => product.barcode)
        );

        resetReview();
        setError('');
        setNotice('');
    }

    async function previewSelection() {
        if (busy || selected.length === 0) return;

        setBusy('preview');
        setError('');
        setNotice('');
        setConfirmed(false);

        try {
            const data = await apiRequest(
                `${BASE}/bulk`,
                {
                    method: 'POST',
                    body: JSON.stringify({
                        barcodes: selected,
                        dryRun: true,
                        confirm: false,
                    }),
                },
                token
            );

            setPreview(data);

            setNotice(
                'Preview complete. No database changes were made.'
            );

        } catch (err) {
            setPreview(null);
            setError(err.message || 'Preview failed.');

        } finally {
            setBusy('');
        }
    }

    const readyItems = (preview?.items || [])
        .filter(item => item.status?.startsWith('READY_'));

    const allReady =
        preview &&
        readyItems.length === selected.length;

    async function importSelected() {
        if (
            busy ||
            !allReady ||
            !confirmed ||
            !importEnabled
        ) {
            return;
        }

        setBusy('import');
        setError('');
        setNotice('');

        try {
            const data = await apiRequest(
                `${BASE}/bulk`,
                {
                    method: 'POST',
                    body: JSON.stringify({
                        barcodes: selected,
                        dryRun: false,
                        confirm: true,
                    }),
                },
                token
            );

            setPreview(data);
            setConfirmed(false);
            setSelected([]);

            setNotice(
                `Imported ${data.imported ?? 0} products; ` +
                `added ${data.imagesUpdated ?? 0} images ` +
                `to existing products. ` +
                `No retailer prices were created.`
            );

        } catch (err) {
            setError(err.message || 'Import failed.');

        } finally {
            setBusy('');
        }
    }

    return (
        <Card>
            <CardHeader>
                <div className="flex items-center gap-2">
                    <Database
                        size={20}
                        className="text-emerald-700"
                    />

                    <div>
                        <h2 className="font-semibold text-gray-900">
                            Open Food Facts Catalogue
                        </h2>

                        <p className="text-sm text-gray-500">
                            Search, review and import up to 20
                            products at a time, including
                            available product images.
                        </p>
                    </div>
                </div>
            </CardHeader>

            <CardContent className="space-y-5">

                <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
                    <ShieldAlert
                        size={18}
                        className="mt-0.5 shrink-0"
                    />

                    <p>
                        Review product identity, package size and image before importing.
                        {importEnabled ? ' Catalogue writes are enabled on this backend.' : ' This backend is in preview-only mode.'}
                    </p>
                </div>

                <form
                    onSubmit={search}
                    className="flex flex-col gap-2 sm:flex-row"
                >
                    <label
                        htmlFor="off-query"
                        className="sr-only"
                    >
                        Search products
                    </label>

                    <Input
                        id="off-query"
                        value={query}
                        onChange={event =>
                            setQuery(event.target.value)
                        }
                        placeholder="e.g. bread, rice or a barcode"
                        minLength={2}
                        maxLength={60}
                    />

                    <Button
                        type="submit"
                        disabled={
                            Boolean(busy) ||
                            query.trim().length < 2
                        }
                    >
                        <Search size={16} className="mr-2" />

                        {busy === 'search'
                            ? 'Searching…'
                            : 'Search'}
                    </Button>
                </form>

                <p className="text-xs text-gray-500">
                    The provider may be temporarily unavailable
                    or rate-limited. Repeated searches use the
                    backend's short-lived cache.
                </p>

                {error && (
                    <p
                        role="alert"
                        className="rounded-lg bg-red-50 p-3 text-sm text-red-800"
                    >
                        {error}
                    </p>
                )}

                {notice && (
                    <p
                        role="status"
                        className="rounded-lg bg-gray-50 p-3 text-sm text-gray-700"
                    >
                        {notice}
                    </p>
                )}

                {results.length > 0 && (
                    <section className="space-y-3">

                        <div className="flex flex-wrap items-center justify-between gap-2">
                            <h3 className="font-semibold text-gray-900">
                                Candidates ({results.length})
                            </h3>

                            <Button
                                type="button"
                                variant="secondary"
                                disabled={Boolean(busy)}
                                onClick={selectAll}
                            >
                                {selected.length === results.length
                                    ? 'Clear selection'
                                    : 'Select all'}
                            </Button>
                        </div>

                        <div className="max-h-[480px] space-y-2 overflow-y-auto pr-1">

                            {results.map(product => (
                                <label
                                    key={product.barcode}
                                    className="flex cursor-pointer items-start gap-3 rounded-lg border border-gray-200 p-3 hover:bg-gray-50"
                                >
                                    <input
                                        type="checkbox"
                                        className="mt-3"
                                        checked={selected.includes(
                                            product.barcode
                                        )}
                                        onChange={() =>
                                            toggle(product.barcode)
                                        }
                                        disabled={Boolean(busy)}
                                    />

                                    <div className="flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-md bg-gray-100">

                                        {product.imageUrl ? (
                                            <img
                                                src={product.imageUrl}
                                                alt={product.name}
                                                loading="lazy"
                                                className="h-full w-full object-contain"
                                            />
                                        ) : (
                                            <ImageOff
                                                size={23}
                                                className="text-gray-400"
                                            />
                                        )}

                                    </div>

                                    <div className="min-w-0 flex-1">
                                        <p className="font-medium text-gray-900">
                                            {product.name}
                                        </p>

                                        <p className="text-xs text-gray-600">
                                            {[
                                                product.brand,
                                                product.department,
                                                product.sizeText,
                                            ]
                                                .filter(Boolean)
                                                .join(' · ') ||
                                                'Details incomplete'}
                                        </p>

                                        <p className="mt-1 font-mono text-xs text-gray-500">
                                            {product.barcode}
                                        </p>

                                        {product.sourceUrl && (
                                            <a
                                                href={product.sourceUrl}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                onClick={event =>
                                                    event.stopPropagation()
                                                }
                                                className="mt-1 inline-flex items-center gap-1 text-xs text-emerald-800 underline"
                                            >
                                                View source
                                                <ExternalLink size={12} />
                                            </a>
                                        )}
                                    </div>
                                </label>
                            ))}
                        </div>

                        <Button
                            type="button"
                            disabled={
                                Boolean(busy) ||
                                selected.length === 0
                            }
                            onClick={previewSelection}
                        >
                            Preview {selected.length} selected
                        </Button>
                    </section>
                )}

                {preview && (
                    <section className="space-y-3 rounded-xl border border-gray-200 p-4">

                        <h3 className="flex items-center gap-2 font-semibold text-gray-900">
                            <CheckCircle2
                                size={18}
                                className="text-emerald-700"
                            />

                            {preview.dryRun
                                ? 'Import preview — no changes yet'
                                : 'Import results'}
                        </h3>

                        <p className="text-sm text-gray-600">
                            {preview.requested} selected ·{' '}
                            {preview.ready} eligible

                            {!preview.dryRun &&
                                ` · ${preview.imported} imported · ${preview.imagesUpdated} images updated`}
                        </p>

                        <div className="space-y-2">
                            {(preview.items || []).map(item => (
                                <div
                                    key={item.barcode}
                                    className="flex items-start justify-between gap-3 rounded-md bg-gray-50 p-2 text-sm"
                                >
                                    <span className="text-gray-800">
                                        {item.name || item.barcode}
                                    </span>

                                    <span className="shrink-0 font-medium text-gray-600">
                                        {statusText(item.status)}
                                    </span>
                                </div>
                            ))}
                        </div>

                        {preview.dryRun && allReady && (
                            <div className="space-y-3 border-t border-gray-200 pt-3">

                                <label className="flex items-start gap-2 text-sm text-gray-700">
                                    <input
                                        type="checkbox"
                                        className="mt-1"
                                        checked={confirmed}
                                        onChange={event =>
                                            setConfirmed(event.target.checked)
                                        }
                                    />

                                    <span>
                                        I reviewed these products and want to import them into this catalogue.
                                    </span>
                                </label>

                                {!importEnabled && (
                                    <p className="text-sm text-amber-800">
                                        This backend allows previews only.
                                    </p>
                                )}

                                <Button
                                    type="button"
                                    onClick={importSelected}
                                    disabled={
                                        !confirmed ||
                                        !importEnabled ||
                                        Boolean(busy)
                                    }
                                >
                                    {busy === 'import'
                                        ? 'Importing…'
                                        : `Import ${selected.length} reviewed products`}
                                </Button>
                            </div>
                        )}

                        {preview.dryRun && !allReady && (
                            <p className="text-sm text-amber-800">
                                Remove products marked as already
                                existing or requiring review, then
                                preview the remaining selection again.
                            </p>
                        )}
                    </section>
                )}

                <p className="text-xs text-gray-500">
                    Source: Open Food Facts.
                    Product database: ODbL.
                    Product images: CC BY-SA where applicable.
                    <a className="ml-1 underline" href="https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/">
                        Open Food Facts reuse terms
                    </a>
                </p>

            </CardContent>
        </Card>
    );
}
