// Shared display formatting. Timestamps arrive as ISO-8601 from every service,
// so they are parsed and rendered in one place rather than per screen.

const INVALID = "—";

function parse(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const d = new Date(/^\d{4}-\d{2}-\d{2}$/.test(iso) ? `${iso}T00:00:00` : iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Date only, in the viewer's locale. */
export function formatDate(iso: string | null | undefined): string {
  const d = parse(iso);
  return d ? d.toLocaleDateString() : INVALID;
}

/** Date and time, in the viewer's locale. Use wherever a raw ISO string would otherwise show. */
export function formatDateTime(iso: string | null | undefined): string {
  const d = parse(iso);
  return d ? d.toLocaleString() : INVALID;
}

/** "5m ago" for recent items, falling back to a date once it stops being useful. */
export function formatRelative(iso: string | null | undefined): string {
  const d = parse(iso);
  if (!d) return INVALID;
  const mins = Math.round((Date.now() - d.getTime()) / 60000);
  if (mins < 0) return formatDate(iso);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  return formatDate(iso);
}

/** Amount with an explicit currency, formatted consistently throughout the app. */
export function formatMoney(value: number | null | undefined, currency = "VND"): string {
  if (value == null) return INVALID;
  const amount = value.toLocaleString("vi-VN");
  return `${amount} ${currency}`;
}
