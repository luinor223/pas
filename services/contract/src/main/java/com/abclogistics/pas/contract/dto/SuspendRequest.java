package com.abclogistics.pas.contract.dto;

import jakarta.validation.constraints.NotBlank;

public record SuspendRequest(@NotBlank String reason) { }
