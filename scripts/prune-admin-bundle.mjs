/**
 * prune-admin-bundle.mjs. 공개 정적 번들에서 관리 화면을 제거한다.
 *
 * 사용:
 *   node scripts/prune-admin-bundle.mjs              web/out 을 제자리에서 정리
 *   node scripts/prune-admin-bundle.mjs <번들경로>    다른 디렉터리를 대상으로
 *   node scripts/prune-admin-bundle.mjs --check      제거하지 않고 잔존 여부만 판정
 *
 * 배경: 관리 화면은 별도 앱이 아니라 같은 Next.js 라우트다(`web/app/admin/`).
 * `output: "export"` 가 전 라우트를 함께 내보내므로 `web/out/` 에 관리 화면이 들어간다.
 * 그대로 S3 에 올리면 CloudFront 가 `/*` 규칙으로 그것까지 공개한다. ADR 0011
 * (docs/adr/0011-cloudfront-two-origins.md:41) 이 "라우팅 없음은 인증 검사보다 앞선
 * 방어다" 라고 적었고, 그 원칙을 화면 축에도 적용한다. 정본 근거 절은 8.1 접근 경로다.
 *
 * 노출 등급은 정보 노출이고 즉시 장악은 아니다. 화면이 부르는 `/admin/v1/*` 은 8443 mTLS 라
 * 클라이언트 인증서 없이는 응답하지 않는다. 그럼에도 제거하는 이유는 위 원칙 때문이다.
 *
 * 대상이 0 건이면 실패한다. 무동작으로 끝나면 "정리했다" 는 잘못된 확신만 남기기 때문이다.
 * 실제로 이 저장소의 `certbot renew` 가 빈 디렉터리에서 무동작으로 통과하던 전례가 있다.
 *
 * 한계: 파일 이름에 admin 이 들어가지 않는 관리 전용 코드는 잡지 못한다. 이 게이트는
 * 라우트 경로 기준이고, 관리 로직을 공용 청크로 옮기면 그대로 남는다.
 */

import { existsSync, readdirSync, rmSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL(".", import.meta.url)), "..");

const args = process.argv.slice(2);
const checkOnly = args.includes("--check");
const target = args.find((a) => !a.startsWith("--")) ?? join(ROOT, "web", "out");

/* 제거 대상. Next.js 정적 export 가 관리 라우트에 대해 내는 산출물 네 갈래다.
 * 페이지 HTML 과 RSC 페이로드(.txt)를 지우면 라우트가 닿지 않고, 청크까지 지워야
 * 직접 URL 로 스크립트를 긁는 경로도 막힌다. */
const TARGETS = [
  "admin",
  "admin.html",
  "admin.txt",
  join("_next", "static", "chunks", "app", "admin"),
];

function fail(msg) {
  console.error(`오류: ${msg}`);
  process.exit(1);
}

/** 번들 안에 이름으로 admin 이 걸리는 경로를 전부 모은다. 제거 후 잔존 판정에 쓴다. */
function scanAdminPaths(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (/admin/i.test(name)) {
      out.push(full);
      continue; // 통째로 지울 대상이므로 안을 더 훑지 않는다
    }
    if (statSync(full).isDirectory()) {
      scanAdminPaths(full, out);
    }
  }
  return out;
}

if (!existsSync(target)) {
  fail(`번들 디렉터리가 없다: ${target}. next build 를 먼저 돌린다.`);
}

if (checkOnly) {
  const left = scanAdminPaths(target);
  if (left.length > 0) {
    console.error(`오류: 공개 번들에 관리 화면이 남았다. ${left.length} 건.`);
    for (const p of left) console.error(`  ${relative(ROOT, p)}`);
    console.error("scripts/prune-admin-bundle.mjs 를 빌드 뒤에 돌린다(기획안 8.1).");
    process.exit(1);
  }
  console.log(`공개 번들 검사 통과. 관리 화면 0 건. 대상 ${relative(ROOT, target)}`);
  process.exit(0);
}

const removed = [];
for (const rel of TARGETS) {
  const full = join(target, rel);
  if (!existsSync(full)) continue;
  rmSync(full, { recursive: true, force: true });
  removed.push(rel);
}

if (removed.length === 0) {
  fail(
    `제거 대상이 0 건이다. 대상 ${relative(ROOT, target)}. ` +
      "번들이 관리 라우트를 포함하지 않거나 경로 규약이 바뀌었다. " +
      "무동작을 성공으로 보고하지 않는다."
  );
}

/* 제거 목록이 규약을 따라잡지 못했을 수 있으므로 이름 기준으로 다시 훑는다. */
const left = scanAdminPaths(target);
if (left.length > 0) {
  console.error(`오류: 제거 후에도 관리 화면이 남았다. ${left.length} 건.`);
  for (const p of left) console.error(`  ${relative(ROOT, p)}`);
  fail("TARGETS 목록이 산출물 규약과 어긋났다. 목록을 갱신한다.");
}

console.log(`공개 번들 정리 완료. ${removed.length} 건 제거. 대상 ${relative(ROOT, target)}`);
for (const rel of removed) console.log(`  ${rel}`);
