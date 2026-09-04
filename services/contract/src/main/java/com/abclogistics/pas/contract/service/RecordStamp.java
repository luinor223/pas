package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.ActorStamped;

/** Copies the acting user onto a row's record-metadata columns; a system actor is stamped as the SYSTEM principal. */
final class RecordStamp {

    private RecordStamp() { }

    static void creator(ActorStamped entity) {
        entity.setCreatedBy(SecurityUtils.currentUserIdOrSystem());
        entity.setCreatedByName(SecurityUtils.currentUserNameOrSystem());
        SecurityUtils.currentUser().ifPresent(user -> entity.setCreatedByDepartment(user.department()));
    }

    static void editor(ActorStamped entity) {
        entity.setUpdatedBy(SecurityUtils.currentUserIdOrSystem());
        entity.setUpdatedByName(SecurityUtils.currentUserNameOrSystem());
    }
}
