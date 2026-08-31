package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.Attachment;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        String ownerType,
        UUID ownerId,
        String fileName,
        String contentType,
        Long sizeBytes,
        Instant uploadedAt
) {
    public static AttachmentResponse of(Attachment a) {
        return new AttachmentResponse(
                a.getId(), a.getOwnerType().name(), a.getOwnerId(), a.getFileName(),
                a.getContentType(), a.getSizeBytes(), a.getUploadedAt());
    }
}
