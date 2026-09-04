export function formatPeriod(periodCode: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(periodCode);
  if (!match) return periodCode;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, 1);
  return date.toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

export function formatQuantity(value: number): string {
  return value.toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}
