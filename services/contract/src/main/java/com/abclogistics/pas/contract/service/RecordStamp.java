package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.common.security.SystemActor;
import com.abclogistics.pas.contract.domain.ActorStamped;

/** Copies the acting user onto a row's record-metadata columns; a system actor is stamped as the SYSTEM principal. */
final class RecordStamp {

    private RecordStamp() { }

    static void creator(ActorStamped entity) {
        SecurityUtils.currentUser().ifPresentOrElse(user -> {
            entity.setCreatedBy(user.userId());
            entity.setCreatedByName(user.fullName());
            entity.setCreatedByDepartment(user.department());
        }, () -> {
            entity.setCreatedBy(SystemActor.ID);
            entity.setCreatedByName(SystemActor.NAME);
        });
    }

    static void editor(ActorStamped entity) {
        SecurityUtils.currentUser().ifPresentOrElse(user -> {
            entity.setUpdatedBy(user.userId());
            entity.setUpdatedByName(user.fullName());
        }, () -> {
            entity.setUpdatedBy(SystemActor.ID);
            entity.setUpdatedByName(SystemActor.NAME);
        });
    }
}
