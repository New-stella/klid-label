import { useState } from 'react';

import { useUiStore } from '@/stores/useUiStore';

import { useCreateMarkingImport, useMarkingProgress, useMarkingScan } from '../hooks/useMarkingImport';
import {
  EMPTY_MARKING_META,
  isMarkingMetaReady,
  toMarkingMetaPayload,
  type MarkingMetaDraft,
} from '../markingMeta';
import type { MarkingItemStatus, MarkingScanResult } from '../markingTypes';

import { MarkingImportForm } from './MarkingImportForm';
import { MarkingImportProgressPanel } from './MarkingImportProgressPanel';
import { MarkingScanResultPanel } from './MarkingScanResultPanel';

/** 서버 오류에서 사용자에게 보여줄 문구를 꺼낸다 — 서버 메시지를 그대로 쓴다. */
function messageOf(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback;
}

/** 적재할 수 있는 항목의 이름만 모은다 — 적재할 수 없는 항목은 고를 수 없다. */
function importableNames(result: MarkingScanResult): string[] {
  return result.items.filter((i) => i.importable).map((i) => i.markingFileName);
}

/**
 * 이벤트 마킹 갈래 — 폴더 검사 → 짝 확인 → 일괄 적재 → 진행 조회를 한 자리에서 밟는다.
 *
 * <p>라벨링 완료 갈래와 <b>상태를 나눠 갖는다</b>. 두 갈래의 입력과 결과가 서로 섞이지 않아야
 * 하므로 이 구획이 자기 상태만 들고 있고, 갈래를 바꿔도 가져온 내역은 이 바깥에 있어 그대로
 * 남는다(SCREEN-039).
 *
 * <p>적재 요청은 <b>202</b> 로 곧바로 돌아오며 그 응답에는 작업 식별번호와 대상 수만 있다.
 * 그래서 성공 안내를 「가져왔다」가 아니라 「등록했다」로 적고, 실제 결과는 아래 진행 패널이
 * 되풀이 조회로 보여준다.
 *
 * @design SCREEN-039
 * @design ADR-053
 * @design API-216 API-217 API-218
 * @design UC-037
 * @design AC-1032
 * @design AC-1033
 */
export function MarkingImportSection() {
  const pushToast = useUiStore((s) => s.pushToast);

  const [folderPath, setFolderPath] = useState('');
  const [meta, setMeta] = useState<MarkingMetaDraft>(EMPTY_MARKING_META);
  const [scan, setScan] = useState<MarkingScanResult | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [jobSn, setJobSn] = useState<number | null>(null);
  const [statusFilter, setStatusFilter] = useState('');
  const [submitError, setSubmitError] = useState<string | null>(null);

  const { mutate: runScan, isPending: scanning } = useMarkingScan({
    onSuccess: (result) => {
      setScan(result);
      // 적재할 수 있는 항목을 기본으로 고른다 — 고를 수 없는 항목은 담지 않는다.
      setSelected(new Set(importableNames(result)));
      setSubmitError(null);
    },
    onError: (e) => {
      setScan(null);
      setSelected(new Set());
      pushToast({ variant: 'error', message: messageOf(e, '폴더를 검사하지 못했습니다.') });
    },
  });

  const { mutate: runImport, isPending: submitting } = useCreateMarkingImport({
    onSuccess: (result) => {
      setSubmitError(null);
      setJobSn(result.jobSn);
      setStatusFilter('');
      pushToast({
        variant: 'success',
        message: `적재 작업을 등록했습니다. 대상 ${result.targetCount}건 — 진행은 아래에서 확인하세요.`,
      });
    },
    onError: (e) => setSubmitError(messageOf(e, '적재를 등록하지 못했습니다.')),
  });

  const {
    data: progress,
    isLoading: progressLoading,
    error: progressError,
  } = useMarkingProgress(jobSn, (statusFilter || null) as MarkingItemStatus | null);

  const metaReady = isMarkingMetaReady(meta);

  return (
    <div className="flex flex-col gap-4" data-testid="marking-import-section">
      <MarkingImportForm
        folderPath={folderPath}
        meta={meta}
        scanning={scanning}
        onFolderPathChange={setFolderPath}
        onMetaChange={setMeta}
        onScan={() => runScan({ folderPath: folderPath.trim() })}
      />

      {scan && (
        <MarkingScanResultPanel
          result={scan}
          selected={selected}
          metaReady={metaReady}
          submitting={submitting}
          errorMessage={submitError}
          onToggle={(name) =>
            setSelected((prev) => {
              const next = new Set(prev);
              if (next.has(name)) next.delete(name);
              else next.add(name);
              return next;
            })
          }
          onToggleAll={(checked) => setSelected(checked ? new Set(importableNames(scan)) : new Set())}
          onSubmit={() =>
            runImport({
              folderPath: folderPath.trim(),
              meta: toMarkingMetaPayload(meta),
              targets: Array.from(selected),
            })
          }
        />
      )}

      {jobSn !== null && (
        <MarkingImportProgressPanel
          jobSn={jobSn}
          progress={progress}
          loading={progressLoading}
          errorMessage={
            progressError ? messageOf(progressError, '진행을 불러오지 못했습니다.') : null
          }
          statusFilter={statusFilter}
          onStatusFilterChange={setStatusFilter}
        />
      )}
    </div>
  );
}
