// Workflow (workflow-service) DTOs, mirroring WorkflowDefinitionController + DocumentTypeController.
export type StepDefinitionDto = {
  id: string;
  stepOrder: number;
  name: string;
  approverRole: string;
  slaHours: number;
};

export type WorkflowDefinitionResponse = {
  id: string;
  documentTypeCode: string;
  documentTypeName: string;
  versionNo: number;
  name: string;
  active: boolean;
  createdAt: string;
  createdBy: string;
  steps: StepDefinitionDto[];
};

export type CreateDefinitionRequest = {
  documentTypeCode: string;
  name: string;
};

export type StepRequest = {
  name: string;
  approverRole: string;
  slaHours: number;
};

export type DocumentTypeResponse = {
  id: string;
  code: string;
  name: string;
  numberPrefix: string;
  esignEnabled: boolean;
  esignProvider: string | null;
};

export type UpdateDocumentTypeRequest = {
  name: string;
  esignEnabled: boolean;
  esignProvider: string | null;
};
