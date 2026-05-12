import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthProvider';
import Button from '../ui/Button';
import UserMenu from './UserMenu';

/*
 * Header — top bar of the AppShell. Product name on the left, primary
 * navigation in the center (only when authed and we have somewhere to go),
 * user menu or sign-in CTA on the right.
 */
const Header = () => {
  const { authenticated, login } = useAuth();

  return (
    <header className="border-b border-zinc-200 bg-white">
      <div className="mx-auto max-w-6xl px-6 h-14 flex items-center justify-between">
        <Link
          to="/"
          className="text-sm font-semibold tracking-tight text-zinc-900 hover:text-violet-700 transition-colors"
        >
          Playground Web-App
        </Link>

        <div>
          {authenticated ? (
            <UserMenu />
          ) : (
            <Button size="sm" onClick={() => login()}>
              Sign in
            </Button>
          )}
        </div>
      </div>
    </header>
  );
};

export default Header;
