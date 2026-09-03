import * as React from "react";
import { cn } from "@/shared/lib/cn";

const chevron = `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%236b7280' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E")`;

export const Select = React.forwardRef<HTMLSelectElement, React.SelectHTMLAttributes<HTMLSelectElement>>(({ className, children, style, ...props }, ref) => (
  <select
    ref={ref}
    className={cn("flex h-9 w-full appearance-none rounded-md border border-input bg-background py-2 pl-3 pr-9 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring", className)}
    style={{ backgroundImage: chevron, backgroundPosition: "right 0.75rem center", backgroundRepeat: "no-repeat", ...style }}
    {...props}
  >
    {children}
  </select>
));
Select.displayName = "Select";
