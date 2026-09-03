import { useNavigate, useSearch } from "@tanstack/react-router";
import { VolumeRecordsPage, type VolumeRecordsTabValue } from "./VolumeRecordsPage";

export function VolumeRecordsRoute() {
  const { tab } = useSearch({ from: "/volume-records" });
  const navigate = useNavigate({ from: "/volume-records" });

  function changeTab(next: VolumeRecordsTabValue) {
    navigate({ search: { tab: next === "PERIODS" ? "periods" : undefined } });
  }

  return <VolumeRecordsPage tab={tab === "periods" ? "PERIODS" : "RECORDS"} onTabChange={changeTab} />;
}
