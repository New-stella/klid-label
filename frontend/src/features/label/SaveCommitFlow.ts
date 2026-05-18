// 라벨 저장 + 커밋 시퀀스
//
// BE의 PUT /frames/{srcSn}/labels 가 저장과 Gitea 커밋을 한 번에 처리한다.
// (portalMode: PORTAL 채널에서는 BE가 커밋을 skip)
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
  committed: CommitResponse | null;
}

/**
 * 저장 + 커밋. BE PUT이 두 단계를 모두 처리하므로 단일 호출.
 */
export async function saveAndCommit(
  srcSn: number,
  labels: Label[],
  _options: SaveCommitOptions = {},
): Promise<SaveCommitResult> {
  const saved = await putLabels(srcSn, labels);
  return { saved, committed: null };
}
