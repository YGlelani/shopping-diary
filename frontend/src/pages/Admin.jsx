
import { useState } from 'react';

import {
  UploadCloud,
  RefreshCw,
  Database,
  Tag,
} from 'lucide-react';

import {
  Card,
  CardContent,
  CardHeader,
} from '../components/ui/Card';

import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { apiRequest } from '../api/client';

import OpenFoodFactsPanel from '../components/admin/OpenFoodFactsPanel';

export default function Admin({ token }) {

  const [csvFile, setCsvFile] = useState(null);
  const [uploadStatus, setUploadStatus] = useState('');

  const [loyaltyQuery, setLoyaltyQuery] =
    useState('milk');

  const [loyaltyStatus, setLoyaltyStatus] =
    useState('');

  const [hdxStatus, setHdxStatus] = useState('');

  const [reefStoreCode, setReefStoreCode] =
    useState('');

  const [reefStatus, setReefStatus] = useState('');
  const [reefResult, setReefResult] = useState(null);

  const [busy, setBusy] = useState('');

  // ---------------------------------------------
  // CSV IMPORT
  // ---------------------------------------------

  async function handleCsvUpload(event) {
    event.preventDefault();

    if (!csvFile || busy) {
      return;
    }

    setBusy('csv');
    setUploadStatus('Uploading and processing...');

    const formData = new FormData();
    formData.append('file', csvFile);

    try {
      const response = await apiRequest(
        '/admin/import/csv',
        {
          method: 'POST',
          body: formData,
        },
        token
      );

      setUploadStatus(
        `Imported: ${response.imported ?? 0}, ` +
        `Skipped: ${response.skipped ?? 0}, ` +
        `Errors: ${response.errors?.length ?? 0}`
      );

      setCsvFile(null);

    } catch (error) {
      setUploadStatus(`Error: ${error.message}`);

    } finally {
      setBusy('');
    }
  }

  // ---------------------------------------------
  // EXTERNAL INGESTION
  // ---------------------------------------------

  async function runSync(
    endpoint,
    operation,
    setStatus,
    onSuccess
  ) {
    if (busy) {
      return;
    }

    setBusy(operation);
    setStatus('Processing...');

    try {
      const result = await apiRequest(
        endpoint,
        { method: 'POST' },
        token
      );

      setStatus(
        result?.message ||
        'Sync completed successfully'
      );

      if (onSuccess) {
        onSuccess(result);
      }

    } catch (error) {
      setStatus(`Error: ${error.message}`);

      if (onSuccess) {
        onSuccess(null);
      }

    } finally {
      setBusy('');
    }
  }

  function syncLoyaltyHub() {
    const query = loyaltyQuery.trim();

    if (!query) {
      setLoyaltyStatus('Enter a search query.');
      return;
    }

    runSync(
      `/admin/ingest/loyaltyhub?query=${encodeURIComponent(query)}`,
      'loyalty',
      setLoyaltyStatus
    );
  }

  function syncHdx() {
    runSync(
      '/admin/ingest/hdx/trigger',
      'hdx',
      setHdxStatus
    );
  }

  function syncReef() {
    const storeCode = reefStoreCode
      .trim()
      .toUpperCase();

    if (!storeCode) {
      setReefStatus(
        'Enter a mapped Pick n Pay store code.'
      );
      return;
    }

    const params = new URLSearchParams({
      retailerSlug: 'picknpay',
      storeCode,
    });

    runSync(
      `/admin/ingest/reefapi/promotions?${params}`,
      'reef',
      setReefStatus,
      setReefResult
    );
  }

  // ---------------------------------------------
  // ADMIN UI
  // ---------------------------------------------

  return (
    <div className="space-y-6 max-w-5xl mx-auto">

      <div>
        <h1 className="text-2xl font-bold text-gray-900">
          Data Ingestion
        </h1>

        <p className="text-gray-500 mt-1">
          Manage products, prices and promotional
          data sources.
        </p>
      </div>

      {/* Catalogue manager appears locally only */}
      {import.meta.env.DEV && (
        <OpenFoodFactsPanel token={token} />
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">

        {/* CSV Upload */}
        <Card>
          <CardHeader>
            <h2 className="text-lg font-semibold flex items-center gap-2">
              <UploadCloud size={18} />
              Manual CSV Upload
            </h2>
          </CardHeader>

          <CardContent>
            <form
              onSubmit={handleCsvUpload}
              className="space-y-4"
            >
              <Input
                type="file"
                accept=".csv"
                onChange={event =>
                  setCsvFile(
                    event.target.files?.[0] || null
                  )
                }
              />

              <Button
                type="submit"
                disabled={!csvFile || Boolean(busy)}
              >
                {busy === 'csv'
                  ? 'Uploading...'
                  : 'Upload CSV'}
              </Button>

              {uploadStatus && (
                <p
                  role="status"
                  className="text-sm text-gray-700"
                >
                  {uploadStatus}
                </p>
              )}
            </form>
          </CardContent>
        </Card>

        {/* LoyaltyHub */}
        <Card>
          <CardHeader>
            <h2 className="text-lg font-semibold flex items-center gap-2">
              <RefreshCw size={18} />
              LoyaltyHub Sync
            </h2>
          </CardHeader>

          <CardContent className="space-y-4">
            <p className="text-sm text-gray-500">
              Import available grocery price
              observations from LoyaltyHub.
            </p>

            <Input
              value={loyaltyQuery}
              onChange={event =>
                setLoyaltyQuery(event.target.value)
              }
              placeholder="Search term, e.g. milk"
            />

            <Button
              type="button"
              disabled={Boolean(busy)}
              onClick={syncLoyaltyHub}
            >
              {busy === 'loyalty'
                ? 'Syncing...'
                : 'Sync Prices'}
            </Button>

            {loyaltyStatus && (
              <p
                role="status"
                className="text-sm text-gray-700"
              >
                {loyaltyStatus}
              </p>
            )}
          </CardContent>
        </Card>

        {/* Historical Data */}
        <Card>
          <CardHeader>
            <h2 className="text-lg font-semibold flex items-center gap-2">
              <Database size={18} />
              Historical Data (HDX)
            </h2>
          </CardHeader>

          <CardContent className="space-y-4">
            <p className="text-sm text-gray-500">
              Import historical food-price
              observations for analysis.
              These are not current store-stock
              confirmations.
            </p>

            <Button
              type="button"
              disabled={Boolean(busy)}
              onClick={syncHdx}
            >
              {busy === 'hdx'
                ? 'Importing...'
                : 'Run HDX Import'}
            </Button>

            {hdxStatus && (
              <p
                role="status"
                className="text-sm text-gray-700"
              >
                {hdxStatus}
              </p>
            )}
          </CardContent>
        </Card>

        {/* ReefAPI */}
        <Card>
          <CardHeader>
            <h2 className="text-lg font-semibold flex items-center gap-2">
              <Tag size={18} />
              Promotion Sync (ReefAPI)
            </h2>
          </CardHeader>

          <CardContent className="space-y-4">
            <p className="text-sm text-gray-500">
              Import supported Pick n Pay promotions
              for a mapped store.
              Unsupported, membership-only or
              ambiguous deals are skipped.
            </p>

            <div className="space-y-2">
              <label className="text-sm font-medium text-gray-700">
                Retailer
              </label>

              <Input
                value="Pick n Pay"
                disabled
              />
            </div>

            <div className="space-y-2">
              <label
                htmlFor="reef-store-code"
                className="text-sm font-medium text-gray-700"
              >
                Pick n Pay store code
              </label>

              <Input
                id="reef-store-code"
                value={reefStoreCode}
                onChange={event =>
                  setReefStoreCode(
                    event.target.value.toUpperCase()
                  )
                }
                placeholder="e.g. WC21"
              />

              <p className="text-xs text-gray-500">
                The code must belong to an active
                Pick n Pay branch already mapped
                in Shopping Diary.
              </p>
            </div>

            <Button
              type="button"
              disabled={
                Boolean(busy) ||
                !reefStoreCode.trim()
              }
              onClick={syncReef}
            >
              {busy === 'reef'
                ? 'Syncing...'
                : 'Sync Promotions'}
            </Button>

            {reefStatus && (
              <p
                role="status"
                className="text-sm text-gray-700"
              >
                {reefStatus}
              </p>
            )}

            {reefResult && (
              <div className="rounded-lg bg-gray-50 p-3 text-sm text-gray-700 space-y-1">
                <p>
                  Products fetched:{' '}
                  {reefResult.productsFetched ?? 0}
                </p>

                <p>
                  Matched products:{' '}
                  {reefResult.matchedProducts ?? 0}
                </p>

                <p>
                  Promotions saved:{' '}
                  {reefResult.promotionsSaved ?? 0}
                </p>

                <p>
                  Promotions skipped:{' '}
                  {reefResult.promotionsSkipped ?? 0}
                </p>
              </div>
            )}
          </CardContent>
        </Card>

      </div>
    </div>
  );
}
