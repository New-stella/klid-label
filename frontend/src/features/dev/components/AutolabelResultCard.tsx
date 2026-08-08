import { Link } from 'react-router-dom';

import { Card, CardContent, CardHeader, CardTitle } from '@/components/common/Card';
import { Spinner } from '@/components/common/Spinner';
import { type AutolabelTestResult } from '@/features/dev/types';

export interface AutolabelResultCardProps {
  result: AutolabelTestResult;
  /** 폴링으로 받아온 영상 상세 — 아직 없으면 `null`(업로드 응답값으로 대체 표시). */
  status: string | null;
  frameCount: number | null;
  /** terminal(MARKING_READY/FAILED) 도달 — 도달하면 진행 스피너를 감춘다. */
  reachedTerminal: boolean;
  reachedMarkingReady: boolean;
}

/**
 * dev 업로드 결과 카드 — rawSn·파이프라인 상태·산출 요약·후속 화면 링크.
 *
 * 화면 상태(폴링·폼)는 페이지가 소유하고 여기서는 표시만 한다(순수 표현 컴포넌트).
 * `component.md` 의 400줄 규칙에 따라 페이지에서 분리했다.
 */
export function AutolabelResultCard({
  result,
  status,
  frameCount,
  reachedTerminal,
  reachedMarkingReady,
}: AutolabelResultCardProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>업로드 결과</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-3 text-body">
          <div className="flex items-center gap-2">
            <span
              data-testid="autolabel-raw-sn"
              className="rounded-md bg-primary-50 px-2 py-0.5 font-mono text-primary-700"
            >
              rawSn = {result.rawSn}
            </span>
            <span className="text-gray-500">
              파이프라인 상태: {status ?? result.pipelineStatus}
            </span>
            {!reachedTerminal && <Spinner size="sm" label="파이프라인 진행 중" />}
          </div>

          {reachedMarkingReady && (
            <div
              role="status"
              data-testid="autolabel-marking-ready"
              className="rounded-md border border-primary-200 bg-primary-50 px-3 py-2 text-sub text-primary-700"
            >
              업로드 완료 — 비식별 후 마킹 대기입니다. 마킹 화면에서 마킹을 진행하세요. (rawSn ={' '}
              {result.rawSn})
            </div>
          )}

          {status === 'FAILED' && (
            <div
              role="alert"
              data-testid="autolabel-pipeline-failed"
              className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sub text-danger-700"
            >
              파이프라인 실행에 실패했습니다. BE 로그를 확인해주세요. (rawSn = {result.rawSn})
            </div>
          )}

          <div className="grid grid-cols-1 gap-2 text-gray-600 md:grid-cols-3">
            <div>
              <span className="text-sub text-gray-500">파일 경로</span>
              <p className="break-all font-mono text-sub text-gray-700">{result.savedFilePath}</p>
            </div>
            <div>
              <span className="text-sub text-gray-500">프레임 수</span>
              <p className="text-section-title text-gray-900">{frameCount ?? 0}</p>
            </div>
            <div>
              <span className="text-sub text-gray-500">트리거 시각</span>
              <p className="text-sub text-gray-700">
                {new Date(result.startedAt).toLocaleString()}
              </p>
            </div>
          </div>

          <div className="flex flex-wrap gap-4 pt-2">
            <Link
              to={`/marking/${result.rawSn}`}
              className="text-body font-medium text-primary-600 hover:underline"
            >
              마킹 화면으로 이동 →
            </Link>
            <Link
              to="/video/completed"
              className="text-body font-medium text-primary-600 hover:underline"
            >
              영상 목록 보기 →
            </Link>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

export default AutolabelResultCard;
