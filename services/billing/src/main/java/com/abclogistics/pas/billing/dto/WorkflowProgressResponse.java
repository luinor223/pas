package com.abclogistics.pas.billing.dto;

public record WorkflowProgressResponse(
    String statementNo,
    Object workflowInstance
) {}
