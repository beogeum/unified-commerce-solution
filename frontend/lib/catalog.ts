export const SKU_TYPE: Record<string, string> = {
  NORMAL: "일반", BUNDLE: "가상묶음", KIT: "실물세트", MATERIAL: "부자재", GIFT: "증정품", SAMPLE: "샘플", SERVICE: "서비스", DIGITAL: "디지털",
};
/** 유형별 기본값 [판매가능, 구매가능, 재고관리] — 서버(CatalogRules)와 같은 값 */
export const TYPE_DEFAULTS: Record<string, [boolean, boolean, boolean]> = {
  NORMAL: [true, true, true], BUNDLE: [true, false, false], KIT: [true, true, true], MATERIAL: [false, true, true],
  GIFT: [false, true, true], SAMPLE: [false, true, true], SERVICE: [true, false, false], DIGITAL: [true, false, false],
};
export const PRODUCT_TAX: Record<string, string> = { TAXABLE: "과세", TAX_FREE: "면세", ZERO_RATED: "영세율" };
export const PRODUCT_STATUS: Record<string, [string, "ok" | "info" | "warn" | "mute" | "err"]> = {
  DRAFT: ["초안", "info"], ACTIVE: ["판매중", "ok"], INACTIVE: ["판매중지", "mute"], ARCHIVED: ["보관", "mute"],
};
export const MISSING_KO: Record<string, string> = {
  brand: "브랜드", category: "카테고리", tax_type: "과세유형", active_sellable_sku: "판매 가능한 SKU",
};

export type Brand = { id: string; name: string; status: string; product_count: number; version: number };
export type Category = { id: string; parent_id: string | null; name: string; path: string; status: string };
export type Product = {
  id: string; product_code: string; name: string; internal_name: string | null; brand_id: string | null; brand_name: string | null;
  category_id: string | null; category_name: string | null; tax_type: string | null; status: string; sku_count: number;
  active_sku_count: number; version: number; updated_at: string;
};
export type OptionValue = { id: string; value: string };
export type ProductOption = { id: string; name: string; values: OptionValue[] };
export type Warning = { code: string; message: string; details: Record<string, unknown> };
export type Sku = {
  id: string; product_id: string; product_code: string; product_name: string; sku_code: string; name: string | null; sku_type: string;
  sellable: boolean; purchasable: boolean; inventory_managed: boolean; barcode: string | null; lot_managed: boolean; expiry_managed: boolean;
  min_remaining_shelf_days: number | null; options: { option_name: string; value: string }[]; status: string; version: number; warnings: Warning[];
};
export type ProductDetail = { product: Product; options: ProductOption[]; skus: Sku[] };
