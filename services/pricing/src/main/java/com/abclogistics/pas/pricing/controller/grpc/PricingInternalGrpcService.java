package com.abclogistics.pas.pricing.controller.grpc;

import com.abclogistics.pas.common.error.GrpcStatusMapper;
import com.abclogistics.pas.pricing.domain.ServiceItem;
import com.abclogistics.pas.pricing.dto.PriceLineView;
import com.abclogistics.pas.pricing.grpc.*;
import com.abclogistics.pas.pricing.service.EffectivePriceService;
import com.abclogistics.pas.pricing.service.EffectivePriceService.ResolvedPriceList;
import com.abclogistics.pas.pricing.service.ServiceCatalogService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@GrpcService
public class PricingInternalGrpcService extends PricingInternalGrpc.PricingInternalImplBase {

    private final ServiceCatalogService catalog;
    private final EffectivePriceService effective;

    public PricingInternalGrpcService(ServiceCatalogService catalog, EffectivePriceService effective) {
        this.catalog = catalog;
        this.effective = effective;
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
            responseObserver.onError(GrpcStatusMapper.toStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /** Effective price list resolution (billing → pricing). Historical, precedence CONTRACT >
     *  CUSTOMER+GROUP > CUSTOMER; NOT_FOUND when no version's validity holds the date. */
    @Override
    @Transactional(readOnly = true)
    public void getEffectivePriceList(GetEffectivePriceListRequest request,
                                      StreamObserver<GetEffectivePriceListResponse> responseObserver) {
        try {
            LocalDate date = LocalDate.parse(request.getDate());
            Optional<ResolvedPriceList> resolved = effective.resolve(
                    uuidOrNull(request.getContractId()), uuidOrNull(request.getCustomerId()),
                    blankToNull(request.getServiceGroup()), date);
            if (resolved.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("No effective price list for the given scope and date").asRuntimeException());
                return;
            }
            responseObserver.onNext(toResponse(resolved.get()));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(GrpcStatusMapper.toStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private static GetEffectivePriceListResponse toResponse(ResolvedPriceList r) {
        GetEffectivePriceListResponse.Builder b = GetEffectivePriceListResponse.newBuilder()
                .setPriceListNo(r.priceListNo())
                .setVersionNo(r.versionNo())
                .setVersionId(r.versionId().toString())
                .setValidFrom(r.validFrom().toString())
                .setValidTo(r.validTo().toString());
        for (PriceLineView line : r.lines()) {
            b.addLines(PriceLine.newBuilder()
                    .setServiceCode(line.serviceCode())
                    .setServiceName(line.serviceName())
                    .setUnit(line.unit())
                    .setUnitPrice(line.unitPrice().doubleValue())
                    .build());
        }
        return b.build();
    }

    private static UUID uuidOrNull(String s) {
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

}
