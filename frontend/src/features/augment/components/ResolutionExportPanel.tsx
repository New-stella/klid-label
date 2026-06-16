import { useMemo, useState } from 'react';
import { Info, Maximize2 } from 'lucide-react';

import { ResolutionExportSection } from '@/features/video/components/ResolutionExportSection';
import { useVideos } from '@/features/video/hooks/useVideos';

const PAGE_SIZE = 100;

/**
 * SFR-06-03 — 해상도 변경(이미지셋 다운스케일) 패널 (데이터 증강 화면 내 독립 섹션).
 *
 * 증강(WINTER/NIGHT/RAIN)과 명확히 구분되는 별도 기능 — 저작도구가 직접 수행한다.
 * 증강 화면에는 '현재 영상' 개념이 없으므로 자체 단일 영상 선택기를 둔다.
 * 선택된 rawSn 을 ResolutionExportSection(미변경 컴포넌트)에 전달한다.
 *
 * 상태 격리: 이 패널의 영상 선택 상태는 증강 대상(다중) 선택과 공유하지 않는다.
 * 해상도 선택기는 검수 완료(COMPLETED + APPROVED) 영상 단일 선택.
 *
 * 보안: REVIEWER 전용 라우트(/augment) + BE 권한 그대로. 외부 입력 없음.
 */
export function ResolutionExportPanel() {
  const [selectedRawSn, setSelectedRawSn] = useState<number | null>(null);

  // 영상 목록 재사용 — 증강 대상과 동일하게 검수 완료(승인) 영상만 노출.
  const { data: videosPage, isLoading } = useVideos({
    page: 0,
    size: PAGE_SIZE,
    dataSttsCd: 'COMPLETED',
    reviewStatusCd: 'APPROVED',
  });

  const videos = useMemo(
    () => (videosPage?.content ?? []).filter((v) => v.status === 'COMPLETED'),
    [videosPage],
  );

  const handleSelect = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    setSelectedRawSn(value === '' ? null : Number(value));
  };

  return (
    <section
      aria-label="해상도 변경"
      className="space-y-4"
      data-testid="resolution-export-panel"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-gray-700 text-white">
          <Maximize2 size={14} aria-hidden />
        </span>
        <h2 className="text-base font-semibold text-gray-800">해상도 변경</h2>
        <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
          이미지셋 다운스케일
        </span>
      </div>

      <div className="flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2.5">
        <Info size={14} className="mt-0.5 shrink-0 text-blue-600" aria-hidden />
        <p className="text-xs text-blue-700">
          해상도 변경은 증강(겨울/야간/비)과 별개 기능입니다. 검수 완료 영상 1건을
          선택해 표준 하위 해상도 이미지셋을 내려받습니다. 라벨 좌표는 제공되지
          않으며 새 영상은 생성되지 않습니다.
        </p>
      </div>

      <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
        <label className="flex flex-col gap-1">
          <span className="text-xs font-medium text-gray-500">대상 영상</span>
          <select
            aria-label="해상도 변경 대상 영상 선택"
            value={selectedRawSn === null ? '' : String(selectedRawSn)}
            onChange={handleSelect}
            disabled={isLoading}
            className="max-w-md rounded-md border border-gray-300 bg-white px-3 py-2 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <option value="">영상을 선택하세요</option>
            {videos.map((v) => (
              <option key={v.id} value={v.id}>
                {v.cctvName} (#{v.id})
              </option>
            ))}
          </select>
        </label>

        {selectedRawSn === null ? (
          <p className="mt-3 text-sm text-gray-400">
            대상 영상을 선택하면 해상도 변환 옵션이 표시됩니다.
          </p>
        ) : (
          <ResolutionExportSection rawSn={selectedRawSn} />
        )}
      </div>
    </section>
  );
}
