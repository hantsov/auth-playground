import { createContext, useContext, useEffect, useRef, useState } from 'react';
import keycloak from '../config/keycloak';

const AuthContext = createContext(null);

export const AuthProvider = ({ children, fallback = <div>Loading...</div> }) => {
  const [initialized, setInitialized] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);
  const [tokenParsed, setTokenParsed] = useState(null);
  const initStarted = useRef(false);

  useEffect(() => {
    if (initStarted.current) return;
    initStarted.current = true;

    keycloak
      .init({
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html',
        pkceMethod: 'S256',
        checkLoginIframe: false,
      })
      .then((auth) => {
        setAuthenticated(auth);
        setTokenParsed(keycloak.tokenParsed ?? null);
        setInitialized(true);
      })
      .catch((err) => {
        console.error('Keycloak init failed', err);
        setInitialized(true);
      });

    keycloak.onAuthSuccess = () => {
      setAuthenticated(true);
      setTokenParsed(keycloak.tokenParsed ?? null);
    };
    keycloak.onAuthRefreshSuccess = () => {
      setTokenParsed(keycloak.tokenParsed ?? null);
    };
    keycloak.onAuthLogout = () => {
      setAuthenticated(false);
      setTokenParsed(null);
    };
    keycloak.onTokenExpired = () => {
      keycloak.updateToken(30).catch(() => keycloak.login());
    };
  }, []);

  const value = {
    initialized,
    authenticated,
    tokenParsed,
    keycloak,
    login: (opts) => keycloak.login(opts),
    logout: (opts) => keycloak.logout(opts ?? { redirectUri: window.location.origin }),
  };

  if (!initialized) return fallback;

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
};
