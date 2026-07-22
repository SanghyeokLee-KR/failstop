"use client";

/**
 * 운영자·역할(/admin/operators). 역할 부여·회수는 SUPER + 2인 승인(§3-1).
 * 운영자 신원은 mTLS 인증서 CN 유래이며 password_hash가 없다(V5 admin_operators).
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import { ROLE_DESC } from "@/lib/admin/roles";
import { ADMIN_ROLES, type AdminOperator, type AdminRole, type DestructiveAction } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import RoleBadge from "@/components/admin/RoleBadge";
import { HealthPill, PageHeader, PermissionNote } from "@/components/admin/ui";
import styles from "@/components/admin/admin.module.css";

const OP_ACTIONS: DestructiveAction[] = ["operators.roles"];

export default function OperatorsPage() {
  const canSuper = useCan("SUPER");
  const [ops, setOps] = useState<AdminOperator[] | null>(null);
  const [targetId, setTargetId] = useState("");
  const [roles, setRoles] = useState<AdminRole[]>([]);
  const actions = useMemo(() => OP_ACTIONS, []);

  const load = useCallback(async () => {
    try {
      setOps(await getAdminApi().getOperators());
    } catch {
      // 세션 게이트 처리
    }
  }, []);

  useEffect(() => {
    void load();
    const iv = setInterval(() => void load(), 3000);
    return () => clearInterval(iv);
  }, [load]);

  const selectTarget = (op: AdminOperator) => {
    setTargetId(op.operatorId);
    setRoles(op.roles.slice());
  };

  const toggleRole = (r: AdminRole) => {
    setRoles((prev) => (prev.includes(r) ? prev.filter((x) => x !== r) : [...prev, r]));
  };

  return (
    <div className={styles.page}>
      <PageHeader title="운영자·역할" desc="역할 부여·회수는 SUPER + 2인 승인. 신원은 mTLS 인증서 CN에서 유래합니다." />

      <PanelCard title="운영자">
        {!ops ? (
          <Skeleton variant="card" width="100%" height={160} />
        ) : (
          <ul className={styles.opList}>
            {ops.map((op) => (
              <li key={op.operatorId} className={styles.opItem} data-selected={op.operatorId === targetId || undefined}>
                <div className={styles.opCol}>
                  <span className={styles.opNameRow}>{op.displayName}</span>
                  <span className={styles.opIdRow}>{op.operatorId}</span>
                </div>
                <div className={styles.opRoles}>
                  {op.roles.map((r) => (
                    <RoleBadge key={r} role={r} />
                  ))}
                </div>
                <div className={styles.opStatus}>
                  {op.status === "ACTIVE" ? <HealthPill health="success" label="활성" /> : <span className={styles.neutralPill}>비활성</span>}
                </div>
                <button type="button" className={styles.controlBtn} disabled={!canSuper} onClick={() => selectTarget(op)}>
                  역할 편집
                </button>
              </li>
            ))}
          </ul>
        )}
      </PanelCard>

      <PanelCard title="역할 변경 요청 (2인 승인)">
        {!canSuper && <PermissionNote>운영자 관리 권한(SUPER)이 없어 조회만 가능합니다.</PermissionNote>}
        {targetId ? (
          <div className={styles.roleEditor}>
            <span className={styles.roleEditorTarget}>대상 운영자 {targetId}</span>
            <fieldset className={styles.roleFieldset}>
              <legend className="sr-only">부여할 역할</legend>
              {ADMIN_ROLES.map((r) => (
                <label key={r} className={styles.roleCheck}>
                  <input type="checkbox" checked={roles.includes(r)} disabled={!canSuper} onChange={() => toggleRole(r)} />
                  <span className={styles.roleCheckLabel}>
                    <RoleBadge role={r} />
                    <small>{ROLE_DESC[r]}</small>
                  </span>
                </label>
              ))}
            </fieldset>
            <RequestButton
              action="operators.roles"
              payload={{ action: "operators.roles", operatorId: targetId, roles }}
              label="역할 변경 요청"
              onRequested={load}
            />
          </div>
        ) : (
          <p className={styles.approvalEmpty}>운영자 목록에서 편집할 대상을 선택하세요</p>
        )}
        <ApprovalList actions={actions} onChange={load} emptyLabel="대기 중인 역할 변경 승인이 없어요" />
      </PanelCard>
    </div>
  );
}
