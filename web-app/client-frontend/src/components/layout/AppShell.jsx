import Header from './Header';
import Footer from './Footer';

/*
 * AppShell — the standard chrome wrapping every page: sticky-feeling header,
 * centered max-width main column, footer. Pages render their own card layout
 * inside `children` and don't need to know about the surrounding layout.
 */
const AppShell = ({ children }) => {
  return (
    <div className="min-h-full flex flex-col bg-zinc-50">
      <Header />
      <main className="flex-1">
        <div className="mx-auto max-w-6xl px-6 py-8">{children}</div>
      </main>
      <Footer />
    </div>
  );
};

export default AppShell;
