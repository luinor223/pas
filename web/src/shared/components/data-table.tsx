import { useState } from "react";
import {
  type ColumnDef,
  type SortingState,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  getPaginationRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { ChevronUp, ChevronDown, ChevronsUpDown } from "lucide-react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { Button } from "@/shared/components/button";
import { PaginationControls } from "@/shared/components/pagination-controls";

type DataTableProps<T> = {
  columns: ColumnDef<T>[];
  data: T[];
  pageSize?: number;
  emptyMessage?: string;
  rowClassName?: (row: T) => string | undefined;
  serverPagination?: {
    page: number;
    totalPages: number;
    totalItems: number;
    onPageChange: (page: number) => void;
  };
  /** Mouse convenience (audit-style): whole row opens details. Text selection never counts as a click. */
  onRowClick?: (row: T) => void;
};

export function DataTable<T>({ columns, data, pageSize = 10, emptyMessage = "No results", rowClassName, serverPagination, onRowClick }: DataTableProps<T>) {
  const [sorting, setSorting] = useState<SortingState>([]);
  const table = useReactTable({
    data,
    columns,
    state: {
      sorting,
      ...(serverPagination ? { pagination: { pageIndex: serverPagination.page, pageSize } } : {}),
    },
    onSortingChange: serverPagination ? undefined : setSorting,
    enableSorting: !serverPagination,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: serverPagination ? undefined : getSortedRowModel(),
    getPaginationRowModel: serverPagination ? undefined : getPaginationRowModel(),
    manualPagination: Boolean(serverPagination),
    pageCount: serverPagination?.totalPages,
    initialState: { pagination: { pageSize } },
  });

  const rows = table.getRowModel().rows;
  const { pageIndex } = table.getState().pagination;

  return (
    <div className="space-y-3">
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((hg) => (
            <TableRow key={hg.id}>
              {hg.headers.map((header) => {
                const sorted = header.column.getIsSorted();
                return (
                  <TableHead key={header.id}>
                    {header.isPlaceholder ? null : header.column.getCanSort() ? (
                      <button
                        type="button"
                        onClick={header.column.getToggleSortingHandler()}
                        className="inline-flex items-center gap-1 hover:text-foreground"
                      >
                        {flexRender(header.column.columnDef.header, header.getContext())}
                        {sorted === "asc" ? <ChevronUp size={14} /> : sorted === "desc" ? <ChevronDown size={14} /> : <ChevronsUpDown size={14} className="opacity-40" />}
                      </button>
                    ) : (
                      flexRender(header.column.columnDef.header, header.getContext())
                    )}
                  </TableHead>
                );
              })}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {rows.length === 0 ? (
            <TableRow>
              <TableCell colSpan={columns.length} className="text-center text-muted-foreground">{emptyMessage}</TableCell>
            </TableRow>
          ) : (
            rows.map((row) => (
              <TableRow
                key={row.id}
                className={`${onRowClick ? "cursor-pointer hover:bg-muted/50" : ""} ${rowClassName?.(row.original) ?? ""}`}
                onClick={onRowClick ? () => {
                  if (window.getSelection()?.toString()) return;
                  onRowClick(row.original);
                } : undefined}
              >
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>
                ))}
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>

      {serverPagination && serverPagination.totalPages > 0 ? (
        <PaginationControls
          page={serverPagination.page}
          totalPages={Math.max(1, serverPagination.totalPages)}
          pageSize={pageSize}
          totalItems={serverPagination.totalItems}
          onPageChange={serverPagination.onPageChange}
        />
      ) : table.getPageCount() > 1 && (
        <div className="flex items-center justify-between text-sm text-muted-foreground">
          <span>Page {pageIndex + 1} of {table.getPageCount()} · {data.length} total</span>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => table.previousPage()} disabled={!table.getCanPreviousPage()}>Previous</Button>
            <Button size="sm" variant="outline" onClick={() => table.nextPage()} disabled={!table.getCanNextPage()}>Next</Button>
          </div>
        </div>
      )}
    </div>
  );
}
