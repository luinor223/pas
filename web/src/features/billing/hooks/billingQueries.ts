import { queryOptions } from "@tanstack/react-query";
import { billingApi, type StatementListParams } from "../services/billingApi";

export const statementsQuery = (params: StatementListParams = {}) =>
  queryOptions({ queryKey: ["payment-statements", params], queryFn: () => billingApi.listStatements(params) });

export const statementQuery = (id: string) =>
  queryOptions({
    queryKey: ["payment-statement", id],
    queryFn: () => billingApi.getStatement(id),
    enabled: !!id,
  });

export const statementWorkflowQuery = (id: string) =>
  queryOptions({
    queryKey: ["payment-statement-workflow", id],
    queryFn: () => billingApi.workflowProgress(id),
    enabled: !!id,
  });
