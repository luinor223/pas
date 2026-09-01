import { FileText, CheckSquare, ReceiptText, PenLine } from "lucide-react";
import { StatCard } from "@/shared/components/stat-card";
import { StatusBadge } from "@/shared/components/status-badge";

// Sample data - the contract/approval services aren't wired yet; shape matches the API to come.
const APPROVALS = [
  { id: "CTR-2026-0142", customer: "Saigon Port Services", type: "Contract", status: "SUBMITTED", age: "2d" },
  { id: "PMT-2026-0331", customer: "Cat Lai Terminal", type: "Payment Statement", status: "SUBMITTED", age: "1d" },
  { id: "ADD-2026-0058", customer: "VN Logistics JSC", type: "Addendum", status: "SUBMITTED", age: "4h" },
  { id: "PRC-2026-0090", customer: "Hai Phong Depot", type: "Price List", status: "SUBMITTED", age: "6h" },
  { id: "PMT-2026-0328", customer: "Tan Cang Logistics", type: "Payment Statement", status: "SUBMITTED", age: "3d" },
];

const ACTIVITY = [
  { dot: "#059669", text: "Nguyễn Minh approved CTR-2026-0139", time: "12 min ago" },
  { dot: "#dc2626", text: "Trần Thu Hà rejected PMT-2026-0325", time: "48 min ago" },
  { dot: "#1d4ed8", text: "System signature completed on CTR-2026-0131", time: "2 h ago" },
  { dot: "#ea8a1e", text: "Lê Ngọc Vĩ submitted ADD-2026-0058", time: "3 h ago" },
  { dot: "#64748b", text: "Phạm Quang locked volume period VOL-2026-07", time: "5 h ago" },
];

const BARS = [
  { label: "Active", value: 68, color: "#1d4ed8" },
  { label: "Under Review", value: 22, color: "#ea8a1e" },
  { label: "Draft", value: 18, color: "#94a3b8" },
  { label: "Expired", value: 14, color: "#cbd5e1" },
  { label: "Rejected", value: 6, color: "#dc2626" },
];

export function Dashboard() {
  const max = Math.max(...BARS.map((b) => b.value));
  return (
    <div className="space-y-6">
      {/* KPIs */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Active Contracts" value="128" icon={<FileText size={18} />} foot="+6 this month" footTone="positive" />
        <StatCard label="Pending My Approval" value="14" icon={<CheckSquare size={18} />} foot="4 overdue" footTone="danger" />
        <StatCard label="Statements This Period" value="37" icon={<ReceiptText size={18} />} foot="8.42B VND total" />
        <StatCard label="Awaiting Signature" value="9" icon={<PenLine size={18} />} foot="avg 1.8 days" />
      </div>

      {/* Approvals + activity */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1.9fr_1fr]">
        <section className="rounded-xl border border-border bg-card">
          <div className="flex items-center justify-between px-5 py-4">
            <h2 className="text-[15px] font-semibold">Pending my approval</h2>
            <a href="#" className="text-sm font-medium text-primary hover:underline">View all</a>
          </div>
          <div className="overflow-x-auto scroll-thin">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-y border-border bg-muted/40 text-[11px] uppercase tracking-wide text-muted-foreground">
                  <th className="px-5 py-2.5 text-left font-semibold">Document ID</th>
                  <th className="px-3 py-2.5 text-left font-semibold">Customer</th>
                  <th className="px-3 py-2.5 text-left font-semibold">Type</th>
                  <th className="px-3 py-2.5 text-left font-semibold">Status</th>
                  <th className="px-5 py-2.5 text-right font-semibold">Age</th>
                </tr>
              </thead>
              <tbody>
                {APPROVALS.map((r) => (
                  <tr key={r.id} className="border-b border-border last:border-0 hover:bg-muted/30">
                    <td className="px-5 py-3"><a href="#" className="font-mono text-[13px] text-primary hover:underline">{r.id}</a></td>
                    <td className="px-3 py-3">{r.customer}</td>
                    <td className="px-3 py-3 text-muted-foreground">{r.type}</td>
                    <td className="px-3 py-3"><StatusBadge status={r.status} /></td>
                    <td className="px-5 py-3 text-right text-muted-foreground tnum">{r.age}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="rounded-xl border border-border bg-card p-5">
          <h2 className="mb-4 text-[15px] font-semibold">Recent activity</h2>
          <ul className="space-y-4">
            {ACTIVITY.map((a, i) => (
              <li key={i} className="flex gap-3">
                <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full" style={{ background: a.dot }} />
                <div className="min-w-0">
                  <p className="text-[13px] leading-snug">{a.text}</p>
                  <p className="mt-0.5 text-xs text-muted-foreground">{a.time}</p>
                </div>
              </li>
            ))}
          </ul>
        </section>
      </div>

      {/* Status chart */}
      <section className="rounded-xl border border-border bg-card p-5">
        <h2 className="mb-4 text-[15px] font-semibold">Contracts by status</h2>
        <div className="space-y-3">
          {BARS.map((b) => (
            <div key={b.label} className="flex items-center gap-4">
              <span className="w-28 shrink-0 text-[13px] text-muted-foreground">{b.label}</span>
              <div className="h-2.5 flex-1 rounded-full bg-muted">
                <div className="h-full rounded-full" style={{ width: `${(b.value / max) * 100}%`, background: b.color }} />
              </div>
              <span className="w-8 shrink-0 text-right text-[13px] font-medium tnum">{b.value}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
