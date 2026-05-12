import { useState } from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { useAuth } from '../auth/AuthProvider';
import { useAccount, ACCOUNT_STATUS } from '../auth/AccountProvider';
import AppShell from '../components/layout/AppShell';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';

/*
 * InspectorPage
 *
 * The educational payload of this playground, isolated to its own page
 * (linked from the user menu) so the dashboard can stay product-shaped.
 *
 * Two side-by-side raw views:
 *   - The decoded JWT claims keycloak-js exposes via tokenParsed
 *   - The DB row the resource-backend returns from /api/user
 *
 * Together they show the two sources the app reconciles: claims about the
 * user that travel with each request (fast, signature-validated against
 * Keycloak's JWKS), versus persisted application state stored alongside
 * the `sub` UUID. The "Re-sync from token" button forces a POST
 * /api/user/sync so the lastSyncedAt timestamp updates visibly.
 */
const JsonBlock = ({ data }) => (
  <pre className="rounded-md bg-zinc-900 text-zinc-100 p-4 text-xs leading-relaxed overflow-auto max-h-[28rem]">
    {JSON.stringify(data, null, 2)}
  </pre>
);

const formatTimestamp = (iso) => {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
};

const InspectorPage = () => {
  const { tokenParsed } = useAuth();
  const { user, status, sync } = useAccount();
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState(null);

  const handleSync = async () => {
    try {
      setSyncing(true);
      setError(null);
      await sync();
    } catch (err) {
      console.error('Sync failed', err);
      setError(
        err.response?.data?.message || err.message || 'Failed to sync',
      );
    } finally {
      setSyncing(false);
    }
  };

  const accountLoading = status !== ACCOUNT_STATUS.READY || !user;

  return (
    <AppShell>
      <div className="mb-6 max-w-3xl">
        <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">
          Token Inspector
        </h1>
        <p className="mt-2 text-sm text-zinc-600">
          A developer view of the two data sources the dashboard reconciles:
          the decoded JWT delivered by Keycloak (validated by the resource
          server against Keycloak’s JWKS endpoint, never via per-request
          introspection), and the matching application row keyed by the
          token’s <code className="text-xs bg-zinc-100 px-1 py-0.5 rounded">sub</code>{' '}
          claim. The application row is auto-synced from the JWT on every
          login; the timestamp below shows when that last ran.
        </p>
        {user?.lastSyncedAt && (
          <p className="mt-2 text-xs text-zinc-500">
            Last synced: {formatTimestamp(user.lastSyncedAt)}
          </p>
        )}
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <Card
          title="JWT claims"
          subtitle="From keycloak-js · tokenParsed"
        >
          {tokenParsed ? (
            <JsonBlock data={tokenParsed} />
          ) : (
            <p className="text-sm text-zinc-500">No token available.</p>
          )}
        </Card>

        <Card
          title="User record"
          subtitle="From resource-backend · GET /api/user"
          action={
            <Button
              variant="secondary"
              size="sm"
              onClick={handleSync}
              disabled={syncing || accountLoading}
            >
              <RefreshCw
                className={`h-3.5 w-3.5 ${syncing ? 'animate-spin' : ''}`}
              />
              {syncing ? 'Syncing…' : 'Re-sync from token'}
            </Button>
          }
        >
          {accountLoading && (
            <div className="h-32 rounded-md bg-zinc-100 animate-pulse" />
          )}
          {!accountLoading && error && (
            <div className="mb-3 flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3">
              <AlertCircle className="h-5 w-5 text-red-600 shrink-0 mt-0.5" />
              <p className="text-sm text-red-700">{error}</p>
            </div>
          )}
          {!accountLoading && user && <JsonBlock data={user} />}
        </Card>
      </div>
    </AppShell>
  );
};

export default InspectorPage;
