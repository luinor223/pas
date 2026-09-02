package com.abclogistics.pas.operations.grpc;

import com.abclogistics.pas.operations.domain.OperationPeriod;
import com.abclogistics.pas.operations.domain.PeriodCode;
import com.abclogistics.pas.operations.domain.VolumeRecord;
import com.abclogistics.pas.operations.repository.OperationPeriodRepository;
import com.abclogistics.pas.operations.repository.VolumeRecordRepository;
import com.abclogistics.pas.operations.grpc.ListVolumesRequest;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import com.abclogistics.pas.operations.grpc.OperationsInternalGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@GrpcService
public class OperationsInternalGrpcService extends OperationsInternalGrpc.OperationsInternalImplBase {

    private final OperationPeriodRepository periodRepo;
    private final VolumeRecordRepository volumeRepo;

    public OperationsInternalGrpcService(OperationPeriodRepository periodRepo, VolumeRecordRepository volumeRepo) {
        this.periodRepo = periodRepo;
        this.volumeRepo = volumeRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public void listVolumes(ListVolumesRequest request, StreamObserver<ListVolumesResponse> responseObserver) {
        try {
            String periodCode = request.getPeriodCode();
            String contractIdStr = request.getContractId();

            if (periodCode == null || periodCode.isBlank() || contractIdStr == null || contractIdStr.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("contract_id and period_code required").asRuntimeException());
                return;
            }
            // P0-3: validate period_code format early → INVALID_ARGUMENT, not NOT_FOUND
            if (!PeriodCode.isValid(periodCode)) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid period_code, expected YYYY-MM: " + periodCode).asRuntimeException());
                return;
            }

            UUID contractId;
            try {
                contractId = UUID.fromString(contractIdStr);
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid contract_id").asRuntimeException());
                return;
            }

            OperationPeriod period = periodRepo.findByPeriodCode(periodCode).orElse(null);
            if (period == null) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Period not found: " + periodCode).asRuntimeException());
                return;
            }

            List<VolumeRecord> volumes = volumeRepo.findByContractIdAndPeriod_PeriodCode(contractId, periodCode);

            ListVolumesResponse.Builder builder = ListVolumesResponse.newBuilder()
                    .setPeriodState(period.getStatus())
                    .setPeriodStart(period.getStartDate().format(DateTimeFormatter.ISO_DATE))
                    .setPeriodEnd(period.getEndDate().format(DateTimeFormatter.ISO_DATE));

            for (VolumeRecord v : volumes) {
                // 00-registry.md:93 defines quantity as double in proto; numeric(18,3) -> double loses exactness
                // but spec is double, so we round to 3 decimals explicitly (BigDecimal halved) before doubleValue()
                double qty = v.getQuantity().setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
                com.abclogistics.pas.operations.grpc.VolumeRecord grpcRecord = com.abclogistics.pas.operations.grpc.VolumeRecord.newBuilder()
                        .setRecordNo(v.getRecordNo())
                        .setServiceCode(v.getServiceCode())
                        .setUnit(v.getUnit())
                        .setQuantity(qty)
                        .setServiceName(v.getServiceName())
                        .build();
                builder.addVolumes(grpcRecord);
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        }
    }
}
