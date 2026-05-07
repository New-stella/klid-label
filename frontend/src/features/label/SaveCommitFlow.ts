// 라벨 저장 + 커밋 시퀀스
//
// 시퀀스:
//   1) PUT /frames/{srcSn}/labels    — 라벨 일괄 저장
//   2) POST /frames/{srcSn}/commit   — Gitea 커밋 (portalMode면 skip)
//
// portalMode: 외부 채널(SCR-PORTAL-002) 간편 라벨링 — 버전관리 미제공.

import { commitLabels, putLabels, type CommitResponse } from './api';
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
 * 저장 → 커밋 2단계 실행. 1단계 실패 시 2단계 진행 안 함.
 */
export async function saveAndCommit(
  srcSn: number,
  labels: Label[],
  options: SaveCommitOptions = {},
): Promise<SaveCommitResult> {
  const saved = await putLabels(srcSn, labels);
  if (options.portalMode) {
    return { saved, committed: null };
  }
  const committed = await commitLabels(srcSn, options.message);
  return { saved, committed };
}
