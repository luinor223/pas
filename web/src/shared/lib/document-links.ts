export type DocumentTarget = {
  to: "/contracts" | "/price-lists" | "/addenda" | "/payment-statements" | "/volume-records";
  search: Record<string, string>;
};

export function documentTarget(documentType: string | null | undefined, documentId: string | null | undefined): DocumentTarget | undefined {
  if (!documentType || !documentId) return undefined;
  if (documentType === "CONTRACT") return { to: "/contracts", search: { id: documentId } };
  if (documentType === "PRICE_LIST") return { to: "/price-lists", search: { versionId: documentId } };
  if (documentType === "ADDENDUM") return { to: "/addenda", search: { id: documentId } };
  if (documentType === "PAYMENT_STATEMENT") return { to: "/payment-statements", search: { id: documentId } };
  if (documentType === "OPERATION_PERIOD") return { to: "/volume-records", search: { tab: "periods" } };
  return undefined;
}
