export type CsMessage = { id: string; direction: "INBOUND" | "OUTBOUND" | "NOTE" | "SYSTEM"; body: string; template_id: string | null; channel_status: string | null;
  created_by_name: string | null; created_at: string; channel_marked_at: string | null };
export type CsInquiry = { id: string; inquiry_no: string; business_entity_id: string; business_entity_name: string; channel_account_id: string; channel_account_name: string;
  order_id: string | null; order_no: string | null; external_order_key: string | null; claim_id: string | null; source: string; external_inquiry_key: string | null;
  inquiry_type: string; title: string; status: string; priority: string; assignee_member_id: string | null; assignee_name: string | null; due_at: string; overdue: boolean;
  customer_reply: boolean; customer_name_masked: string | null; first_response_at: string | null; answered_at: string | null; closed_at: string | null; created_at: string;
  last_message: string | null; pending_channel_answers: number; messages: CsMessage[]; order_summary: Record<string, string>; version: number };
export type CsKpi = { open: number; due_today: number; overdue: number; in_progress: number; customer_replied: number; waiting_channel_post: number };
export type CsTemplate = { id: string; business_entity_id: string | null; name: string; inquiry_type: string | null; body: string; status: string; version: number };
export const CS_TYPE_KO: Record<string, string> = { DELIVERY: "배송", PRODUCT: "상품", CANCEL: "취소", RETURN: "반품", EXCHANGE: "교환", PAYMENT: "결제",
  ADDRESS_CHANGE: "주소 변경", OTHER: "기타" };
export const CS_STATUS: Record<string, ["ok" | "info" | "warn" | "err" | "mute", string]> = { OPEN: ["warn", "미답변"], IN_PROGRESS: ["info", "처리 중"],
  WAITING_CUSTOMER: ["mute", "고객 회신 대기"], ANSWERED: ["ok", "답변 완료"], CLOSED: ["mute", "종료"] };
export const CS_SOURCE_KO: Record<string, string> = { CHANNEL: "채널 문의", PHONE: "전화", EMAIL: "메일", CHAT: "채팅", OTHER: "기타" };
export const CS_PRIORITY_KO: Record<string, string> = { URGENT: "긴급", HIGH: "높음", NORMAL: "보통", LOW: "낮음" };
