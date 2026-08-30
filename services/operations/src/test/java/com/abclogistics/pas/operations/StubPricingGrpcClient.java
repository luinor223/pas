package com.abclogistics.pas.operations;

import com.abclogistics.pas.operations.grpc.PricingGrpcClient;
import com.abclogistics.pas.pricing.grpc.GetServiceItemResponse;
import com.abclogistics.pas.common.error.NotFoundException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StubPricingGrpcClient extends PricingGrpcClient {

    private final Map<String, GetServiceItemResponse> items = new ConcurrentHashMap<>();
    private boolean failNotFound = false;

    public StubPricingGrpcClient() {
        super();
        // default: known service codes
        register("CONT_LIFT", "Container lift on/off", "TEU", true);
        register("STORAGE", "Storage beyond free time", "day", true);
        register("LASHING", "Lashing & securing", "TEU", true);
        register("REEFER", "Reefer monitoring", "day", true);
        register("DOC_HANDLING", "Documentation handling", "set", true);
        register("WEIGHING", "Weighing (VGM)", "TEU", true);
    }

    private void register(String code, String name, String unit, boolean active) {
        items.put(code, GetServiceItemResponse.newBuilder()
                .setCode(code).setName(name).setUnit(unit).setIsActive(active).build());
    }

    @Override
    public GetServiceItemResponse getServiceItem(String code) {
        GetServiceItemResponse resp = items.get(code);
        if (resp == null) {
            throw new NotFoundException("Service item not found: " + code);
        }
        if (failNotFound) throw new NotFoundException("Service item not found: " + code);
        return resp;
    }

    public void setServiceItem(String code, GetServiceItemResponse response) {
        items.put(code, response);
    }

    public void remove(String code) {
        items.remove(code);
    }

    public void clear() {
        items.clear();
    }
}
