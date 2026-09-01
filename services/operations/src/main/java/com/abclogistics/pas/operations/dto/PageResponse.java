package com.abclogistics.pas.operations.dto;

import java.util.List;

public record PageResponse<T>(
    List<T> data,
    Meta meta
) {}
