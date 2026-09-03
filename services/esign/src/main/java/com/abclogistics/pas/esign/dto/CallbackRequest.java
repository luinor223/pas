package com.abclogistics.pas.esign.dto;

import java.util.UUID;

public record CallbackRequest(
    String providerRef,
    String result,
    String error
) {}
