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
  _options: SaveCommitOptions = {},
): Promise<SaveCommitResult> {
  const saved = await putLabels(srcSn, labels);
  return { saved, committed: null };
}
