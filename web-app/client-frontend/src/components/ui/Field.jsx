/*
 * Field — label + value pair, used inside cards for displaying record data.
 * Defaults to a stacked layout; pass `inline` for label/value on one row.
 */
const Field = ({ label, value, mono = false, inline = false, children }) => {
  const content = children ?? value ?? <span className="text-zinc-400">—</span>;
  const valueClasses = mono
    ? 'font-mono text-sm text-zinc-800 break-all'
    : 'text-sm text-zinc-900';

  if (inline) {
    return (
      <div className="flex items-center justify-between gap-4 py-1.5">
        <dt className="text-sm text-zinc-500">{label}</dt>
        <dd className={valueClasses}>{content}</dd>
      </div>
    );
  }

  return (
    <div className="py-1.5">
      <dt className="text-xs font-medium uppercase tracking-wide text-zinc-500">
        {label}
      </dt>
      <dd className={`mt-1 ${valueClasses}`}>{content}</dd>
    </div>
  );
};

export default Field;
