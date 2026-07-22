package kr.exchange.admin;

/** 운영자 역할(관리자 설계 §1-2 5역할). 임의 축약 금지, DB operator_roles CHECK와 정합. */
public enum AdminRole {
    VIEWER,
    BOT_OPS,
    CHAOS_OPS,
    MEMBER_MOD,
    SUPER
}
