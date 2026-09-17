// 「영상 분석 설명 · 이벤트 어노테이션」 창의 칸 껍데기 — 두 칸이 공유한다.
// [@design UI-056] [@design UI-107] [@design UI-156]
//
// 칸 머리에 제목·범위 표시·미저장/저장 실패 표시·한 줄 설명을 두고, 본문은 칸이 스스로 스크롤한다.
// 껍데기를 공유하는 이유는 두 칸의 머리 모양이 갈리지 않게 하기 위해서다 — 한쪽만 「저장 안 됨」을
// 다르게 보이면 어느 칸이 바뀌었는지 읽는 규칙이 화면마다 달라진다.

import { type ReactNode } from 'react';

import {
  COLUMN_DIRTY_LABEL,
  COLUMN_SAVE_FAILED_LABEL,
  COLUMN_SCOPE_LABEL,
} from './annotationWording';

/**
 * 창의 한 칸이 바깥(창)에 내주는 조작 — 저장은 창 아래 공통 버튼 하나가 부른다.
 *
 * ★칸마다 저장 버튼을 두지 않는 것이 사양이라, 저장을 시작하는 주체와 저장을 아는 주체가
 * 갈린다. 그 사이를 이 손잡이가 잇는다.
 */
export interface AnnotationColumnHandle {
  /** 바뀐 것이 있으면 저장한다. 성공(또는 보낼 것이 없음)이면 true, 실패면 false. */
  save: () => Promise<boolean>;
}

export interface AnnotationColumnProps {
  title: string;
  description: string;
  /** 저장되지 않은 변경이 있는가. */
  dirty?: boolean;
  /** 직전 저장이 이 칸에서 실패했는가 — 한쪽만 실패했을 때 어느 칸인지 이 표시가 가른다. */
  failed?: boolean;
  children: ReactNode;
  /**
   * 본문을 세로 flex 로 두어 <b>자식이 칸 높이를 채우게</b> 한다.
   *
   * 영상 분석 설명 칸이 이 형태다(시안 `.colbody { display:flex; flex-direction:column }`) —
   * 긴 서술을 펼쳐 읽으려면 입력 칸이 창 높이를 따라 자라야 한다. 이벤트 어노테이션 칸은
   * 항목이 쌓이는 목록이라 기본(문서 흐름 + 스크롤)이 맞다.
   */
  fill?: boolean;
  'data-testid'?: string;
}

export function AnnotationColumn({
  title,
  description,
  dirty = false,
  failed = false,
  fill = false,
  children,
  'data-testid': testId,
}: AnnotationColumnProps) {
  return (
    <section
      aria-label={title}
      data-testid={testId}
      className="flex min-h-0 min-w-0 flex-col border-gray-200 [&+&]:border-l"
    >
      <div className="shrink-0 border-b border-gray-200 px-3.5 pb-2 pt-2.5">
        <div className="flex items-center gap-2">
          <h2 className="text-body-md font-bold text-gray-900">{title}</h2>
          <span className="text-caption text-gray-500">{COLUMN_SCOPE_LABEL}</span>
          <span className="flex-1" />
          {failed && (
            <span
              role="status"
              data-testid={testId === undefined ? undefined : `${testId}-failed`}
              className="inline-flex items-center gap-1 text-caption font-semibold text-danger"
            >
              {COLUMN_SAVE_FAILED_LABEL}
            </span>
          )}
          {dirty && !failed && (
            <span
              data-testid={testId === undefined ? undefined : `${testId}-dirty`}
              className="inline-flex items-center gap-1 text-caption font-semibold text-warning-700"
            >
              <span aria-hidden="true" className="h-2 w-2 rounded-full bg-warning-500" />
              {COLUMN_DIRTY_LABEL}
            </span>
          )}
        </div>
        <p className="mt-1 text-caption text-gray-600">{description}</p>
      </div>
      <div
        className={
          fill
            ? 'flex min-h-0 flex-1 flex-col overflow-y-auto px-3.5 py-3'
            : 'min-h-0 flex-1 overflow-y-auto px-3.5 py-3'
        }
      >
        {children}
      </div>
    </section>
  );
}
