// R4·R5 — 「폐기 프레임」 저장 안내(인라인 블록).
//
// 저장에 폐기 상태 변경이 실려 있으면 몇 개 프레임이 학습데이터에서 빠지고 몇 개가 되돌아오는지
// 알린다(SCREEN-005). 폐기는 되돌릴 수 있지만 <b>산출물에서 빠지는 결정</b>이라 저장 전에 한 번
// 드러낸다 — 한번이라도 검수가 완료된 영상에서는 서버가 새 폐기·복원을 막으므로 되돌릴 회차가
// 없으면 그 프레임은 산출물에서 영구 누락된다.
//
// <h3>왜 컴포넌트로 뽑았나 (Critical)</h3>
// 저장 축을 타는 진입점이 셋이다 — 헤더 저장(「폐기 프레임 저장 확인」 모달) · 프레임 이동 가드의
// "저장 후 이동" · 닫기 가드의 "저장 후 닫기". 뒤 둘은 <b>확인 모달을 겹치지 않고</b> 자기 다이얼로그
// 본문에 이 블록을 인라인으로 싣는다(모달 위에 모달을 올리면 포커스 트랩이 중첩되고 시안에도 그런
// 연쇄가 없다). 세 곳이 문구·톤을 각자 만들면 같은 사실을 서로 다르게 말하게 되므로 여기 하나로 모은다.
//
// @design SCREEN-005

import { AlertTriangle } from 'lucide-react';

import {
  DISCARD_SAVE_HELP_TEXT,
  discardSaveSummaryText,
  type DiscardSaveSummary,
} from '../discardSaveSummary';

export interface DiscardSaveNoticeProps {
  /** 이번 저장이 바꾸는 폐기 상태(방향별 프레임 수). */
  summary: DiscardSaveSummary;
  /**
   * 이 블록의 test id. 문장은 {@code `${testId}-summary`} 로 따로 잡을 수 있다.
   * 진입점마다 다른 값을 주어 어느 다이얼로그의 안내인지 구분한다.
   */
  testId: string;
}

export function DiscardSaveNotice({ summary, testId }: DiscardSaveNoticeProps) {
  return (
    <div data-testid={testId} className="flex flex-col gap-3">
      {/* 경고 톤은 이 저장소의 기존 warn 안내(bg-warning-50/text-warning-700)와 같은 색축을 쓴다. */}
      <p className="flex items-start gap-2 rounded-md bg-warning-50 px-3 py-2 text-body-sm text-warning-700">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
        <span data-testid={`${testId}-summary`}>{discardSaveSummaryText(summary)}</span>
      </p>
      <p className="text-caption text-gray-500">{DISCARD_SAVE_HELP_TEXT}</p>
    </div>
  );
}
