import { useEffect } from "react";

export function useRecoverOutOfRangePage({
  ready,
  page,
  totalPages,
  totalItems,
  recover,
}: {
  ready: boolean;
  page: number;
  totalPages: number;
  totalItems: number;
  recover: () => void;
}) {
  useEffect(() => {
    if (ready && page > 0 && totalItems > 0 && page >= totalPages) recover();
  }, [page, ready, recover, totalItems, totalPages]);
}
