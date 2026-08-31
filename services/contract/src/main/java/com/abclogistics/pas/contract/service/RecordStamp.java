package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.ActorStamped;

/** Copies the acting user onto a row's record-metadata columns; a system actor leaves them null. */
final class RecordStamp {

    private RecordStamp() { }

    static void creator(ActorStamped entity) {
        SecurityUtils.currentUser().ifPresent(user -> {
            entity.setCreatedBy(user.userId());
            entity.setCreatedByName(user.fullName());
            entity.setCreatedByDepartment(user.department());
        });
    }

    static void editor(ActorStamped entity) {
        SecurityUtils.currentUser().ifPresent(user -> {
            entity.setUpdatedBy(user.userId());
            entity.setUpdatedByName(user.fullName());
        });
    }
}
