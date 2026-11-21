import { PRODUCT_STATUS } from "@/lib/catalog";

export function ProductStatusChip({ status }: { status: string }) {
  const [label, kind] = PRODUCT_STATUS[status] ?? [status, "mute"];
  return <span className={`chip c-${kind}`}>{label}</span>;
}
