"use client";

/**
 * 운영자·역할(/admin/operators). 생성·비활성·역할 부여/회수는 SUPER + 2인 승인(§3-1).
 * 운영자 신원은 mTLS 인증서 CN 유래이며 password_hash가 없다(V5 admin_operators).
 * 역할은 단건 부여/회수로 다룬다. 승인 감사에 어떤 역할이 바뀌는지 단건으로 남긴다.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import { ROLE_DESC, ROLE_LABEL } from "@/lib/admin/roles";
import { ADMIN_ROLES, type AdminOperator, type AdminRole, type DestructiveAction } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import RoleBadge from "@/components/admin/RoleBadge";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { HealthPill, PageHeader, PermissionNote } from "@/components/admin/ui";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

const OP_ACTIONS: DestructiveAction[] = ["operators.create", "operators.status", "operators.roles"];

// 인증서 CN 형식 유래. 소문자·숫자·하이픈만 허용해 자가신고 오타를 사전 차단한다
const OPERATOR_ID_RE = /^[a-z0-9-]{2,}$/;

export default function OperatorsPage() {
  const canSuper = useCan("SUPER");
  const [targetId, setTargetId] = useState("");
  const [newId, setNewId] = useState("");
  const [newName, setNewName] = useState("");
  const actions = useMemo(() => OP_ACTIONS, []);

  const res = useAdminResource<AdminOperator[]>(() => getAdminApi().getOperators(), { intervalMs: 3000, isSessionError });
  const ops = res.data;
  const load = res.reload;

  const selected = ops?.find((o) => o.operatorId === targetId) ?? null;

  // 역할 편집 대상 선택 시 편집 영역으로 포커스를 옮긴다(§9 키보드 흐름). 편집기가 그려진 뒤 실행
  const editorHeadingRef = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (selected) editorHeadingRef.current?.focus();
  }, [selected?.operatorId]);

  const idValid = OPERATOR_ID_RE.test(newId.trim());
  const idTaken = (ops ?? []).some((o) => o.operatorId === newId.trim());
  const createReady = canSuper && idValid && !idTaken && newName.trim().length > 0;

  return (
    <div className={styles.page}>
      <PageHeader
        title="운영자·역할"
        desc="생성·상태·역할 변경은 2인 승인"
        actions={res.stale ? <StaleBadge since={res.lastUpdatedAt} /> : undefined}
      />

      <PanelCard title="운영자">
        {res.error ? (
          <AdminErrorState reason={res.errorReason} onRetry={res.reload} />
        ) : !ops ? (
          <Skeleton variant="card" width="100%" height={160} />
        ) : (
          <div className={styles.staleRegion} data-stale={res.stale ? "" : undefined}>
            <ul className={styles.opList}>
              {ops.map((op) => (
                <li key={op.operatorId} className={styles.opItem} data-selected={op.operatorId === targetId || undefined}>
                  <div className={styles.opCol}>
                    <span className={styles.opNameRow}>{op.displayName}</span>
                    <span className={styles.opIdRow}>{op.operatorId}</span>
                  </div>
                  <div className={styles.opRoles}>
                    {op.roles.length > 0 ? (
                      op.roles.map((r) => <RoleBadge key={r} role={r} />)
                    ) : (
                      <span className={styles.opIdRow}>역할 없음</span>
                    )}
                  </div>
                  <div className={styles.opStatus}>
                    {op.status === "ACTIVE" ? <HealthPill health="success" label="활성" /> : <span className={styles.neutralPill}>비활성</span>}
                  </div>
                  <button
                    type="button"
                    className={styles.controlBtn}
                    disabled={!canSuper}
                    aria-pressed={op.operatorId === targetId}
                    onClick={() => setTargetId(op.operatorId === targetId ? "" : op.operatorId)}
                  >
                    {op.operatorId === targetId ? "닫기" : "관리"}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
      </PanelCard>

      <PanelCard title="운영자 관리" actions={<span className={styles.panelMeta}>2인 승인</span>}>
        {!canSuper && <PermissionNote>운영자 관리 권한(SUPER) 없음. 조회만 가능</PermissionNote>}
        {selected ? (
          <div className={styles.roleEditor}>
            <span className={styles.roleEditorTarget} ref={editorHeadingRef} tabIndex={-1}>
              {selected.displayName} ({selected.operatorId})
            </span>

            <div className={styles.opManageRow}>
              <span className={styles.opManageLabel}>
                상태
                {selected.status === "ACTIVE" ? (
                  <HealthPill health="success" label="활성" />
                ) : (
                  <span className={styles.neutralPill}>비활성</span>
                )}
              </span>
              {selected.status === "ACTIVE" ? (
                <RequestButton
                  action="operators.status"
                  payload={{ action: "operators.status", operatorId: selected.operatorId, status: "DISABLED" }}
                  label="비활성화 요청"
                  onRequested={load}
                  confirm={{
                    title: "운영자 비활성화 요청",
                    description: "발급·실행 시 이 운영자의 세션 확립 차단",
                    target: `${selected.displayName} (${selected.operatorId})`,
                    blastRadius: "단일 운영자 · 기존 세션 만료 시 재확립 불가",
                    confirmLabel: "비활성화 요청",
                  }}
                />
              ) : (
                <RequestButton
                  action="operators.status"
                  payload={{ action: "operators.status", operatorId: selected.operatorId, status: "ACTIVE" }}
                  label="활성화 요청"
                  onRequested={load}
                  confirm={{
                    title: "운영자 활성화 요청",
                    description: "발급·실행 시 이 운영자의 세션 확립 재허용",
                    target: `${selected.displayName} (${selected.operatorId})`,
                    blastRadius: "단일 운영자",
                    tone: "warning",
                    confirmLabel: "활성화 요청",
                  }}
                />
              )}
            </div>

            <div className={styles.roleGrantList}>
              {ADMIN_ROLES.map((r) => {
                const held = selected.roles.includes(r);
                return (
                  <div key={r} className={styles.roleGrantRow} data-held={held || undefined}>
                    <span className={styles.roleCheckLabel}>
                      <RoleBadge role={r} />
                      <small>{ROLE_DESC[r]}</small>
                    </span>
                    <RequestButton
                      action="operators.roles"
                      payload={{ action: "operators.roles", operatorId: selected.operatorId, grant: held ? "REVOKE" : "GRANT", role: r }}
                      label={held ? "회수" : "부여"}
                      onRequested={load}
                      confirm={{
                        title: held ? "역할 회수 요청" : "역할 부여 요청",
                        description: "발급·실행 시 대상 운영자의 역할 집합 갱신",
                        target: `${selected.displayName} (${selected.operatorId})`,
                        blastRadius: `${ROLE_LABEL[r]} ${held ? "회수" : "부여"}`,
                        tone: held ? "danger" : "warning",
                        confirmLabel: held ? "회수 요청" : "부여 요청",
                      }}
                    />
                  </div>
                );
              })}
            </div>
          </div>
        ) : (
          <div className="empty-state empty-state--compact">
            <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
            <span className="empty-state__title">관리할 운영자 선택 필요</span>
            <span className="empty-state__desc">상태 변경과 역할 부여·회수는 건마다 다른 운영자의 승인을 거칩니다</span>
          </div>
        )}
      </PanelCard>

      <PanelCard title="운영자 생성 요청" actions={<span className={styles.panelMeta}>2인 승인</span>}>
        {!canSuper && <PermissionNote>운영자 생성 권한(SUPER) 없음</PermissionNote>}
        <div className={styles.roleEditor}>
          <div className={styles.formRow}>
            <label className={styles.field}>
              <span>operatorId (인증서 CN)</span>
              <input
                value={newId}
                onChange={(e) => setNewId(e.target.value)}
                disabled={!canSuper}
                placeholder="op-03"
                aria-label="신규 운영자 operatorId"
                aria-invalid={newId.length > 0 && (!idValid || idTaken)}
              />
            </label>
            <label className={styles.field}>
              <span>표시 이름</span>
              <input value={newName} onChange={(e) => setNewName(e.target.value)} disabled={!canSuper} aria-label="신규 운영자 표시 이름" />
            </label>
          </div>
          {newId.length > 0 && !idValid && <PermissionNote>operatorId는 소문자·숫자·하이픈 2자 이상</PermissionNote>}
          {idTaken && <PermissionNote>이미 존재하는 operatorId</PermissionNote>}
          {/* 정상 절차 설명은 경보면이 아니라 무채 각주. 이 화면의 유채는 실제 이탈에만 남긴다 */}
          <p className={styles.formHelp}>생성은 신원 등록만 수행. 역할은 생성 후 관리 패널에서 다른 운영자의 승인으로 부여</p>
          {createReady ? (
            <RequestButton
              action="operators.create"
              payload={{ action: "operators.create", operatorId: newId.trim(), displayName: newName.trim() }}
              label="생성 요청"
              onRequested={() => {
                setNewId("");
                setNewName("");
                load();
              }}
              confirm={{
                title: "운영자 생성 요청",
                description: "발급·실행 시 이 운영자를 레지스트리에 추가",
                target: `${newName.trim()} (${newId.trim()})`,
                blastRadius: "신원만 등록 · 역할은 별도 승인으로 부여",
                confirmLabel: "생성 요청",
              }}
            />
          ) : (
            <button type="button" className={styles.neutralBtn} disabled>
              생성 요청
            </button>
          )}
        </div>
      </PanelCard>

      <PanelCard title="운영자 승인 대기">
        <ApprovalList
          actions={actions}
          onChange={load}
          emptyLabel="대기 중인 운영자 승인 없음"
          emptyDesc="생성·상태·역할 요청이 들어오면 여기에서 발급·실행"
        />
      </PanelCard>
    </div>
  );
}
