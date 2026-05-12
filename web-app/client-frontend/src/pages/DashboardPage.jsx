import { useAuth } from '../auth/AuthProvider';
import { useAccount, ACCOUNT_STATUS } from '../auth/AccountProvider';
import AppShell from '../components/layout/AppShell';
import Avatar from '../components/ui/Avatar';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';

/*
 * DashboardPage
 *
 * The product surface for signed-in, registered users. Profile card from
 * JWT claims, Account card from the application's user row (kept fresh by
 * AccountProvider's background sync on each login). JWT internals and the
 * raw DB row live on /inspector — reachable via the user menu — so the
 * dashboard reads as a regular app rather than a debug panel.
 */

const ProfileSkeleton = () => (
  <div className="space-y-4 animate-pulse">
    <div className="flex items-center gap-4">
      <div className="h-16 w-16 rounded-full bg-zinc-200" />
      <div className="space-y-2">
        <div className="h-4 w-40 rounded bg-zinc-200" />
        <div className="h-3 w-56 rounded bg-zinc-200" />
      </div>
    </div>
  </div>
);

const AccountSkeleton = () => (
  <dl className="space-y-4 animate-pulse">
    {[0, 1, 2].map((i) => (
      <div key={i}>
        <div className="h-3 w-24 rounded bg-zinc-200" />
        <div className="mt-2 h-4 w-48 rounded bg-zinc-200" />
      </div>
    ))}
  </dl>
);

const DashboardPage = () => {
  const { tokenParsed } = useAuth();
  const { user, status } = useAccount();

  const displayName =
    [tokenParsed?.given_name, tokenParsed?.family_name]
      .filter(Boolean)
      .join(' ') ||
    tokenParsed?.preferred_username ||
    'Account';

  const accountLoading = status !== ACCOUNT_STATUS.READY || !user;

  return (
    <AppShell>
      <div className="mb-6">
        <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">
          Dashboard
        </h1>
        <p className="mt-1 text-sm text-zinc-500">
          Your profile and account details.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card title="Profile">
          {!tokenParsed ? (
            <ProfileSkeleton />
          ) : (
            <div className="flex items-center gap-4">
              <Avatar name={displayName} size="lg" />
              <div className="min-w-0">
                <p className="text-base font-medium text-zinc-900 truncate">
                  {displayName}
                </p>
                <p className="text-sm text-zinc-500 truncate">
                  {tokenParsed.email || 'No email on file'}
                </p>
                {tokenParsed.preferred_username && (
                  <p className="mt-0.5 text-xs text-zinc-400 truncate">
                    @{tokenParsed.preferred_username}
                  </p>
                )}
              </div>
            </div>
          )}
        </Card>

        <Card title="Account">
          {accountLoading ? (
            <AccountSkeleton />
          ) : (
            <dl className="divide-y divide-zinc-100">
              <Field label="First name" value={user.firstName} />
              <Field label="Last name" value={user.lastName} />
              <Field label="Email" value={user.email} />
              <Field label="Username" value={user.username} />
            </dl>
          )}
        </Card>

        {!accountLoading && user.customData && (
          <Card
            title="Custom data"
            subtitle="Stored as JSONB on the user record"
            className="lg:col-span-2"
          >
            <pre className="rounded-md bg-zinc-50 border border-zinc-200 p-4 text-xs text-zinc-800 overflow-auto">
              {JSON.stringify(user.customData, null, 2)}
            </pre>
          </Card>
        )}
      </div>
    </AppShell>
  );
};

export default DashboardPage;
