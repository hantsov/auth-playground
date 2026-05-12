/*
 * Footer — minimal single-line strip. The GitHub link is a placeholder;
 * point the href at the real repo when this lands somewhere.
 *
 * lucide-react no longer ships brand marks, so the GitHub glyph is
 * inlined as an SVG here rather than pulled from an icon set.
 */
const GithubMark = ({ className = '' }) => (
  <svg
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden="true"
    className={className}
  >
    <path d="M12 .5C5.65.5.5 5.65.5 12c0 5.08 3.29 9.39 7.86 10.91.58.11.79-.25.79-.56v-2.19c-3.2.7-3.87-1.36-3.87-1.36-.52-1.32-1.27-1.67-1.27-1.67-1.04-.71.08-.7.08-.7 1.15.08 1.76 1.18 1.76 1.18 1.02 1.76 2.69 1.25 3.34.96.1-.74.4-1.25.72-1.54-2.55-.29-5.24-1.28-5.24-5.69 0-1.26.45-2.29 1.18-3.1-.12-.29-.51-1.46.11-3.04 0 0 .97-.31 3.18 1.18a11 11 0 0 1 5.79 0c2.21-1.49 3.18-1.18 3.18-1.18.62 1.58.23 2.75.11 3.04.74.81 1.18 1.84 1.18 3.1 0 4.42-2.69 5.39-5.25 5.68.41.36.78 1.06.78 2.14v3.17c0 .31.21.68.8.56A11.5 11.5 0 0 0 23.5 12C23.5 5.65 18.35.5 12 .5z" />
  </svg>
);

const Footer = () => {
  return (
    <footer className="border-t border-zinc-200 bg-white">
      <div className="mx-auto max-w-6xl px-6 h-12 flex items-center justify-between text-xs text-zinc-500">
        <span>Playground Web-App</span>
        <a
          href="#"
          className="inline-flex items-center gap-1.5 hover:text-zinc-900 transition-colors"
          aria-label="View source on GitHub"
        >
          <GithubMark className="h-3.5 w-3.5" />
          GitHub
        </a>
      </div>
    </footer>
  );
};

export default Footer;
