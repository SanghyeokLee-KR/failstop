/**
 * 하이픈이 든 이름(app-3, ai-1, db-primary)을 한 덩어리로 묶는 글 조각. 브라우저는 하이픈 뒤를 줄바꿈 자리로 보아
 * 좁은 칸에서 'app-'과 '3'을 두 줄로 가른다. 낱말 안 줄바꿈을 막는 규칙(디자인 시스템 §10-1)이라 이름만 줄바꿈 없는
 * 조각으로 감싸고 나머지 글은 그대로 흐르게 한다. 글자는 바꾸지 않아 복사와 검색 결과가 같다.
 */

import { Fragment } from "react";

const NAME = /([A-Za-z][A-Za-z0-9.]*(?:-[A-Za-z0-9.]+)+)/g;

export default function KeepNames({ text }: { text: string }) {
  const parts = text.split(NAME);
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
