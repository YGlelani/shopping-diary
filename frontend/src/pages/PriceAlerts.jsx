
import {
  useState,
  useEffect,
} from 'react';

import {
  useNavigate,
} from 'react-router-dom';

import {
  Bell,
  Trash2,
} from 'lucide-react';

import {
  Card,
  CardContent,
  CardHeader,
} from '../components/ui/Card';

import { Button } from '../components/ui/Button';
import { apiRequest } from '../api/client';

function money(value) {
  const number = Number(value);

  return Number.isFinite(number)
    ? `R${number.toFixed(2)}`
    : 'Unavailable';
}

function alertStatus(alert) {
  if (!alert.active) {
    return {
      label: 'Inactive',
      className:
        'bg-gray-100 text-gray-600',
    };
  }

  if (alert.lastNotifiedAt) {
    return {
      label: 'Target detected',
      className:
        'bg-emerald-100 text-emerald-800',
    };
  }

  return {
    label: 'Watching',
    className:
      'bg-blue-100 text-blue-800',
  };
}

export default function PriceAlerts({
  token,
}) {
  const navigate = useNavigate();

  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] =
    useState(false);

  async function loadAlerts() {
    const response = await apiRequest(
      '/alerts',
      {},
      token
    );

    if (!Array.isArray(response)) {
      throw new Error(
        'Invalid alert response'
      );
    }

    setAlerts(response);
  }

  useEffect(() => {
    let cancelled = false;

    async function initialize() {
      try {
        const response = await apiRequest(
          '/alerts',
          {},
          token
        );

        if (!cancelled) {
          setAlerts(
            Array.isArray(response)
              ? response
              : []
          );
          setError('');
        }

      } catch (err) {
        if (!cancelled) {
          setError(err.message);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    initialize();

    return () => {
      cancelled = true;
    };
  }, [token]);

  async function refreshAlerts() {
    setRefreshing(true);
    setError('');

    try {
      await loadAlerts();
    } catch (err) {
      setError(err.message);
    } finally {
      setRefreshing(false);
    }
  }

  async function deleteAlert(id) {
    setError('');

    try {
      await apiRequest(
        `/alerts/${id}`,
        { method: 'DELETE' },
        token
      );

      setAlerts(current =>
        current.filter(
          alert => alert.id !== id
        )
      );

    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <div className="flex justify-between items-start gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">
            Price Alerts
          </h1>

          <p className="text-gray-500 mt-1">
            Monitor the products you want
            to buy when their prices drop.
          </p>
        </div>

        <Button
          variant="secondary"
          disabled={refreshing || loading}
          onClick={refreshAlerts}
        >
          {refreshing
            ? 'Refreshing...'
            : 'Refresh'}
        </Button>
      </div>

      {error && (
        <p
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 p-3 text-red-700"
        >
          {error}
        </p>
      )}

      <Card>
        <CardHeader className="bg-gray-50/50">
          <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
            <Bell size={18} />
            Your Alerts
          </h2>
        </CardHeader>

        <CardContent className="p-0">
          {loading ? (
            <div className="p-6 text-center text-gray-500">
              Loading alerts...
            </div>
          ) : alerts.length === 0 ? (
            <div className="text-center py-12 px-4">
              <p className="text-gray-500 mb-4">
                You don't have any price
                alerts yet.
              </p>

              <Button
                onClick={() =>
                  navigate('/discover')
                }
              >
                Find Products to Track
              </Button>
            </div>
          ) : (
            <ul className="divide-y divide-gray-100">
              {alerts.map(alert => {
                const status =
                  alertStatus(alert);

                return (
                  <li
                    key={alert.id}
                    className="p-4 sm:px-6 flex flex-col sm:flex-row sm:justify-between sm:items-center gap-4 hover:bg-gray-50"
                  >
                    <div>
                      <p className="font-medium text-gray-900">
                        Product #{alert.productId}
                      </p>

                      <p className="text-sm text-gray-500 mt-1">
                        Target price{' '}
                        <span className="font-bold text-emerald-700">
                          {money(
                            alert.targetPrice
                          )}
                        </span>
                      </p>

                      {alert.lastNotifiedAt && (
                        <p className="text-xs text-gray-500 mt-2">
                          Last matching offer detected:{' '}
                          {new Date(
                            alert.lastNotifiedAt
                          ).toLocaleString(
                            'en-ZA'
                          )}
                        </p>
                      )}
                    </div>

                    <div className="flex items-center gap-3">
                      <span
                        className={`px-2.5 py-1 rounded-full text-xs font-medium ${status.className}`}
                      >
                        {status.label}
                      </span>

                      <button
                        type="button"
                        className="p-2 text-gray-400 hover:text-red-600 transition-colors"
                        title="Delete alert"
                        aria-label="Delete alert"
                        onClick={() =>
                          deleteAlert(alert.id)
                        }
                      >
                        <Trash2 size={18} />
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </CardContent>
      </Card>

      <p className="text-xs text-gray-500">
        Price targets are checked
        automatically each day.
        Detected prices are estimates
        and may change before you shop.
      </p>
    </div>
  );
}
