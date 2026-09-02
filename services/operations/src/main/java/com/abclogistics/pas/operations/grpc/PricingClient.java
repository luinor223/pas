package com.abclogistics.pas.operations.grpc;

import com.abclogistics.pas.pricing.grpc.GetServiceItemResponse;

public interface PricingClient {
    GetServiceItemResponse getServiceItem(String code);
}
