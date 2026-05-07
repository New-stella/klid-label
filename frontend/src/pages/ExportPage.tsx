import { useState } from 'react';
import { z } from 'zod';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Input } from '@/components/common/Input';
import { PageHeader } from '@/components/common/PageHeader';
import { RadioGroup } from '@/components/common/RadioGroup';
import { Skeleton } from '@/components/common/Skeleton';
import {
  useDatasets,
  usePrepareExport,
} from '@/features/export/hooks/useExport';
import {
  ExportFormat,
  type ExportFormat as Format,
  type ExportPreview,
} from '@/features/export/types';
import { useUiStore } from '@/stores/useUiStore';

/**
 * NAS 경로 zod 스키마 — UI/UX §4-12 + security.md.
 *
 * 보안:
 * - `..` 포함 금지 (path traversal 1차 차단 — BE 책임이지만 FE도 가드).
 * - 절대 경로(`/`) 시작만 허용.
 * - 길이 제한 (1~512자).
 */
const nasPathSchema = z
  .string()
  .min(1, 'NAS 경로를 입력하세요')
  .max(512, '최대 512자')
  .refine((v) => !v.includes('..'), { message: '`..` 경로는 허용되지 않습니다' })
  .refine((v) => v.startsWith('/'), { message: '절대 경로(/)로 시작해야 합니다' });

const FORMAT_OPTIONS: { value: Format; label: string }[] = [
  { value: ExportFormat.COCO, label: 'COCO' },
  { value: ExportFormat.YOLO, label: 'YOLO' },
  { value: ExportFormat.VIDEO_COT, label: '영상 + CoT' },
];

/**
 * SCR-EXPORT-001 학습데이터셋 NAS 내보내기 (`/export`).
 *
 * UI/UX §4-12:
 * - 데이터셋 선택 라디오 + 형식 라디오 + NAS 경로 + [미리보기][내보내기 실행]
 * - V1.4: 데이터마트 UI/D-day/다운로드 카드 절대 미제공
 *
 * 보안:
 * - REVIEWER 역할 (라우터 + BE).
 * - format은 enum allowlist (라디오로 강제).
 * - nasPath는 zod 1차 검증 + BE에서 path traversal 재검증.
 */
export function ExportPage() {
  const pushToast = useUiStore((s) => s.pushToast);
  const [datasetId, setDatasetId] = useState<number | null>(null);
  const [format, setFormat] = useState<Format>(ExportFormat.COCO);
  const [nasPath, setNasPath] = useState('');
  const [pathError, setPathError] = useState<string | undefined>(undefined);
  const [preview, setPreview] = useState<ExportPreview | null>(null);

  const { data: datasets, isLoading: dsLoading, error: dsError } = useDatasets();

  const { mutate, isPending } = usePrepareExport({
    onSuccess: (data) => {
      // PrepareExportResponse 형태 — preview 포함 가능
      const res = data as unknown as { exportId: number; preview?: ExportPreview };
      if (res.preview) setPreview(res.preview);
      pushToast({ variant: 'success', message: '내보내기 준비 완료' });
    },
    onError: () => {
      pushToast({ variant: 'error', message: '내보내기 준비 실패' });
    },
  });

  const validatePath = (value: string): boolean => {
    const result = nasPathSchema.safeParse(value);
    if (!result.success) {
      setPathError(result.error.issues[0]?.message ?? '잘못된 경로');
      return false;
    }
    setPathError(undefined);
    return true;
  };

  const handlePathChange = (value: string) => {
    setNasPath(value);
    if (value) validatePath(value);
    else setPathError(undefined);
  };

  const canSubmit =
    datasetId !== null && format && nasPath && !pathError && !isPending;

  const handlePreview = () => {
    if (!canSubmit || datasetId === null) return;
    if (!validatePath(nasPath)) return;
    mutate({ datasetId, format, nasPath });
  };

  const handleExecute = () => {
    if (!canSubmit || datasetId === null) return;
    if (!validatePath(nasPath)) return;
    mutate({ datasetId, format, nasPath });
  };

  return (
    <section className="flex flex-col gap-4" data-testid="export-page">
      <PageHeader
        title="학습데이터셋 내보내기"
        description="NAS 경로로 학습데이터셋을 내보냅니다."
      />

      <section
        aria-label="데이터셋 선택"
        className="flex flex-col gap-2 rounded border border-border bg-white p-4"
      >
        <h2 className="text-section-title text-primary">데이터셋 선택</h2>
        {dsError && <ErrorState title="데이터셋 목록을 불러올 수 없습니다" />}
        {dsLoading && <Skeleton height={48} />}
        {datasets && datasets.length > 0 && (
          <RadioGroup
            name="export-dataset"
            value={datasetId !== null ? String(datasetId) : undefined}
            onChange={(v) => setDatasetId(Number.parseInt(v, 10))}
            orientation="vertical"
            options={datasets.map((d) => ({
              value: String(d.id),
              label: `${d.name} (영상 ${d.videoCount.toLocaleString('ko-KR')}건)`,
            }))}
          />
        )}
      </section>

      <section
        aria-label="형식 선택"
        className="flex flex-col gap-2 rounded border border-border bg-white p-4"
      >
        <h2 className="text-section-title text-primary">형식 선택</h2>
        <RadioGroup
          name="export-format"
          value={format}
          onChange={(v) => setFormat(v as Format)}
          options={FORMAT_OPTIONS}
        />
      </section>

      <section
        aria-label="NAS 경로 입력"
        className="flex flex-col gap-2 rounded border border-border bg-white p-4"
      >
        <Input
          label="NAS 경로"
          placeholder="/mnt/nas/exports/..."
          value={nasPath}
          onChange={(e) => handlePathChange(e.target.value)}
          error={pathError}
        />
      </section>

      <div className="flex justify-end gap-2">
        <Button
          data-testid="export-preview-btn"
          variant="outline"
          onClick={handlePreview}
          disabled={!canSubmit}
          loading={isPending}
        >
          미리보기
        </Button>
        <Button
          data-testid="export-execute-btn"
          variant="primary"
          onClick={handleExecute}
          disabled={!canSubmit}
          loading={isPending}
        >
          내보내기 실행
        </Button>
      </div>

      {preview && (
        <section
          data-testid="export-preview"
          aria-label="미리보기"
          className="rounded border border-border bg-white p-4"
        >
          <h2 className="mb-2 text-section-title text-primary">미리보기 결과</h2>
          <dl className="grid grid-cols-3 gap-3">
            <Field label="영상">{preview.videoCount.toLocaleString('ko-KR')}건</Field>
            <Field label="프레임">{preview.frameCount.toLocaleString('ko-KR')}장</Field>
            <Field label="라벨">{preview.labelCount.toLocaleString('ko-KR')}건</Field>
          </dl>
        </section>
      )}
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-sub text-neutral">{label}</dt>
      <dd className="text-body text-primary">{children}</dd>
    </div>
  );
}
