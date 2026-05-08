import { useState } from 'react';
import { Send, Info, ShieldCheck, FolderTree } from 'lucide-react';
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

const FORMAT_OPTIONS: { value: Format; label: string; description: string }[] = [
  {
    value: ExportFormat.COCO,
    label: 'COCO',
    description: 'JSON 포맷 — 바운딩박스·세그멘테이션 모두 지원',
  },
  {
    value: ExportFormat.YOLO,
    label: 'YOLO',
    description: 'TXT 포맷 — 정규화 좌표, YOLO v5/v8 학습 표준',
  },
  {
    value: ExportFormat.VIDEO_COT,
    label: '영상 + CoT',
    description: '생성형 AI 학습용 영상 + Chain-of-Thought 메타',
  },
];

/**
 * SCR-EXPORT-001 학습데이터셋 NAS 내보내기 (`/export`).
 *
 * UI/UX §4-12 (V1.x mock 시각 정합):
 * - 헤더 + 안내 문구 + 데이터셋 선택 + 형식 선택 + NAS 경로 + [미리보기][내보내기 실행]
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
    <section className="flex flex-col gap-5" data-testid="export-page">
      <PageHeader
        title="학습데이터셋 내보내기"
        description="검수 완료된 학습데이터셋을 NAS 경로로 내보냅니다."
        actions={
          <div className="flex items-center justify-center rounded-lg bg-green-50 p-2">
            <Send className="h-5 w-5 text-green-600" aria-hidden />
          </div>
        }
      />

      {/* 안내 — 검수 완료 영상만 내보내기 가능 */}
      <div className="flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2.5">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-blue-600" aria-hidden />
        <p className="text-sub text-blue-700">
          내보내기는 검수 완료(승인)된 데이터셋만 가능합니다. 비식별 처리 실패 영상은 BE에서
          자동으로 제외됩니다.
        </p>
      </div>

      <section
        aria-label="데이터셋 선택"
        className="flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-4"
      >
        <div className="flex items-center gap-2">
          <span className="flex h-6 w-6 items-center justify-center rounded-full bg-primary-600 text-sub font-bold text-white">
            1
          </span>
          <h2 className="text-section-title text-primary">데이터셋 선택</h2>
        </div>
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
        className="flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-4"
      >
        <div className="flex items-center gap-2">
          <span className="flex h-6 w-6 items-center justify-center rounded-full bg-primary-600 text-sub font-bold text-white">
            2
          </span>
          <h2 className="text-section-title text-primary">형식 선택</h2>
        </div>
        <RadioGroup
          name="export-format"
          value={format}
          onChange={(v) => setFormat(v as Format)}
          options={FORMAT_OPTIONS.map((f) => ({
            value: f.value,
            label: `${f.label} — ${f.description}`,
          }))}
        />
      </section>

      <section
        aria-label="NAS 경로 입력"
        className="flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-4"
      >
        <div className="flex items-center gap-2">
          <span className="flex h-6 w-6 items-center justify-center rounded-full bg-primary-600 text-sub font-bold text-white">
            3
          </span>
          <h2 className="text-section-title text-primary">NAS 경로</h2>
        </div>
        <Input
          label="NAS 경로"
          placeholder="/mnt/nas/exports/..."
          value={nasPath}
          onChange={(e) => handlePathChange(e.target.value)}
          error={pathError}
        />
        <div className="flex items-start gap-2 rounded-md bg-gray-50 px-3 py-2">
          <FolderTree className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gray-400" aria-hidden />
          <p className="text-sub text-gray-500">
            절대 경로(/)로 시작해야 하며, 상위 경로(`..`)는 허용되지 않습니다.
          </p>
        </div>
      </section>

      <div className="flex items-center justify-between">
        <p className="flex items-center gap-1.5 text-sub text-gray-500">
          <ShieldCheck className="h-3.5 w-3.5 text-primary-500" aria-hidden />
          비식별 처리된 영상만 전송됩니다.
        </p>
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
      </div>

      {preview && (
        <section
          data-testid="export-preview"
          aria-label="미리보기"
          className="rounded-lg border border-gray-200 bg-white p-4"
        >
          <h2 className="mb-3 text-section-title text-primary">미리보기 결과</h2>
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
    <div className="rounded-md bg-gray-50 p-3">
      <dt className="text-sub text-gray-500">{label}</dt>
      <dd className="text-page-title text-gray-900 tabular-nums">{children}</dd>
    </div>
  );
}
