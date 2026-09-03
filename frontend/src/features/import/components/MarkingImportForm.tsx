import { useState } from 'react';
import { FolderSearch } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Field, FieldError, FieldLabel } from '@/components/common/Field';
import { Input } from '@/components/common/Input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { useEventTypes } from '@/features/eventType/hooks';

import {
  validateMarkingMeta,
  type MarkingMetaDraft,
} from '../markingMeta';
import { MarkingPrivacyType } from '../markingTypes';

import { ImportPathPickerModal } from './ImportPathPickerModal';

const FIELD_LABEL_CLASS = 'text-label font-semibold text-gray-900';
const FIELD_HELP_CLASS = 'text-caption text-gray-600';

/** 개인정보 유형 선택지 — 「미상」은 없다(사람이 직접 고르는 자리다). */
const PRIVACY_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: MarkingPrivacyType.ANONY, label: '비식별 불필요 (ANONY)' },
  { value: MarkingPrivacyType.PRVC, label: '개인정보 포함 (PRVC)' },
  { value: MarkingPrivacyType.PSDO, label: '가명처리 (PSDO)' },
];

export interface MarkingImportFormProps {
  folderPath: string;
  meta: MarkingMetaDraft;
  scanning: boolean;
  onFolderPathChange: (value: string) => void;
  onMetaChange: (next: MarkingMetaDraft) => void;
  onScan: () => void;
}

/**
 * 폴더에서 일괄 올리기 — 폴더 위치와 일괄 공통 정보를 받아 검사를 요청한다.
 *
 * <p>공통 정보는 <b>마킹 문서에서 얻을 수 없는 값만</b> 받으며 항목마다 같은 값으로 붙는다.
 * 그래서 한 폴더에 여러 카메라가 섞여 있으면 폴더를 나눠 따로 올려야 한다 — 그 사실을 이
 * 자리에서 알린다.
 *
 * <p>★영상 식별자는 받지 않는다. 영상 파일 이름에서 얻으므로 사람이 지정하면 파일과 어긋난
 * 값이 들어간다.
 *
 * <p>여기서 보는 것은 <b>형식뿐</b>이고 서버가 같은 규칙을 다시 본다. 화면이 먼저 걸러 주는
 * 이유는 다 고르고 난 뒤에야 거부되는 동선을 만들지 않기 위해서다.
 *
 * <p>폴더 위치는 직접 적을 수도 있고 찾아보기로 고를 수도 있다(API-221). 고르는 길은 적는
 * 길을 대신하지 않으며, 허용 저장소 범위 판정은 어느 쪽이든 서버가 소유한다.
 *
 * @design SCREEN-039
 * @design API-216
 * @design API-221
 */
export function MarkingImportForm({
  folderPath,
  meta,
  scanning,
  onFolderPathChange,
  onMetaChange,
  onScan,
}: MarkingImportFormProps) {
  const [pickerOpen, setPickerOpen] = useState(false);
  /** 사람이 손댄 자리 — 손대기 전에는 「아직 안 채웠다」를 오류로 알리지 않는다. */
  const [touched, setTouched] = useState<Partial<Record<keyof MarkingMetaDraft, boolean>>>({});
  const { data: eventTypes, isLoading: eventTypesLoading } = useEventTypes();

  const errors = validateMarkingMeta(meta);
  const set = (key: keyof MarkingMetaDraft, value: string) => {
    setTouched((t) => ({ ...t, [key]: true }));
    onMetaChange({ ...meta, [key]: value });
  };
  const errorOf = (key: keyof MarkingMetaDraft) => (touched[key] ? errors[key] : undefined);

  return (
    <section
      aria-labelledby="marking-import-heading"
      data-testid="marking-import-form"
      className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="marking-import-heading" className="text-title-sm text-gray-900">
        폴더에서 일괄 올리기
      </h2>

      <Field className="gap-1.5">
        <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="marking-folder-path" required>
          폴더 위치
        </FieldLabel>
        <Input
          id="marking-folder-path"
          type="text"
          value={folderPath}
          placeholder="예: /nas-storage/handover/marking-20260706"
          onChange={(e) => onFolderPathChange(e.target.value)}
        />
        <div className="flex items-center justify-between gap-2">
          <p className={FIELD_HELP_CLASS}>
            그 아래를 재귀로 훑어 마킹 문서와 영상의 짝을 찾습니다. 허용된 저장소 범위 밖이면
            서버가 받지 않습니다.
          </p>
          <Button
            variant="secondary"
            size="sm"
            leftIcon={FolderSearch}
            data-testid="marking-folder-browse"
            aria-label="폴더 찾아보기"
            onClick={() => setPickerOpen(true)}
          >
            찾아보기
          </Button>
        </div>
      </Field>

      <fieldset className="flex flex-col gap-4 rounded-md border border-gray-200 p-4">
        <legend className="px-1 text-label font-semibold text-gray-900">일괄 공통 정보</legend>
        <p className={FIELD_HELP_CLASS} data-testid="marking-meta-note">
          마킹 문서에서 얻을 수 없는 값만 받으며 항목마다 같은 값으로 붙습니다. 한 폴더에 여러
          카메라가 섞여 있으면 폴더를 나눠 따로 올리세요. 영상 식별자는 영상 파일 이름에서 얻으므로
          받지 않습니다.
        </p>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <Field className="gap-1.5">
            <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="marking-event-type" required>
              이벤트 유형
            </FieldLabel>
            <Select
              value={meta.eventTypeCd}
              onValueChange={(v) => set('eventTypeCd', v)}
              disabled={eventTypesLoading}
            >
              <SelectTrigger id="marking-event-type" aria-busy={eventTypesLoading}>
                <SelectValue placeholder="고르지 않음" />
              </SelectTrigger>
              <SelectContent>
                {eventTypesLoading && (
                  <SelectItem value="__loading__" disabled>
                    불러오는 중…
                  </SelectItem>
                )}
                {(eventTypes ?? []).map((et) => (
                  <SelectItem key={et.categoryKey} value={et.categoryKey}>
                    {et.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldError>{errorOf('eventTypeCd')}</FieldError>
          </Field>

          <Field className="gap-1.5">
            <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="marking-privacy-type" required>
              개인정보 유형
            </FieldLabel>
            <Select value={meta.prvcTypeCd} onValueChange={(v) => set('prvcTypeCd', v)}>
              <SelectTrigger id="marking-privacy-type">
                <SelectValue placeholder="고르지 않음" />
              </SelectTrigger>
              <SelectContent>
                {PRIVACY_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value} value={opt.value}>
                    {opt.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldError>{errorOf('prvcTypeCd')}</FieldError>
          </Field>

          <Field className="gap-1.5">
            <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="marking-local-gov" required>
              지자체 코드
            </FieldLabel>
            <Input
              id="marking-local-gov"
              type="text"
              inputMode="numeric"
              maxLength={10}
              value={meta.localGovCd}
              placeholder="예: 4113500000"
              onChange={(e) => set('localGovCd', e.target.value)}
            />
            <FieldError>{errorOf('localGovCd')}</FieldError>
          </Field>

          <Field className="gap-1.5">
            <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="marking-cctv-id" required>
              CCTV ID
            </FieldLabel>
            <Input
              id="marking-cctv-id"
              type="text"
              maxLength={64}
              value={meta.cctvId}
              placeholder="예: CCTV_0001"
              onChange={(e) => set('cctvId', e.target.value)}
            />
            <FieldError>{errorOf('cctvId')}</FieldError>
          </Field>

          <Field className="gap-1.5">
            <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="marking-captured-at">
              촬영 일시 (선택)
            </FieldLabel>
            <Input
              id="marking-captured-at"
              type="datetime-local"
              value={meta.capturedAt}
              onChange={(e) => set('capturedAt', e.target.value)}
            />
            <p className={FIELD_HELP_CLASS}>
              마킹 문서에 없는 값입니다. 지정하지 않으면 비워 둡니다.
            </p>
          </Field>
        </div>
      </fieldset>

      <div className="flex justify-end">
        <Button
          variant="primary"
          data-testid="marking-scan-button"
          loading={scanning}
          disabled={folderPath.trim().length === 0}
          onClick={onScan}
        >
          폴더 검사
        </Button>
      </div>

      {/* 고른 값은 서버가 돌려준 실제 위치다 — 그 값을 그대로 입력칸에 넣어야 검사와 왕복이 맞다. */}
      <ImportPathPickerModal
        open={pickerOpen}
        mode="folder"
        onClose={() => setPickerOpen(false)}
        onSelect={(path) => {
          onFolderPathChange(path);
          setPickerOpen(false);
        }}
      />
    </section>
  );
}
