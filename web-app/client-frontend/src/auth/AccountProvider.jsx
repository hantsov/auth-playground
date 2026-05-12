import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import api from '../services/api';
import { useAuth } from './AuthProvider';

/*
 * AccountProvider — application-side counterpart to AuthProvider.
 *
 * AuthProvider answers "is the visitor signed in with Keycloak, and what
 * does the JWT say about them." AccountProvider answers "do they have a
 * user record on the resource server, and what's in it." Two concerns,
 * two contexts.
 *
 * Lifecycle, triggered each time AuthProvider flips to authenticated=true:
 *
 *   1. GET /api/user
 *      → 200  → row exists. Fire POST /api/user/sync in the background so
 *               the row mirrors the freshly issued JWT claims. The sync
 *               result, when it arrives, replaces the cached row.
 *      → 404  → no row yet. Status flips to 'needs-registration' and the
 *               router redirects to /register.
 *
 *   2. The /register page calls register() which POSTs /api/user/register,
 *      stores the created row and flips status to 'ready'.
 *
 * Sync failures are non-fatal: the user is still authenticated and their
 * stored row is still valid, just possibly stale by a few claim fields.
 * Next login retries.
 */

const AccountContext = createContext(null);

const STATUS = {
  IDLE: 'idle',
  LOADING: 'loading',
  READY: 'ready',
  NEEDS_REGISTRATION: 'needs-registration',
  ERROR: 'error',
};

export const AccountProvider = ({ children }) => {
  const { authenticated } = useAuth();
  const [status, setStatus] = useState(STATUS.IDLE);
  const [user, setUser] = useState(null);
  const [error, setError] = useState(null);

  const bootstrap = useCallback(async () => {
    setStatus(STATUS.LOADING);
    setError(null);
    try {
      const res = await api.get('/api/user');
      setUser(res.data);
      setStatus(STATUS.READY);

      // Fire-and-forget sync. If it fails, the user keeps the row we just
      // fetched — slightly stale claims aren't worth blocking on.
      api
        .post('/api/user/sync')
        .then((syncRes) => setUser(syncRes.data))
        .catch((err) =>
          console.warn('Background sync failed; using stored row.', err),
        );
    } catch (err) {
      if (err.response?.status === 404) {
        setUser(null);
        setStatus(STATUS.NEEDS_REGISTRATION);
        return;
      }
      console.error('Account bootstrap failed', err);
      setError(err.response?.data?.message || err.message || 'Unknown error');
      setStatus(STATUS.ERROR);
    }
  }, []);

  const register = useCallback(async () => {
    const res = await api.post('/api/user/register');
    setUser(res.data);
    setStatus(STATUS.READY);
    return res.data;
  }, []);

  const sync = useCallback(async () => {
    const res = await api.post('/api/user/sync');
    setUser(res.data);
    return res.data;
  }, []);

  useEffect(() => {
    if (!authenticated) {
      // Reset when signing out so a subsequent sign-in re-bootstraps cleanly.
      setStatus(STATUS.IDLE);
      setUser(null);
      setError(null);
      return;
    }
    bootstrap();
  }, [authenticated, bootstrap]);

  const value = {
    status,
    user,
    error,
    bootstrap,
    register,
    sync,
  };

  return (
    <AccountContext.Provider value={value}>{children}</AccountContext.Provider>
  );
};

export const useAccount = () => {
  const ctx = useContext(AccountContext);
  if (!ctx) throw new Error('useAccount must be used within AccountProvider');
  return ctx;
};

export const ACCOUNT_STATUS = STATUS;
