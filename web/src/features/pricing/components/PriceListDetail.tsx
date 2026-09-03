import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Send } from "lucide-react";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { StatusBadge } from "@/shared/components/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { getApiErrorMessage } from "@/shared/api/errors";
import { formatDate, formatMoney } from "@/shared/lib/format";
import { DetailBackButton } from "@/shared/components/detail-back-link";
import { priceListVersionQuery, priceListVersionsQuery, serviceItemsQuery } from "../hooks/pricingQueries";
import { pricingApi } from "../services/pricingApi";
import type { PriceLineInput, PriceListResponse, PriceListVersionResponse } from "../types/pricingTypes";
import { PriceListScope } from "./PriceListScope";

export function PriceListDetail({
  priceList, canWrite, initialVersionId, onVersionChange,
}: {
  priceList: PriceListResponse;
  canWrite: boolean;
  initialVersionId: string;
  onVersionChange: (versionId: string) => void;
}) {
  const [openCreateVersion, setOpenCreateVersion] = useState(false);
  const versionsQuery = useQuery(priceListVersionsQuery(priceList.id));
  const versions = versionsQuery.data ?? [];
  const selectedVersionId = versions.some((version) => version.id === initialVersionId)
    ? initialVersionId
    : versions.at(-1)?.id ?? "";

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <div className="flex items-center gap-2"><DetailBackButton to="/price-lists" label="Back to price lists" /><CardTitle>{priceList.priceListNo}</CardTitle></div>
            <div className="mt-2 text-sm"><PriceListScope priceList={priceList} /></div>
            {priceList.note && <p className="mt-2 text-sm text-muted-foreground">{priceList.note}</p>}
          </div>
          {canWrite && <Button onClick={() => setOpenCreateVersion(true)}><Plus size={16} className="mr-1.5" /> New version</Button>}
        </CardHeader>
        <CardContent>
          <div className="mb-3">
            <h3 className="font-semibold">Version history</h3>
            <p className="text-xs text-muted-foreground">Approved prices remain read-only. Create a new version when prices need to change.</p>
          </div>
          {versionsQuery.isLoading ? (
            <p className="py-6 text-sm text-muted-foreground">Loading versions...</p>
          ) : versionsQuery.isError ? (
            <p role="alert" className="text-sm text-destructive">{getApiErrorMessage(versionsQuery.error, "Could not load version history")}</p>
          ) : versions.length === 0 ? (
            <div className="rounded-md border border-dashed p-8 text-center">
              <p className="text-sm font-medium">No versions yet</p>
              <p className="mt-1 text-xs text-muted-foreground">Create the first version to set its effective dates and service prices.</p>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>VERSION</TableHead>
                    <TableHead>VALID FROM</TableHead>
                    <TableHead>VALID TO</TableHead>
                    <TableHead>STATUS</TableHead>
                    <TableHead className="text-right">ACTION</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {[...versions].reverse().map((version) => (
                    <TableRow key={version.id} className={selectedVersionId === version.id ? "bg-blue-50/60" : undefined}>
                      <TableCell className="font-semibold">Version {version.versionNo}</TableCell>
                      <TableCell>{formatDate(version.validFrom)}</TableCell>
                      <TableCell>{formatDate(version.validTo)}</TableCell>
                      <TableCell><StatusBadge status={version.status} /></TableCell>
                      <TableCell className="text-right">
                        <Button size="sm" variant={selectedVersionId === version.id ? "secondary" : "outline"} onClick={() => onVersionChange(version.id)}>
                          {version.status === "DRAFT" && canWrite ? "Edit prices" : "View prices"}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      {selectedVersionId && <VersionPrices key={selectedVersionId} priceList={priceList} versionId={selectedVersionId} canWrite={canWrite} />}

      {openCreateVersion && (
        <CreateVersionDialog
          priceList={priceList}
          versions={versions}
          onClose={() => setOpenCreateVersion(false)}
          onCreated={(version) => { setOpenCreateVersion(false); onVersionChange(version.id); }}
        />
      )}
    </div>
  );
}

function VersionPrices({ priceList, versionId, canWrite }: { priceList: PriceListResponse; versionId: string; canWrite: boolean }) {
  const queryClient = useQueryClient();
  const detailQuery = useQuery(priceListVersionQuery(priceList.id, versionId));
  const itemsQuery = useQuery(serviceItemsQuery);
  const [editedPrices, setEditedPrices] = useState<Record<string, string> | null>(null);
  const [validationError, setValidationError] = useState("");
  const [confirmSubmit, setConfirmSubmit] = useState(false);

  const savedPrices = Object.fromEntries((detailQuery.data?.lines ?? []).map((line) => [line.serviceCode, String(line.unitPrice)]));
  const prices = editedPrices ?? savedPrices;
  const dirty = normalizePrices(prices) !== normalizePrices(savedPrices);

  const saveMutation = useMutation({
    mutationFn: (lines: PriceLineInput[]) => pricingApi.replaceLines(priceList.id, versionId, lines),
    onSuccess: () => {
      setEditedPrices(null);
      setValidationError("");
      queryClient.invalidateQueries({ queryKey: ["price-list-version", priceList.id, versionId] });
    },
  });
  const submitMutation = useMutation({
    mutationFn: () => pricingApi.submitVersion(priceList.id, versionId),
    onSuccess: () => {
      setConfirmSubmit(false);
      queryClient.invalidateQueries({ queryKey: ["price-list-versions", priceList.id] });
      queryClient.invalidateQueries({ queryKey: ["price-list-version", priceList.id, versionId] });
      queryClient.invalidateQueries({ queryKey: ["approval-inbox"] });
    },
  });
  const reviseMutation = useMutation({
    mutationFn: () => pricingApi.reviseVersion(priceList.id, versionId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["price-list-versions", priceList.id] });
      queryClient.invalidateQueries({ queryKey: ["price-list-version", priceList.id, versionId] });
    },
  });

  if (detailQuery.isLoading || itemsQuery.isLoading) {
    return <Card><CardContent className="p-6 text-sm text-muted-foreground">Loading prices...</CardContent></Card>;
  }
  if (detailQuery.isError || itemsQuery.isError || !detailQuery.data) {
    const error = detailQuery.error ?? itemsQuery.error;
    return <Card><CardContent className="p-6 text-sm text-destructive">{getApiErrorMessage(error, "Could not load this version")}</CardContent></Card>;
  }

  const { version, lines } = detailQuery.data;
  const editable = canWrite && version.status === "DRAFT";

  function savePrices() {
    const parsed = Object.entries(prices)
      .filter(([, value]) => value.trim() !== "")
      .map(([serviceCode, value]) => ({ serviceCode, unitPrice: Number(value) }));
    if (parsed.length === 0) {
      setValidationError("Enter a price for at least one service.");
      return;
    }
    if (parsed.some((line) => !Number.isFinite(line.unitPrice) || line.unitPrice < 0)) {
      setValidationError("Unit prices must be valid numbers and cannot be negative.");
      return;
    }
    saveMutation.mutate(parsed);
  }

  const actionError = saveMutation.error ?? submitMutation.error ?? reviseMutation.error;

  return (
    <Card>
      <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <CardTitle>Version {version.versionNo} prices</CardTitle>
            <StatusBadge status={version.status} />
          </div>
          <p className="mt-2 text-sm text-muted-foreground">
            Valid {formatDate(version.validFrom)}–{formatDate(version.validTo)}
          </p>
        </div>
        {canWrite && version.status === "REJECTED" && (
          <Button variant="outline" disabled={reviseMutation.isPending} onClick={() => reviseMutation.mutate()}>
            {reviseMutation.isPending ? "Preparing..." : "Revise this version"}
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-4">
        {!editable && (
          <div className="rounded-md bg-muted px-3 py-2 text-sm text-muted-foreground">
            {version.status === "REJECTED"
              ? "This version was rejected. Choose “Revise this version” to return it to draft and update its prices."
              : "This version is read-only. Create a new version to change prices without altering historical billing data."}
          </div>
        )}

        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>SERVICE</TableHead>
                <TableHead>UNIT</TableHead>
                <TableHead className="w-56">UNIT PRICE</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {editable ? (itemsQuery.data ?? []).map((item) => (
                <TableRow key={item.code}>
                  <TableCell>
                    <div className="font-medium">{item.name}</div>
                  </TableCell>
                  <TableCell>{item.unit}</TableCell>
                  <TableCell>
                    <Input
                      type="number"
                      min="0"
                      step="0.01"
                      inputMode="decimal"
                      aria-label={`Unit price for ${item.name}`}
                      placeholder="Not included"
                      value={prices[item.code] ?? ""}
                      onChange={(event) => {
                        setEditedPrices((current) => ({ ...(current ?? savedPrices), [item.code]: event.target.value }));
                        setValidationError("");
                      }}
                    />
                  </TableCell>
                </TableRow>
              )) : lines.length === 0 ? (
                <TableRow><TableCell colSpan={3} className="py-8 text-center text-muted-foreground">No service prices were added to this version.</TableCell></TableRow>
              ) : lines.map((line) => (
                <TableRow key={line.serviceCode}>
                  <TableCell>
                    <div className="font-medium">{line.serviceName}</div>
                  </TableCell>
                  <TableCell>{line.unit}</TableCell>
                  <TableCell className="font-medium tabular-nums">{formatMoney(line.unitPrice, "VND")}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>

        {(validationError || actionError) && (
          <p role="alert" className="text-sm text-destructive">
            {validationError || getApiErrorMessage(actionError, "Could not update this price-list version")}
          </p>
        )}

        {editable && (
          <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
            <p className="text-xs text-muted-foreground">Leave a price blank when that service is not covered by this version.</p>
            <div className="flex gap-2">
              <Button variant="outline" disabled={!dirty || saveMutation.isPending || submitMutation.isPending} onClick={savePrices}>
                {saveMutation.isPending ? "Saving..." : "Save prices"}
              </Button>
              <Button
                disabled={dirty || lines.length === 0 || saveMutation.isPending || submitMutation.isPending}
                title={dirty ? "Save your price changes before submitting" : lines.length === 0 ? "Add and save at least one service price first" : undefined}
                onClick={() => { submitMutation.reset(); setConfirmSubmit(true); }}
              >
                <Send size={15} className="mr-1.5" /> Submit for approval
              </Button>
            </div>
          </div>
        )}
      </CardContent>

      <Dialog open={confirmSubmit} onOpenChange={(open) => { if (!open && !submitMutation.isPending) setConfirmSubmit(false); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Submit {priceList.priceListNo} version {version.versionNo}?</DialogTitle>
          </DialogHeader>
          <div className="space-y-2 text-sm">
            <p>This sends the version to the approval workflow.</p>
            <p className="text-muted-foreground">Prices become read-only after submission. If prices need to change later, create a new version.</p>
            {submitMutation.isError && <p role="alert" className="text-destructive">{getApiErrorMessage(submitMutation.error, "Could not submit this version")}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" disabled={submitMutation.isPending} onClick={() => setConfirmSubmit(false)}>Keep editing</Button>
            <Button disabled={submitMutation.isPending} onClick={() => submitMutation.mutate()}>
              {submitMutation.isPending ? "Submitting..." : "Submit for approval"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}

function CreateVersionDialog({
  priceList, versions, onClose, onCreated,
}: {
  priceList: PriceListResponse;
  versions: PriceListVersionResponse[];
  onClose: () => void;
  onCreated: (version: PriceListVersionResponse) => void;
}) {
  const queryClient = useQueryClient();
  const today = new Date().toISOString().slice(0, 10);
  const [validFrom, setValidFrom] = useState(today);
  const [validTo, setValidTo] = useState("");
  const invalidRange = Boolean(validFrom && validTo && validFrom > validTo);
  const createMutation = useMutation({
    mutationFn: () => pricingApi.createVersion(priceList.id, { validFrom, validTo, addendumId: null }),
    onSuccess: (version) => {
      queryClient.invalidateQueries({ queryKey: ["price-list-versions", priceList.id] });
      onCreated(version);
    },
  });

  return (
    <Dialog open onOpenChange={(open) => { if (!open && !createMutation.isPending) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create version {versions.length + 1}</DialogTitle>
          <p className="text-sm text-muted-foreground">Set the period when these prices should apply. You will add service prices next.</p>
        </DialogHeader>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <Label>Valid from *</Label>
            <Input type="date" value={validFrom} onChange={(event) => setValidFrom(event.target.value)} />
          </div>
          <div>
            <Label>Valid to *</Label>
            <Input type="date" min={validFrom} value={validTo} onChange={(event) => setValidTo(event.target.value)} />
          </div>
        </div>
        <p className="mt-3 text-xs text-muted-foreground">A submitted version cannot overlap another approved or effective version for the same scope.</p>
        {invalidRange && <p role="alert" className="mt-2 text-sm text-destructive">Valid to must be on or after Valid from.</p>}
        {createMutation.isError && <p role="alert" className="mt-2 text-sm text-destructive">{getApiErrorMessage(createMutation.error, "Could not create this version")}</p>}
        <DialogFooter>
          <Button variant="outline" disabled={createMutation.isPending} onClick={onClose}>Cancel</Button>
          <Button disabled={!validFrom || !validTo || invalidRange || createMutation.isPending} onClick={() => createMutation.mutate()}>
            {createMutation.isPending ? "Creating..." : "Create version"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function normalizePrices(prices: Record<string, string>) {
  return JSON.stringify(Object.entries(prices)
    .filter(([, value]) => value.trim() !== "")
    .map(([code, value]) => [code, Number(value)])
    .sort(([left], [right]) => String(left).localeCompare(String(right))));
}
