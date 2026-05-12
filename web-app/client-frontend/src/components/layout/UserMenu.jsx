import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronDown, LogOut, ScanLine } from 'lucide-react';
import { useAuth } from '../../auth/AuthProvider';
import Avatar from '../ui/Avatar';

/*
 * UserMenu — avatar + dropdown shown in the header when authenticated.
 * Houses navigation that's secondary to the main app surface (token
 * inspector, sign out).
 */
const UserMenu = () => {
  const { tokenParsed, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef(null);

  const displayName =
    [tokenParsed?.given_name, tokenParsed?.family_name]
      .filter(Boolean)
      .join(' ') ||
    tokenParsed?.preferred_username ||
    'Account';
  const email = tokenParsed?.email;

  useEffect(() => {
    if (!open) return;
    const onClick = (e) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) {
        setOpen(false);
      }
    };
    const onKey = (e) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div ref={wrapperRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        className="flex items-center gap-2 rounded-full p-1 pr-2 hover:bg-zinc-100 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-400"
      >
        <Avatar name={displayName} size="sm" />
        <span className="hidden sm:inline text-sm font-medium text-zinc-800">
          {displayName}
        </span>
        <ChevronDown className="h-4 w-4 text-zinc-500" />
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 mt-2 w-60 rounded-lg border border-zinc-200 bg-white shadow-lg overflow-hidden z-10"
        >
          <div className="px-3 py-3 border-b border-zinc-100">
            <p className="text-sm font-medium text-zinc-900 truncate">
              {displayName}
            </p>
            {email && (
              <p className="text-xs text-zinc-500 truncate">{email}</p>
            )}
          </div>
          <div className="py-1">
            <Link
              to="/inspector"
              role="menuitem"
              onClick={() => setOpen(false)}
              className="flex items-center gap-2 px-3 py-2 text-sm text-zinc-700 hover:bg-zinc-50"
            >
              <ScanLine className="h-4 w-4" />
              Token Inspector
            </Link>
            <button
              type="button"
              role="menuitem"
              onClick={() => logout()}
              className="flex items-center gap-2 w-full px-3 py-2 text-sm text-zinc-700 hover:bg-zinc-50 text-left"
            >
              <LogOut className="h-4 w-4" />
              Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default UserMenu;
