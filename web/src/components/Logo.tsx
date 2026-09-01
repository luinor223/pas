// PAS mark — a shield holding an approved document.
// LogoMark: flat shield, inherits color via currentColor (header/inline).
// LogoTile: solid-navy app-icon tile (login, elsewhere on a light ground).

export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" className={className} role="img" aria-label="PAS">
      <path
        d="M32 8 L52 15 V33 C52 45.5 42.2 52.8 32 57 C21.8 52.8 12 45.5 12 33 V15 Z"
        fill="currentColor"
      />
      <g stroke="#fff" strokeLinecap="round">
        <line x1="25" y1="27" x2="39" y2="27" strokeWidth="1.9" />
        <line x1="25" y1="31.5" x2="39" y2="31.5" strokeWidth="1.9" />
        <path d="M24.5 38 l4.6 4.6 l10-11.5" strokeWidth="2.9" fill="none" strokeLinejoin="round" />
      </g>
    </svg>
  );
}

export function LogoTile({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" className={className} role="img" aria-label="PAS">
      <rect width="64" height="64" rx="15" fill="#14306e" />
      <path
        d="M32 15 L48 20.5 V33 C48 42.6 41 48.6 32 52 C23 48.6 16 42.6 16 33 V20.5 Z"
        fill="#fff"
      />
      <g stroke="#16357a" strokeLinecap="round">
        <line x1="26" y1="27" x2="38" y2="27" strokeWidth="1.7" />
        <line x1="26" y1="31" x2="38" y2="31" strokeWidth="1.7" />
        <path d="M25.5 37 l4.2 4.2 l9.2-10.6" strokeWidth="2.6" fill="none" strokeLinejoin="round" />
      </g>
    </svg>
  );
}
