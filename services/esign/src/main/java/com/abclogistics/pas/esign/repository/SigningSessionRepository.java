package com.abclogistics.pas.esign.repository;

import com.abclogistics.pas.esign.domain.SigningSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SigningSessionRepository extends JpaRepository<SigningSession, UUID>, JpaSpecificationExecutor<SigningSession> {

    @Query(value = "SELECT nextval('esign.signing_session_no_seq')", nativeQuery = true)
    long nextSessionNoSeq();

    Optional<SigningSession> findBySessionNo(String sessionNo);

    Optional<SigningSession> findByIdempotencyKey(UUID idempotencyKey);

    @Query("SELECT s FROM SigningSession s WHERE s.documentTypeCode = :docType AND s.documentId = :docId " +
           "AND s.status IN ('PENDING_SEND', 'SIGNING') ORDER BY s.createdAt DESC")
    List<SigningSession> findActiveByDocument(@Param("docType") String docType, @Param("docId") UUID docId);

    @Query("SELECT s FROM SigningSession s WHERE s.documentTypeCode = :docType AND s.documentId = :docId " +
           "ORDER BY s.createdAt DESC")
    List<SigningSession> findAllByDocument(@Param("docType") String docType, @Param("docId") UUID docId);

    @Query("SELECT s FROM SigningSession s WHERE s.status IN ('PENDING_SEND', 'SIGNING') ORDER BY s.createdAt ASC")
    List<SigningSession> findAllPendingOrSigning();

    @Query("SELECT s FROM SigningSession s WHERE s.status = :status ORDER BY s.createdAt DESC")
    Page<SigningSession> findByStatus(@Param("status") SigningSession.SessionStatus status, Pageable pageable);

    boolean existsByDocumentTypeCodeAndDocumentIdAndStatusIn(String documentTypeCode, UUID documentId,
                                                              List<SigningSession.SessionStatus> statuses);
}
