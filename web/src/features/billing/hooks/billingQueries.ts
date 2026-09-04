import { queryOptions } from "@tanstack/react-query";
import { billingApi, type StatementListParams } from "../services/billingApi";

export const statementsQuery = (params: StatementListParams = {}) =>
  queryOptions({ queryKey: ["payment-statements", params], queryFn: () => billingApi.listStatements(params) });

export const statementQuery = (statementNo: string) =>
  queryOptions({
    queryKey: ["payment-statement", statementNo],
    queryFn: () => billingApi.getStatement(statementNo),
    enabled: !!statementNo,
  });

export const statementWorkflowQuery = (statementNo: string) =>
  queryOptions({
    queryKey: ["payment-statement-workflow", statementNo],
    queryFn: () => billingApi.workflowProgress(statementNo),
    enabled: !!statementNo,
  });
