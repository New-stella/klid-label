import { useState } from 'react';
import { Maximize2 } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ApiError } from '@/lib/api/errors';

import { useResolutionExport } from '../hooks/useResolutionExport';
import {
  RESOLUTION_PRESETS,
  RESOLUTION_PRESET_LABEL,
  type ResolutionPreset,
} from '../types';

interface ResolutionExportSectionProps {
  rawSn: number;
}

/**
 * SFR-06-03 — 해상도 export 섹션 (영상 상세 독립 UI).
 *
 * 표준 하위 해상도(1080P/720P/480P) 선택 + 실행 + 결과/에러 상태 표시.
 * 증강이 아닌 저작도구 직접 수행 — POST /v1/videos/{rawSn}/resolution 호출.
 * 업스케일·미검수·증강본·중복은 BE 가 400/409 로 거부 → 에러 메시지 노출.
 * 라벨 좌표는 제공하지 않으며 새 영상도 생성하지 않는다.
 */
export function ResolutionExportSection({ rawSn }: ResolutionExportSectionProps) {
  const [preset, setPreset] = useState<ResolutionPreset>('RES_720P');
  const { mutate, isPending, data, error, reset } = useResolutionExport(rawSn);

  const handleExport = () => {
    mutate(preset);
  };

  const errorMessage =
    error instanceof ApiError ? error.userMessage : error ? '해상도 변환에 실패했습니다.' : null;

  return (
    <section
      aria-labelledby="resolution-export-heading"
      className="mt-6 rounded-lg border border-gray-200 bg-white p-4"
    >
      <div className="flex items-center gap-1.5 mb-1">
        <Maximize2 size={16} className="text-gray-500" aria-hidden />
        <h3 id="resolution-export-heading" className="text-sm font-semibold text-gray-800">
          해상도 변경 (이미지셋 다운스케일)
        </h3>
      </div>
      <p className="text-xs text-gray-400 mb-3">
        검수 완료된 원본 프레임 이미지셋을 표준 하위 해상도로 내려받습니다. 라벨 좌표는 제공되지 않으며
        새 영상은 생성되지 않습니다.
      </p>

      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">목표 해상도</span>
          <select
            aria-label="목표 해상도 선택"
            value={preset}
            onChange={(e) => {
              setPreset(e.target.value as ResolutionPreset);
              reset();
            }}
            disabled={isPending}
            className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            {RESOLUTION_PRESETS.map((p) => (
              <option key={p} value={p}>
                {RESOLUTION_PRESET_LABEL[p]}
              </option>
            ))}
          </select>
        </label>

        <Button variant="primary" size="md" loading={isPending} onClick={handleExport}>
          해상도 변환 실행
        </Button>
      </div>

      {errorMessage && (
        <p role="alert" className="mt-3 text-sm text-red-600">
          {errorMessage}
        </p>
      )}

      {data && (
        <div
          role="status"
          className="mt-3 rounded-md border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-800"
        >
          변환 완료 — {data.srcW}×{data.srcH} → {data.targetW}×{data.targetH}, 프레임 {data.frameCount}장
          (export #{data.exportSn})
        </div>
      )}
    </section>
  );
}
