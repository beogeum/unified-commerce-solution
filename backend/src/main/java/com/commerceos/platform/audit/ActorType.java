package com.commerceos.platform.audit;

/** 감사 주체 구분(docs/08 §4: 관리자/시스템 명령/승인자/외부 시스템). */
public enum ActorType {
    USER, SYSTEM, INTEGRATION, APPROVER
}
