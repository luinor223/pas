package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.repository.CustomerCounterRepository;
import com.abclogistics.pas.contract.repository.DocumentCounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business-key generation (registry §2). Numbers are server-generated and never client-supplied.
 *
 * <p>Every method takes the counter's row lock, so concurrent creates serialise on it rather than
 * racing to the same sequence and failing on a UNIQUE.
 */
@Service
public class DocumentNumberService {

    private final DocumentCounterRepository documentCounters;
    private final CustomerCounterRepository customerCounters;

    public DocumentNumberService(DocumentCounterRepository documentCounters,
                                 CustomerCounterRepository customerCounters) {
        this.documentCounters = documentCounters;
        this.customerCounters = customerCounters;
    }

    /** {@code CTR-{YYYY}-{seq}} or {@code ADD-{YYYY}-{seq}}, per type per year. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextDocumentNo(EntityType docType, int year) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** {@code CUS-{seq}} — no year segment, so the sequence runs unbroken across years. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextCustomerCode() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
