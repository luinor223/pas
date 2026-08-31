package com.abclogistics.pas.pricing.grpc;

import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.pricing.domain.ServiceItem;
import com.abclogistics.pas.pricing.service.ServiceCatalogService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

@GrpcService
public class PricingInternalGrpcService extends PricingInternalGrpc.PricingInternalImplBase {

    private final ServiceCatalogService catalog;

    public PricingInternalGrpcService(ServiceCatalogService catalog) {
        this.catalog = catalog;
    }

    /** Validates and supplies the D7 snapshot at volume entry (operations → pricing, registry §5).
     *  NOT_FOUND ⇒ the caller rejects the entry. */
    @Override
    @Transactional(readOnly = true)
    public void getServiceItem(GetServiceItemRequest request, StreamObserver<GetServiceItemResponse> responseObserver) {
        try {
            ServiceItem item = catalog.getByCode(request.getCode());
            responseObserver.onNext(GetServiceItemResponse.newBuilder()
                    .setCode(item.getCode())
                    .setName(item.getName())
                    .setUnit(item.getUnit())
                    .setIsActive(item.isActive())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(mapToStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /** Effective price list resolution (billing → pricing). Arrives with the versioning slice. */
    @Override
    public void getEffectivePriceList(GetEffectivePriceListRequest request,
                                      StreamObserver<GetEffectivePriceListResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED
                .withDescription("GetEffectivePriceList arrives with price-list versioning")
                .asRuntimeException());
    }

    private Status mapToStatus(Exception e) {
        if (e instanceof IllegalArgumentException) return Status.INVALID_ARGUMENT;
        if (e instanceof NotFoundException) return Status.NOT_FOUND;
        return Status.INTERNAL;
    }
}
