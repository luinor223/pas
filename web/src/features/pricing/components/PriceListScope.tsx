import { useQuery } from "@tanstack/react-query";
import { contractQuery, customerQuery } from "@/features/contract/hooks/contractQueries";
import { humanize } from "@/shared/lib/text";
import type { PriceListResponse } from "../types/pricingTypes";
import type { ContractResponse, CustomerResponse } from "@/features/contract/types/contractTypes";

export function PriceListScope({ priceList, compact = false, contract: suppliedContract, customer: suppliedCustomer, resolveDetails = true }: {
  priceList: PriceListResponse;
  compact?: boolean;
  contract?: ContractResponse;
  customer?: CustomerResponse;
  resolveDetails?: boolean;
}) {
  const contractQueryResult = useQuery({ ...contractQuery(priceList.contractId ?? ""), enabled: resolveDetails && Boolean(priceList.contractId) && !suppliedContract });
  const customerQueryResult = useQuery({ ...customerQuery(priceList.customerId ?? ""), enabled: resolveDetails && Boolean(priceList.customerId) && !suppliedCustomer });
  const contract = suppliedContract ?? contractQueryResult.data;
  const customer = suppliedCustomer ?? customerQueryResult.data;

  if (priceList.contractId) {
    return (
      <div>
        <div className="font-medium">{contract ? `Contract ${contract.contractNo}` : "Contract"}</div>
        {!compact && <div className="text-xs text-muted-foreground">{contract?.customerName ?? "Loading details..."}</div>}
      </div>
    );
  }
  if (priceList.customerId) {
    return (
      <div>
        <div className="font-medium">{customer?.name ?? "Customer"}</div>
        {!compact && priceList.serviceGroup && <div className="text-xs text-muted-foreground">{humanize(priceList.serviceGroup)}</div>}
      </div>
    );
  }
  return <span className="font-medium">{priceList.serviceGroup ? humanize(priceList.serviceGroup) : "General"}</span>;
}
