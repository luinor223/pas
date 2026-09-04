import { queryOptions } from "@tanstack/react-query";
import { workflowApi } from "../services/workflowApi";

export const documentTypesQuery = queryOptions({
  queryKey: ["document-types"],
  queryFn: () => workflowApi.listDocumentTypes(),
  staleTime: 5 * 60_000,
});

export const definitionsQuery = (documentTypeCode?: string) =>
  queryOptions({
    queryKey: ["workflow-definitions", documentTypeCode ?? "all"],
    queryFn: () => workflowApi.listDefinitions(documentTypeCode || undefined),
  });

export const definitionQuery = (id: string) =>
  queryOptions({
    queryKey: ["workflow-definitions", id],
    queryFn: () => workflowApi.getDefinition(id),
    enabled: !!id,
  });
