package com.abclogistics.pas.identity.support;

import com.abclogistics.pas.common.api.ApiResponse;
import com.abclogistics.pas.identity.dto.LoginResponse;
import com.abclogistics.pas.identity.dto.RoleResponse;
import com.abclogistics.pas.identity.dto.TokenResponse;
import com.abclogistics.pas.identity.dto.UserResponse;
import org.springframework.core.ParameterizedTypeReference;

// Response bodies ride the shared {data, meta} envelope; these refs decode data into its DTO.
public final class Envelopes {
    private Envelopes() { }

    public static final ParameterizedTypeReference<ApiResponse<LoginResponse>> LOGIN = new ParameterizedTypeReference<>() { };
    public static final ParameterizedTypeReference<ApiResponse<TokenResponse>> TOKEN = new ParameterizedTypeReference<>() { };
    public static final ParameterizedTypeReference<ApiResponse<UserResponse>> USER = new ParameterizedTypeReference<>() { };
    public static final ParameterizedTypeReference<ApiResponse<UserResponse[]>> USER_ARRAY = new ParameterizedTypeReference<>() { };
    public static final ParameterizedTypeReference<ApiResponse<RoleResponse>> ROLE = new ParameterizedTypeReference<>() { };
}
