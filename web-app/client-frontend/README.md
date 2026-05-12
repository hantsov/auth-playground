# client-frontend

OAuth2 **public client** for the auth-playground project. A small React SPA that authenticates against Keycloak via the Authorization Code + PKCE flow and calls the resource-backend API with the resulting access token.

## Tech stack

- React 19
- Vite 7
- React Router 7
- `keycloak-js` 26 — used directly behind a custom `AuthProvider` context (no third-party React wrapper)
- Axios
- Nginx (in the production container)

## Project layout

```
client-frontend/
├── public/
│   └── silent-check-sso.html        # iframe target for keycloak-js silent SSO
├── src/
│   ├── main.jsx
│   ├── App.jsx
│   ├── auth/
│   │   └── AuthProvider.jsx         # custom React context wrapping keycloak-js
│   ├── config/keycloak.js
│   ├── services/api.js              # axios + token interceptor with refresh coalescing
│   ├── components/ProtectedRoute.jsx
│   ├── pages/{HomePage,DashboardPage}.jsx
│   └── styles/App.css
├── index.html
├── package.json
├── vite.config.js
├── nginx.conf                       # serves the built dist/ in the prod container
├── Dockerfile
└── README.md
```

## Running locally

```bash
npm install        # first time only
npm run dev
```

Default URL: **http://localhost:5173**

The dev server expects Keycloak at `http://localhost:8080` and the resource-backend at `http://localhost:8081`. Start those via the root compose file (`docker compose up -d keycloak-postgres keycloak backend-postgres resource-backend`) before launching the SPA.

### Environment variables (Vite)

These values are baked at *build* time. Defaults match a local docker-compose stack; override via `.env.local` only when a service moves.

| Variable | Default |
|---|---|
| `VITE_KEYCLOAK_URL` | `http://localhost:8080` |
| `VITE_KEYCLOAK_REALM` | `playground` |
| `VITE_KEYCLOAK_CLIENT_ID` | `react-client` |
| `VITE_API_BASE_URL` | `http://localhost:8081` |

> **Vite + Docker caveat:** since Vite inlines `import.meta.env.VITE_*` at build time, the env vars set on the `client-frontend` service in `docker-compose.yml` do **not** affect a pre-built image. They're only useful if you `docker compose build`. The defaults compiled into the bundle work for local dev.

## Auth flow

1. **Init** — on mount, `AuthProvider` calls `keycloak.init({ onLoad: 'check-sso', pkceMethod: 'S256' })`. A `useRef` guard prevents double-init under React 19 Strict Mode.
2. **Silent SSO** — uses the iframe at `public/silent-check-sso.html` to detect existing Keycloak sessions without a full redirect.
3. **Login** — `login()` triggers Keycloak's authorization endpoint with PKCE.
4. **API calls** — `services/api.js` adds `Authorization: Bearer <token>` to every request.
5. **Refresh** — on 401, the response interceptor calls `keycloak.updateToken(30)`. Concurrent 401s share a single in-flight `refreshPromise` to avoid duplicate refresh calls.
6. **Logout** — `logout()` ends the Keycloak session and returns to `/`.

## npm scripts

```
npm run dev        # Vite dev server with HMR
npm run build      # production build into dist/
npm run preview    # serve the built bundle locally
npm run lint       # eslint
```
