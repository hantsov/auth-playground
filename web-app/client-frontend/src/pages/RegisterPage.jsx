import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertCircle, LogOut, UserPlus } from 'lucide-react';
import { useAuth } from '../auth/AuthProvider';
import { useAccount } from '../auth/AccountProvider';
import AppShell from '../components/layout/AppShell';
import Avatar from '../components/ui/Avatar';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';

/*
 * RegisterPage
 *
 * Reached when the SPA's account bootstrap (AccountProvider) discovers
 * that GET /api/user returned 404 — the visitor is authenticated by
 * Keycloak but has no row on the resource server yet.
 *
 * The user reviews their imported claims (read-only — editing here would
 * just be overwritten by the next sync) and confirms; the page POSTs to
 * /api/user/register, the bootstrap row replaces the empty state, and the
 * router navigates to /dashboard.
 */
const RegisterPage = () => {
  const { tokenParsed, logout } = useAuth();
  const { register } = useAccount();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const displayName =
    [tokenParsed?.given_name, tokenParsed?.family_name]
      .filter(Boolean)
      .join(' ') ||
    tokenParsed?.preferred_username ||
    'Account';

  const handleCreate = async () => {
    try {
      setSubmitting(true);
      setError(null);
      await register();
      navigate('/dashboard', { replace: true });
    } catch (err) {
      console.error('Registration failed', err);
      setError(
        err.response?.data?.message ||
          err.message ||
          'Failed to create account',
      );
      setSubmitting(false);
    }
  };

  return (
    <AppShell>
      <div className="mx-auto max-w-xl">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">
            Create your account
          </h1>
          <p className="mt-2 text-sm text-zinc-600">
            We’ve imported the following information from your identity
            provider. Confirm to create your account on Playground Web-App.
          </p>
        </div>

        <Card>
          <div className="flex items-center gap-4 mb-5">
            <Avatar name={displayName} size="lg" />
            <div className="min-w-0">
              <p className="text-base font-medium text-zinc-900 truncate">
                {displayName}
              </p>
              {tokenParsed?.email && (
                <p className="text-sm text-zinc-500 truncate">
                  {tokenParsed.email}
                </p>
              )}
            </div>
          </div>

          <dl className="divide-y divide-zinc-100">
            <Field
              label="First name"
              value={tokenParsed?.given_name}
            />
            <Field
              label="Last name"
              value={tokenParsed?.family_name}
            />
            <Field label="Email" value={tokenParsed?.email} />
            <Field
              label="Username"
              value={tokenParsed?.preferred_username}
            />
          </dl>

          {error && (
            <div className="mt-5 flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3">
              <AlertCircle className="h-5 w-5 text-red-600 shrink-0 mt-0.5" />
              <p className="text-sm text-red-700">{error}</p>
            </div>
          )}

          <div className="mt-6 flex items-center justify-between gap-3">
            <Button
              variant="ghost"
              size="md"
              onClick={() => logout()}
              disabled={submitting}
            >
              <LogOut className="h-4 w-4" />
              Sign out
            </Button>
            <Button
              variant="primary"
              size="md"
              onClick={handleCreate}
              disabled={submitting}
            >
              <UserPlus className="h-4 w-4" />
              {submitting ? 'Creating account…' : 'Create account'}
            </Button>
          </div>
        </Card>
      </div>
    </AppShell>
  );
};

export default RegisterPage;
