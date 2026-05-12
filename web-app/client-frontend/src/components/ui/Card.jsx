/*
 * Card — surface container with subtle border + shadow.
 * Composes via title/subtitle props or freeform children.
 */
const Card = ({ title, subtitle, action, children, className = '', ...props }) => {
  const classes = [
    'rounded-xl bg-white border border-zinc-200 shadow-card overflow-hidden',
    className,
  ].join(' ');

  return (
    <div className={classes} {...props}>
      {(title || subtitle || action) && (
        <div className="flex items-start justify-between gap-4 px-6 pt-5 pb-4 border-b border-zinc-100">
          <div>
            {title && (
              <h2 className="text-base font-semibold text-zinc-900">{title}</h2>
            )}
            {subtitle && (
              <p className="mt-0.5 text-sm text-zinc-500">{subtitle}</p>
            )}
          </div>
          {action && <div className="shrink-0">{action}</div>}
        </div>
      )}
      <div className="px-6 py-5">{children}</div>
    </div>
  );
};

export default Card;
