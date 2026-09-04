import { cn } from "@/shared/lib/cn";
import { statusTone } from "@/shared/lib/status-tone";

const DOT = "before:mr-1.5 before:h-1.5 before:w-1.5 before:rounded-full before:bg-current before:content-['']";

export function StatusBadge({ status, className }: { status: string; className?: string }) {
  const tone = statusTone(status);
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap",
        DOT,
        tone.text,
        tone.bg,
        className
      )}
    >
      {tone.label || status}
    </span>
  );
}
