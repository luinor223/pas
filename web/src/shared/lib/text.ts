/** Converts codes such as `PRICE_LIST`, `user.updated`, and `fullName` to readable labels. */
export function humanize(value: string): string {
  const words = value
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[-_.]+/g, " ")
    .trim()
    .toLowerCase();
  return words ? words[0].toUpperCase() + words.slice(1) : value;
}

/** Removes internal policy references while leaving document numbers such as CTR-2026-0001 intact. */
export function withoutInternalRuleCodes(value: string): string {
  const policyCode = String.raw`(?:CTR-\d{2}|[DM]\d{1,2}[a-z]?)`;
  const policyGroup = new RegExp(`\\s*\\(${policyCode}(?:\\s*,\\s*${policyCode})*\\)`, "g");
  return value
    .replace(policyGroup, "")
    .replace(/\bCTR-\d{2}\b(?!-)/g, "")
    .replace(/\s{2,}/g, " ")
    .trim();
}
