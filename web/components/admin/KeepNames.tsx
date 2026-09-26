/**
 * 하이픈이 든 이름(app-3, ai-1, db-primary)을 한 덩어리로 묶는 글 조각. 브라우저는 하이픈 뒤를 줄바꿈 자리로 보아
 * 좁은 칸에서 'app-'과 '3'을 두 줄로 가른다. 낱말 안 줄바꿈을 막는 규칙(디자인 시스템 §10-1)이라 이름만 줄바꿈 없는
 * 조각으로 감싸고 나머지 글은 그대로 흐르게 한다. 글자는 바꾸지 않아 복사와 검색 결과가 같다.
 * 띄어 쓰지 않은 가운뎃점 묶음('복제·DB·백업·디스크와')도 한 덩어리다. 가운뎃점 앞에서 줄이 갈리면 줄이 '·'로 시작한다(§12-12).
 */

import { Fragment } from "react";
import { splitNames } from "@/lib/admin/text";

export default function KeepNames({ text }: { text: string }) {
  const parts = splitNames(text);
  return (
    <>
      {parts.map((p, i) =>
        i % 2 === 1 ? (
          <span key={i} style={{ whiteSpace: "nowrap" }}>
            {p}
          </span>
        ) : (
          <Fragment key={i}>{p}</Fragment>
        ),
      )}
    </>
  );
}
