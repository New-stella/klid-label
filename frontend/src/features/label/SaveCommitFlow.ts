// 라벨 저장 시퀀스
//
// BE의 PUT /frames/{srcSn}/labels 는 작업본 임시저장만 수행한다. 버전 스냅샷은 검수 승인(APPROVED)
// 시점에 BE가 생성하므로(SFR-08) 저장 단계에서 별도 커밋 호출은 하지 않는다 — 단일 PUT.
//
// portalMode: 외부 채널(SCR-PORTAL-002) 간편 라벨링 — 버전관리 미제공.

import { putLabels, type CommitResponse } from './api';
import type { Label, LabelsResponse } from './types';

export interface SaveCommitOptions {
  portalMode?: boolean;
  message?: string;
  /**
   * 라벨셋 버전 (C-ISSUE-21 낙관적 동시성 토큰) — <b>필수</b>.
   *
   * <p>DEV_FIX H13: 과거 이 함수는 putLabels(srcSn, labels) 로 <b>버전 없이</b> 호출해 BE 의 lost-update
   * 방어를 통째로 우회했다. 현재 이 경로를 렌더하는 화면이 없어 실해는 없었으나, 재사용되는 순간
   * "저장 시 남의 라벨이 조용히 삭제되는" 결함이 부활한다. 그래서 선택 인자가 아니라 <b>필수</b>로
   * 못박아 호출자가 반드시 조회 응답의 labelVersion 을 실어 보내게 한다(모르면 null 을 명시).
   * null 을 넘기면 BE 하위호환 경로(검사 skip)이며, 그 선택은 호출자의 명시적 책임이다.
   */
  labelVersion: number | null;
}

export interface SaveCommitResult {
  saved: LabelsResponse;
  /** 저장은 버전을 만들지 않으므로 항상 null (버전 스냅샷은 검수 승인 시점에 BE가 생성). */
  committed: CommitResponse | null;
}

/**
 * 라벨 저장(임시저장). 단일 PUT — 버전 스냅샷은 생성하지 않는다.
 */
export async function saveAndCommit(
  srcSn: number,
  labels: Label[],
  options: SaveCommitOptions,
): Promise<SaveCommitResult> {
  // DEV_FIX H13 — 라벨셋 버전을 반드시 실어 보낸다(BE 낙관적 동시성 검사 우회 제거).
  const saved = await putLabels(srcSn, labels, options.labelVersion);
  return { saved, committed: null };
}
