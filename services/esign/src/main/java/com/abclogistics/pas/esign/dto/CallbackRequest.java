package com.abclogistics.pas.esign.dto;


public record CallbackRequest(
    String providerRef,
    String result,
    String error
) {}
