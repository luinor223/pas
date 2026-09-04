export type DocumentTarget = {
  to: "/customers" | "/contracts" | "/price-lists" | "/addenda" | "/payment-statements" | "/volume-records" | "/approvals" | "/admin/users" | "/admin/roles" | "/admin/workflows";
  search: Record<string, string>;
};

export function documentTarget(documentType: string | null | undefined, documentId: string | null | undefined): DocumentTarget | undefined {
  if (documentType === "OPERATION_PERIOD") return { to: "/volume-records", search: { tab: "periods" } };
  if (!documentType || !documentId) return undefined;
  if (documentType === "CUSTOMER") return { to: "/customers", search: { id: documentId } };
  if (documentType === "CONTRACT") return { to: "/contracts", search: { id: documentId } };
  if (documentType === "PRICE_LIST") return { to: "/price-lists", search: { versionId: documentId } };
  if (documentType === "ADDENDUM") return { to: "/addenda", search: { id: documentId } };
  if (documentType === "PAYMENT_STATEMENT") return { to: "/payment-statements", search: { id: documentId } };
  return undefined;
}

export function auditRecordTarget(
  entityType: string,
  entityId: string,
  entityNo?: string | null,
  changes?: Record<string, unknown> | null,
): DocumentTarget | undefined {
  if (entityType === "PRICE_LIST") return { to: "/price-lists", search: { id: entityId } };
  if (entityType === "PRICE_LIST_VERSION") return { to: "/price-lists", search: { versionId: entityId } };
  if (entityType === "VOLUME_RECORD") return { to: "/volume-records", search: entityNo ? { q: entityNo } : {} };
  if (entityType === "WORKFLOW_INSTANCE" || entityType === "WORKFLOW_STEP") {
    const documentType = typeof changes?.documentType === "string" ? changes.documentType : undefined;
    const documentId = typeof changes?.documentId === "string" ? changes.documentId : undefined;
    return documentTarget(documentType, documentId);
  }
  if (entityType === "WORKFLOW_DEFINITION") return { to: "/admin/workflows", search: {} };
  if (entityType === "USER") return { to: "/admin/users", search: {} };
  if (entityType === "ROLE") return { to: "/admin/roles", search: {} };
  return documentTarget(entityType, entityId);
}
