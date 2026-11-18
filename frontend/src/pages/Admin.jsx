import { useState } from 'react';
import { UploadCloud, RefreshCw, Database, Tag } from 'lucide-react';
import { Card, CardContent, CardHeader } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { apiRequest } from '../api/client';

export default function Admin({ token }) {
    const [csvFile, setCsvFile] = useState(null);
    const [uploadStatus, setUploadStatus] = useState('');

    const [loyaltyQuery, setLoyaltyQuery] = useState('milk');
    const [reefRetailer, setReefRetailer] = useState('checkers');
    const [syncStatus, setSyncStatus] = useState('');

    const handleCsvUpload = async (e) => {
        e.preventDefault();
        if (!csvFile) return;
        setUploadStatus('Uploading and processing...');

        const formData = new FormData();
        formData.append('file', csvFile);

        try {
            const res = await apiRequest('/admin/import/csv', {
                method: 'POST',
                body: formData,
                // Note: We need to bypass the JSON stringify in apiRequest for FormData
                headers: { 'Authorization': `Bearer ${token}` }
            });
            setUploadStatus(`Success! Imported: ${res.imported}, Skipped: ${res.skipped}, Errors: ${res.errors.length}`);
            setCsvFile(null);
        } catch (err) {
            setUploadStatus(`Error: ${err.message}`);
        }
    };

    const triggerSync = async (endpoint, payload, setStatus) => {
        setStatus('Processing...');
        try {
            const res = await apiRequest(endpoint, {
                method: 'POST',
                body: JSON.stringify(payload)
            }, token);
            setStatus(`Success: ${res}`);
        } catch (err) {
            setStatus(`Error: ${err.message}`);
        }
    };

    return (
        <div className="space-y-6 max-w-5xl mx-auto">
            <div>
                <h1 className="text-2xl font-bold text-gray-900">Data Ingestion</h1>
                <p className="text-gray-500 mt-1">Manage and trigger data pipelines for products, prices, and promotions.</p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">

                {/* CSV Upload */}
                <Card>
                    <CardHeader>
                        <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
                            <UploadCloud size={18} /> Manual CSV Upload
                        </h2>
                    </CardHeader>
                    <CardContent>
                        <form onSubmit={handleCsvUpload} className="space-y-4">
                            <Input
                                type="file"
                                accept=".csv"
                                onChange={(e) => setCsvFile(e.target.files[0])}
                                className="file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-emerald-50 file:text-emerald-700 hover:file:bg-emerald-100"
                            />
                            <Button type="submit" disabled={!csvFile || uploadStatus.includes('Uploading')}>
                                Upload CSV
                            </Button>
                            {uploadStatus && <p className="text-sm font-medium text-gray-700">{uploadStatus}</p>}
                        </form>
                    </CardContent>
                </Card>

                {/* LoyaltyHub Sync */}
                <Card>
                    <CardHeader>
                        <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
                            <RefreshCw size={18} /> LoyaltyHub Sync
                        </h2>
                    </CardHeader>
                    <CardContent>
                        <div className="space-y-4">
                            <p className="text-sm text-gray-500">Fetch live prices from major retailers (limited to 10 rows/call).</p>
                            <div className="flex gap-2">
                                <Input
                                    value={loyaltyQuery}
                                    onChange={e => setLoyaltyQuery(e.target.value)}
                                    placeholder="Search term (e.g. milk)"
                                />
                                <Button onClick={() => triggerSync(`/admin/ingest/loyaltyhub?query=${loyaltyQuery}`, {}, setSyncStatus)}>
                                    Sync
                                </Button>
                            </div>
                            {syncStatus && <p className="text-sm font-medium text-gray-700">{syncStatus}</p>}
                        </div>
                    </CardContent>
                </Card>

                {/* HDX Historical */}
                <Card>
                    <CardHeader>
                        <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
                            <Database size={18} /> Historical Data (HDX)
                        </h2>
                    </CardHeader>
                    <CardContent>
                        <div className="space-y-4">
                            <p className="text-sm text-gray-500">Download the latest UN/WFP historical food price dataset.</p>
                            <Button onClick={() => triggerSync('/admin/ingest/hdx/trigger', {}, setSyncStatus)}>
                                Run HDX Import
                            </Button>
                        </div>
                    </CardContent>
                </Card>

                {/* ReefAPI Promotions */}
                <Card>
                    <CardHeader>
                        <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
                            <Tag size={18} /> Promotion Sync (ReefAPI)
                        </h2>
                    </CardHeader>
                    <CardContent>
                        <div className="space-y-4">
                            <p className="text-sm text-gray-500">Fetch active multi-buys and specials.</p>
                            <div className="flex gap-2">
                                <Input
                                    value={reefRetailer}
                                    onChange={e => setReefRetailer(e.target.value)}
                                    placeholder="Retailer slug (e.g. checkers)"
                                />
                                <Button onClick={() => triggerSync(`/admin/ingest/reefapi/promotions?retailerSlug=${reefRetailer}`, {}, setSyncStatus)}>
                                    Sync
                                </Button>
                            </div>
                        </div>
                    </CardContent>
                </Card>

            </div>
        </div>
    );
}