// PAS brand mark - a navy shield holding an approved document (our own logo).
// tone="light" (on navy grounds): white shield, navy inner marks, white wordmark.
// tone="dark"  (on light grounds): navy shield, white inner marks, near-black wordmark.

const NAVY = "#14306e";

export function LogoMark({ className, tone = "dark" }: { className?: string; tone?: "dark" | "light" }) {
  const shield = tone === "light" ? "#ffffff" : NAVY;
  const ink = tone === "light" ? NAVY : "#ffffff";
  return (
    <svg viewBox="0 0 64 64" className={className} role="img" aria-label="PAS">
      <path d="M32 8 L52 15 V33 C52 45.5 42.2 52.8 32 57 C21.8 52.8 12 45.5 12 33 V15 Z" fill={shield} />
      <g stroke={ink} strokeLinecap="round">
        <line x1="25" y1="27" x2="39" y2="27" strokeWidth="1.9" />
        <line x1="25" y1="31.5" x2="39" y2="31.5" strokeWidth="1.9" />
        <path d="M24.5 38 l4.6 4.6 l10-11.5" strokeWidth="2.9" fill="none" strokeLinejoin="round" />
      </g>
    </svg>
  );
}

export function Logo({ className, tone = "dark" }: { className?: string; tone?: "dark" | "light" }) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className ?? ""}`}>
      <LogoMark tone={tone} className="h-9 w-9 shrink-0" />
      <span className="flex flex-col leading-none">
        <span className="text-[17px] font-bold tracking-tight" style={{ color: tone === "light" ? "#ffffff" : "#1f2733" }}>
          PAS
        </span>
        <span className="mt-0.5 text-[11px] font-medium" style={{ color: tone === "light" ? "rgba(234,240,255,.7)" : "#6b7280" }}>
          Document Management
        </span>
      </span>
    </span>
  );
}
