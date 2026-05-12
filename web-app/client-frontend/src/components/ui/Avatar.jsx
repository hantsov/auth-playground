/*
 * Avatar — circular initials placeholder. No image support yet; this is a
 * playground app, real avatars would come from the IdP profile.
 */
const sizeClasses = {
  sm: 'h-8 w-8 text-xs',
  md: 'h-10 w-10 text-sm',
  lg: 'h-16 w-16 text-lg',
};

const initialsFromName = (name) => {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

const Avatar = ({ name, size = 'md', className = '' }) => {
  const classes = [
    'inline-flex items-center justify-center rounded-full',
    'bg-violet-100 text-violet-700 font-semibold select-none',
    sizeClasses[size],
    className,
  ].join(' ');

  return (
    <span className={classes} aria-hidden="true">
      {initialsFromName(name)}
    </span>
  );
};

export default Avatar;
