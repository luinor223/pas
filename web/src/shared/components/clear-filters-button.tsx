import { X } from "lucide-react";
import { Button, type ButtonProps } from "@/shared/components/button";

export function ClearFiltersButton({ children = "Clear filters", ...props }: ButtonProps) {
  return (
    <Button type="button" variant="outline" {...props}>
      <X size={14} className="mr-1.5" aria-hidden="true" />
      {children}
    </Button>
  );
}
