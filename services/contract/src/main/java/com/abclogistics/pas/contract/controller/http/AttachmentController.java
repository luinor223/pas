package com.abclogistics.pas.contract.controller.http;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.dto.AttachmentResponse;
import com.abclogistics.pas.contract.service.AttachmentService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * No {@code @PreAuthorize} here on purpose: the permission depends on the attachment's owner type
 * ({@code contract:read} vs {@code addendum:read}), which on download and delete is only known once
 * the row is read. {@code AttachmentService} checks it on every operation instead.
 */
@RestController
@RequestMapping("/attachments")
public class AttachmentController {

    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @GetMapping
    public List<AttachmentResponse> list(@RequestParam EntityType ownerType,
                                         @RequestParam UUID ownerId) {
        return attachments.list(ownerType, ownerId).stream().map(AttachmentResponse::of).toList();
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@RequestParam EntityType ownerType,
                                     @RequestParam UUID ownerId,
                                     @RequestPart("file") MultipartFile file) {
        return AttachmentResponse.of(attachments.upload(ownerType, ownerId, file));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        AttachmentService.AttachmentContent content = attachments.download(id);
        // re-checked: a row written before that check must not make its own file undownloadable
        MediaType mediaType = MediaType.parseMediaType(
                AttachmentService.safeContentType(content.metadata().getContentType()));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.metadata().getFileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(content.resource());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        attachments.delete(id);
    }
}
