package com.abclogistics.pas.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "document_counter", schema = "contract")
@IdClass(DocumentCounter.Key.class)
public class DocumentCounter {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, updatable = false)
    private EntityType docType;

    @Id
    @Column(name = "year", nullable = false, updatable = false)
    private int year;

    @Column(name = "next_seq", nullable = false)
    private long nextSeq;

    protected DocumentCounter() { } // JPA

    public static DocumentCounter start(EntityType docType, int year) {
        DocumentCounter c = new DocumentCounter();
        c.docType = docType;
        c.year = year;
        c.nextSeq = 1L;
        return c;
    }

    public long allocate() {
        return nextSeq++;
    }

    public EntityType getDocType() { return docType; }
    public int getYear() { return year; }
    public long getNextSeq() { return nextSeq; }

    public static class Key implements Serializable {
        private EntityType docType;
        private int year;

        public Key() { }

        public Key(EntityType docType, int year) {
            this.docType = docType;
            this.year = year;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return year == key.year && docType == key.docType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(docType, year);
        }
    }
}
