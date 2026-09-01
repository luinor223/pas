package com.abclogistics.pas.billing.dto;

import java.util.List;

public record PageResponse<T>(
    List<T> data,
    Meta meta
) {}
