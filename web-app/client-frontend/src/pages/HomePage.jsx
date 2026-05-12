import { Navigate } from 'react-router-dom';
import { LogIn } from 'lucide-react';
import { useAuth } from '../auth/AuthProvider';
import AppShell from '../components/layout/AppShell';
import Button from '../components/ui/Button';

/*
 * HomePage
 *
 * Public landing for unauthenticated visitors: hero with a single sign-in
 * CTA. Authenticated visitors are redirected straight to the dashboard —
 * the previous "show profile + go-to-dashboard" middle state was a wizard
 * pattern that doesn't belong in an app.
 */
const HomePage = () => {
  const { authenticated, login } = useAuth();

  if (authenticated) {
    return <Navigate to="/dashboard" replace />;
  }

  return (
    <AppShell>
      <section className="mx-auto max-w-2xl text-center py-16 sm:py-24">
        <h1 className="text-4xl sm:text-5xl font-semibold tracking-tight text-zinc-900">
          Playground Web-App
        </h1>
        <p className="mt-4 text-lg text-zinc-600">
          An OIDC + OAuth2 playground. Sign in to explore how the front end,
          authorization server and resource API work together.
        </p>
        <div className="mt-8 flex justify-center">
          <Button size="md" onClick={() => login()}>
            <LogIn className="h-4 w-4" />
            Sign in
          </Button>
        </div>
      </section>
    </AppShell>
  );
};

export default HomePage;
