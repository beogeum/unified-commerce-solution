-- docs/05 §7 추정 영업이익: 고정비 항목(인건비·임차료·공통 소프트웨어·기타 고정비). 공헌이익에는 넣지 않고 따로 표시(DLG-214).
alter table financial_entry drop constraint financial_entry_category_check;
alter table financial_entry add constraint financial_entry_category_check check (category in (
    'SALES', 'SELLER_DISCOUNT', 'SALES_RETURN', 'SHIPPING_FEE', 'EXCHANGE_PRICE_DIFFERENCE', 'CUSTOMER_RETURN_FEE',
    'CHANNEL_DISCOUNT',
    'PRODUCT_COGS', 'SELLING_FEE', 'PAYMENT_FEE', 'ADVERTISING', 'OUTBOUND_SHIPPING', 'THIRD_PARTY_PICK', 'PACK', 'STORAGE',
    'INBOUND_SERVICE', 'MATERIAL', 'GWP', 'RETURN_SHIPPING', 'RETURN_INSPECTION', 'REPACK', 'EXCHANGE_SHIPPING', 'INVENTORY_IMPAIRMENT',
    'DISPOSAL_SERVICE', 'CHANNEL_PENALTY', 'OTHER',
    'FIXED_LABOR', 'FIXED_RENT', 'FIXED_SOFTWARE', 'FIXED_OTHER'));
