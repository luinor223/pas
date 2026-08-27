package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.domain.Attachment;
import com.abclogistics.pas.contract.domain.EntityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Attachment metadata + volume-backed storage. Bytes go to the mounted path configured by
 * {@code contract.attachment-storage-path}; only metadata is persisted.
 */
@Service
public class AttachmentService {

    @Transactional(readOnly = true)
    public List<Attachment> list(EntityType ownerType, UUID ownerId) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** Allowed only while the owner is editable (CTR-01) — an approved document's file set is fixed. */
    @Transactional
    public Attachment upload(EntityType ownerType, UUID ownerId, MultipartFile file) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public void delete(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
