import * as React from "react";
import { cn } from "@/shared/lib/cn";

export function Badge({ className, variant = "default", ...props }: React.HTMLAttributes<HTMLDivElement> & { variant?: "default" | "secondary" | "destructive" | "outline" }) {
  const variants = {
    default: "bg-primary text-primary-foreground",
    secondary: "bg-muted text-foreground",
    destructive: "bg-destructive text-white",
    outline: "border text-foreground",
  }[variant];
  return <div className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold", variants, className)} {...props} />;
}
