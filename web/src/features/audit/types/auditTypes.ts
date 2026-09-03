// Audit trail DTO mirroring services/audit AuditRecordController.

export type AuditRecordResponse = {
  id: string;
  sourceService: string;
  entityType: string;
  entityId: string;
  entityNo: string | null;
  action: string;
  actorId: string | null;
  actorName: string | null;
  actorDepartment: string | null;
  beforeStatus: string | null;
  afterStatus: string | null;
  changes: Record<string, unknown> | null;
  note: string | null;
  ipAddress: string | null;
  occurredAt: string;
};
