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

/** Formats an API decimal string without converting it to an imprecise JavaScript number. */
export function formatDecimalMoney(value: string, currency: string): string {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) return INVALID;
  const [, sign, integer, rawFraction = ""] = match;
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  const fraction = rawFraction.replace(/0+$/, "");
  return `${sign}${grouped}${fraction ? `,${fraction}` : ""} ${currency}`;
}

/** Current local date for an HTML date input (YYYY-MM-DD). */
export function localDateInputValue(date = new Date()): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/** Current local month for an HTML month input (YYYY-MM). */
export function localMonthInputValue(date = new Date()): string {
  return localDateInputValue(date).slice(0, 7);
}
