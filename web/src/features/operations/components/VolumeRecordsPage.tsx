import { useQuery } from "@tanstack/react-query";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Forbidden } from "@/shared/components/Forbidden";
import { TabBar } from "@/shared/components/tab-bar";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { periodsQuery } from "../hooks/operationsQueries";
import { PeriodsTab } from "./PeriodsTab";
import { VolumeRecordsTab } from "./VolumeRecordsTab";

export type VolumeRecordsTabValue = "RECORDS" | "PERIODS";

export function VolumeRecordsPage({
  tab,
  onTabChange,
}: {
  tab: VolumeRecordsTabValue;
  onTabChange: (tab: VolumeRecordsTabValue) => void;
}) {
  const canRead = useHasPermission("volume:read");
  const canWrite = useHasPermission("volume:write");
  const canLock = useHasPermission("volume:lock_period");
  const canEditLocked = useHasPermission("volume:edit_locked");
  const periods = useQuery({ ...periodsQuery, enabled: canRead });

  if (!canRead) {
    return <Forbidden message="You do not have access to volume records. An administrator can grant it." />;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Volume records</CardTitle>
        <p className="text-sm text-muted-foreground">Record completed services by month. Lock a period when its volumes are ready for billing.</p>
      </CardHeader>
      <CardContent className="space-y-4">
        <TabBar
          id="volume-record-tabs"
          panelId="volume-record-panel"
          tabs={[
            { value: "RECORDS", label: "Volume records", count: periods.data?.reduce((total, period) => total + period.volumeCount, 0) },
            { value: "PERIODS", label: "Periods", count: periods.data?.length },
          ]}
          value={tab}
          onChange={onTabChange}
        />
        <div id="volume-record-panel" role="tabpanel" aria-labelledby={`volume-record-tabs-tab-${tab}`} tabIndex={0}>
          {tab === "RECORDS" ? (
            <VolumeRecordsTab
              periods={periods.data ?? []}
              periodsLoading={periods.isLoading}
              periodsError={periods.error}
              canWrite={canWrite}
              canEditLocked={canEditLocked}
            />
          ) : (
            <PeriodsTab
              periods={periods.data ?? []}
              loading={periods.isLoading}
              error={periods.error}
              canCreate={canWrite}
              canLock={canLock}
            />
          )}
        </div>
      </CardContent>
    </Card>
  );
}
