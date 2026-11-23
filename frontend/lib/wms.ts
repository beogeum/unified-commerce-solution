export type PickTask = { id: string; fulfillment_order_id: string; fo_no: string; sku_id: string; sku_code: string; barcode: string | null; location_code: string | null;
  lot_code: string | null; expires_on: string | null; quantity: number; picked_qty: number; status: string; short_reason: string | null };
export type Wave = { id: string; wave_no: string; node_id: string; node_name: string; status: string; created_at: string; picked_at: string | null;
  orders: { id: string; fo_no: string; status: string; packed_at: string | null }[]; tasks: PickTask[]; skipped: { fo_no: string; reason: string }[]; version: number };
export type Packing = { fulfillment_order_id: string; fo_no: string; status: string; wave_id: string | null; wave_no: string | null; pick_complete: boolean;
  packed_at: string | null; lines: { sku_id: string; sku_code: string; barcode: string | null; quantity: number; picked: number }[] };

type Tone = "info" | "ok" | "warn" | "mute" | "err";
export const WAVE_STATUS: Record<string, [Tone, string]> = { PICKING: ["warn", "피킹 중"], PICKED: ["info", "피킹 완료"], CLOSED: ["ok", "종료"], CANCELLED: ["mute", "취소"] };
export const TASK_STATUS: Record<string, [Tone, string]> = { OPEN: ["warn", "피킹 대기"], PICKED: ["ok", "피킹 완료"], SHORT: ["err", "결품"], CANCELLED: ["mute", "취소"] };
