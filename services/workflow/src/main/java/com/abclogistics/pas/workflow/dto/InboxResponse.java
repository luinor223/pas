package com.abclogistics.pas.workflow.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InboxResponse(List<InboxItem> items) {
    public record InboxItem(
            UUID instanceId,
            UUID stepInstanceId,
            String documentTypeCode,
            UUID documentId,
            String documentNo,
            String customerName,
            String status,
            String priority,
            int currentStepOrder,
            String currentStepName,
            String currentStepRole,
            Instant stepActivatedAt,
            Instant createdAt,
            UUID requestedBy,
            String requestedByName
    ) {}
}
