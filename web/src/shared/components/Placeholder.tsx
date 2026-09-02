import { Hammer } from "lucide-react";

// Styled stand-in for screens landing in a later build phase.
export function Placeholder({ title, note }: { title: string; note?: string }) {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
        <Hammer size={24} />
      </div>
      <h2 className="mt-5 text-xl font-bold tracking-tight">{title}</h2>
      <p className="mt-2 max-w-md text-sm text-muted-foreground">
        {note ?? "This screen is designed and coming in a later build phase."}
      </p>
    </div>
  );
}
