import { useEffect } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useAccount, ACCOUNT_STATUS } from '../auth/AccountProvider';

/*
 * ProtectedRoute — gate the authenticated-only routes on two checks:
 *
 *   1. Keycloak says the visitor is signed in (AuthProvider).
 *   2. The resource server has a row for them (AccountProvider). If not,
 *      send them to /register before any app surface renders.
 *
 * The /register route uses this same component with `allowUnregistered` so
 * not-yet-provisioned users can see it; conversely, already-provisioned
 * users hitting /register get bounced to /dashboard.
 */
const ProtectedRoute = ({ children, allowUnregistered = false }) => {
  const { authenticated, login } = useAuth();
  const { status } = useAccount();
  const location = useLocation();

  useEffect(() => {
    if (!authenticated) login();
  }, [authenticated, login]);

  if (!authenticated) return null;

  if (
    status === ACCOUNT_STATUS.IDLE ||
    status === ACCOUNT_STATUS.LOADING
  ) {
    return (
      <div className="min-h-full flex items-center justify-center bg-zinc-50">
        <p className="text-sm text-zinc-500">Loading account…</p>
      </div>
    );
  }

  if (status === ACCOUNT_STATUS.NEEDS_REGISTRATION && !allowUnregistered) {
    return <Navigate to="/register" replace state={{ from: location }} />;
  }

  if (status === ACCOUNT_STATUS.READY && allowUnregistered) {
    // Already-provisioned users shouldn't see the registration page.
    return <Navigate to="/dashboard" replace />;
  }

  return children;
};

export default ProtectedRoute;
