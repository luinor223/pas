import { api } from "@/shared/api/client";
import type {
  CreateDefinitionRequest,
  DocumentTypeResponse,
  StepRequest,
  UpdateDocumentTypeRequest,
  WorkflowDefinitionResponse,
} from "../types/workflowTypes";

// Workflow-definition + document-type data layer (workflow-service).
export const workflowApi = {
  listDefinitions: (documentTypeCode?: string) =>
    api
      .get<WorkflowDefinitionResponse[]>(
        `/workflow-definitions${documentTypeCode ? `?documentTypeCode=${documentTypeCode}` : ""}`,
      )
      .then((r) => r.data),
  getDefinition: (id: string) =>
    api.get<WorkflowDefinitionResponse>(`/workflow-definitions/${id}`).then((r) => r.data),
  createDefinition: (data: CreateDefinitionRequest) =>
    api.post<WorkflowDefinitionResponse>("/workflow-definitions", data).then((r) => r.data),
  updateSteps: (id: string, steps: StepRequest[]) =>
    api
      .put<WorkflowDefinitionResponse>(`/workflow-definitions/${id}/steps`, { steps })
      .then((r) => r.data),
  activateDefinition: (id: string) =>
    api.post<WorkflowDefinitionResponse>(`/workflow-definitions/${id}/activate`).then((r) => r.data),
  deleteDefinition: (id: string) => api.delete(`/workflow-definitions/${id}`).then((r) => r.data),

  listDocumentTypes: () =>
    api.get<DocumentTypeResponse[]>("/document-types").then((r) => r.data),
  updateDocumentType: (code: string, data: UpdateDocumentTypeRequest) =>
    api.put<DocumentTypeResponse>(`/document-types/${code}`, data).then((r) => r.data),
};
