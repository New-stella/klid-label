import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Field, FieldLabel } from '@/components/common/Field';
import { Input } from '@/components/common/Input';
import { RadioGroup } from '@/components/common/RadioGroup';

/** 이 산출물의 비식별 여부 — 기본은 원본이다. */
export const SOURCE_ORIGINAL = 'original';
export const SOURCE_DEIDENTIFIED = 'deidentified';

const FIELD_LABEL_CLASS = 'text-label font-semibold text-gray-900';
const FIELD_HELP_CLASS = 'text-caption text-gray-600';

export interface ImportScanFormProps {
  folderPath: string;
  videoPath: string;
  /** 비식별 여부 — `SOURCE_ORIGINAL` 이 기본값이다. */
  source: string;
  scanning: boolean;
  onFolderPathChange: (value: string) => void;
  onVideoPathChange: (value: string) => void;
  onSourceChange: (value: string) => void;
  onScan: () => void;
}

/**
 * 가져오기 입력 — 산출물 폴더 경로·원본 영상 경로·비식별 여부를 받는다.
 *
 * 비식별 여부의 기본값은 **원본**이다. 잘못 고르면 비식별되지 않은 화면이 학습데이터로 나가고
 * 승인 이후에는 되돌릴 수단이 사실상 없다. 그래서 비식별이 끝난 것으로 고를 때만 주의 문구가
 * 나타난다 — 기본값 쪽에는 경고가 없다(늘 뜨는 경고는 읽히지 않는다).
 *
 * 경로의 허용 저장소 범위 판정은 서버가 소유한다. 화면은 비어 있는지만 보고 막는다.
 *
 * @design SCREEN-039
 * @design API-205
 */
export function ImportScanForm({
  folderPath,
  videoPath,
  source,
  scanning,
  onFolderPathChange,
  onVideoPathChange,
  onSourceChange,
  onScan,
}: ImportScanFormProps) {
  const deidentified = source === SOURCE_DEIDENTIFIED;

  return (
    <section
      aria-labelledby="import-input-heading"
      data-testid="import-scan-form"
      className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="import-input-heading" className="text-title-sm text-gray-900">
        외부 산출물 가져오기
      </h2>

      <Field className="gap-1.5">
        <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="import-folder-path" required>
          산출물 폴더 경로
        </FieldLabel>
        <Input
          id="import-folder-path"
          type="text"
          value={folderPath}
          placeholder="예: /nas-storage/handover/00000073"
          onChange={(e) => onFolderPathChange(e.target.value)}
        />
        <p className={FIELD_HELP_CLASS}>허용된 저장소 범위 밖이면 서버가 받지 않습니다.</p>
      </Field>

      <Field className="gap-1.5">
        <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="import-video-path">
          원본 영상 경로 (선택)
        </FieldLabel>
        <Input
          id="import-video-path"
          type="text"
          value={videoPath}
          placeholder="비우면 프레임과 라벨만 가져온다"
          onChange={(e) => onVideoPathChange(e.target.value)}
        />
        <p className={FIELD_HELP_CLASS}>비우면 프레임과 라벨만 가져옵니다.</p>
      </Field>

      <Field className="gap-1.5">
        <FieldLabel className={FIELD_LABEL_CLASS}>이 산출물은</FieldLabel>
        <RadioGroup
          name="import-source"
          value={source}
          onChange={onSourceChange}
          options={[
            { value: SOURCE_ORIGINAL, label: '원본이다' },
            { value: SOURCE_DEIDENTIFIED, label: '비식별이 끝난 것이다' },
          ]}
        />
      </Field>

      {deidentified && (
        <Alert
          variant="error"
          title="비식별이 끝난 것으로 가져옵니다"
          data-testid="import-deident-warning"
        >
          이 산출물은 그대로 학습데이터로 나갑니다. 잘못 고르면 승인 뒤에 되돌릴 수단이 사실상
          없습니다.
        </Alert>
      )}

      <div className="flex justify-end">
        <Button
          variant="primary"
          data-testid="import-scan-button"
          loading={scanning}
          disabled={folderPath.trim().length === 0}
          onClick={onScan}
        >
          검사
        </Button>
      </div>
    </section>
  );
}
