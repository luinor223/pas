import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/shared/components/button";

type PaginationControlsProps = {
  page: number;
  totalPages: number;
  pageSize: number;
  totalItems: number;
  onPageChange: (page: number) => void;
};

export function PaginationControls({
  page,
  totalPages,
  pageSize,
  totalItems,
  onPageChange,
}: PaginationControlsProps) {
  const pages = Math.max(1, totalPages);
  const current = Math.min(Math.max(0, page), pages - 1);
  const first = totalItems === 0 ? 0 : current * pageSize + 1;
  const last = Math.min(totalItems, (current + 1) * pageSize);

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-3 text-xs text-muted-foreground">
      <span>{first}–{last} of {totalItems} · {pageSize} per page</span>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={current === 0}
          onClick={() => onPageChange(current - 1)}
          aria-label="Previous page"
        >
          <ChevronLeft size={15} />
          Previous
        </Button>
        <span className="min-w-20 text-center tabular-nums">Page {current + 1} of {pages}</span>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={current + 1 >= pages}
          onClick={() => onPageChange(current + 1)}
          aria-label="Next page"
        >
          Next
          <ChevronRight size={15} />
        </Button>
      </div>
    </div>
  );
}
