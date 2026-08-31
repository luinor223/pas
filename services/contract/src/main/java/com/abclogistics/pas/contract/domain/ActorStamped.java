package com.abclogistics.pas.contract.domain;

import java.util.UUID;

/** The who-touched-this columns a row carries beyond {@code BaseEntity}'s ids (D15). */
public interface ActorStamped {

    void setCreatedBy(UUID userId);

    void setCreatedByName(String name);

    void setCreatedByDepartment(String department);

    void setUpdatedBy(UUID userId);

    void setUpdatedByName(String name);
}
