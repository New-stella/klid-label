import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { deidentConfigSchema, type DeidentConfigForm } from '../schemas';
import { ConfigKey, type ConfigMap } from '../types';

interface Props {
  configs: ConfigMap;
}

/**
 * 마스킹 방식 선택지 — **라벨과 코드값의 대응은 외부 규격이다. 절대 바꾸지 말 것.**
 *
 * 값이 어긋나면 운영자가 고른 것과 다른 마스킹이 실행된다. `1` 은 벤더 미할당이라 목록에 없으며,
 * 연속 범위가 아니므로 min/max 로 다루지 않는다.
 */
const MASKING_TYPE_OPTIONS = [
  { value: 0, label: '색상' },
  { value: 2, label: '모자이크' },
  { value: 3, label: '블러' },
] as const;

/** 프레임 저장 여부 선택지 (0 저장 안 함 / 1 저장). */
const DB_SAVE_OPTIONS = [
  { value: 0, label: '저장 안 함' },
  { value: 1, label: '저장' },
] as const;

/** 저장값이 없을 때의 표시 기본값 — BE 시드(V178) · 규격 기본값과 동일하다. */
const DEFAULT_MASKING_TYPE = 0;
const DEFAULT_MASKING_RANGE = 1;
const DEFAULT_DB_SAVE = 0;

const SELECT_CLASS = `w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-body ${KRDS_FOCUS}`;

/**
 * R9 비식별 옵션 카드 — REVIEWER 가 비식별 처리 옵션을 조정한다.
 *
 * ① 마스킹 방식   : 색상(0) / 모자이크(2) / 블러(3)
 * ② 마스킹 범위   : 0.5 ~ 2.0 배율 (슬라이더, step 0.1)
 * ③ 프레임 저장 여부 : 저장 안 함(0) / 저장(1)
 *
 * ⚠ **dotted 키와 폼 필드 이름을 분리한다.** BE 설정 키는 `kpst.deid.masking-type` 처럼 점이
 * 들어가는데, react-hook-form 은 필드 이름의 점을 중첩 객체 경로로 해석한다. 그래서 폼 필드는
 * 점 없는 별칭(`maskingType` 등)을 쓰고 **전송 시점에만** 실제 dotted 키로 매핑한다.
 * 반면 `configs`(ConfigMap) 의 키는 서버가 준 dotted 원문 그대로다.
 *
 * ⚠ 반영 지연: 저장은 즉시 반영되지만 설정 캐시 TTL 이 60초이고 서버가 2노드라, 다른 노드는
 * 최대 60초 뒤에 새 값으로 위탁한다.
 *
 * 보안: zod 스키마로 열거·범위 검증(1차) → BE 재검증(2차). 접근성: label htmlFor 연결.
 */
export function DeidentConfigCard({ configs }: Props) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { mutate, isPending } = useUpdateConfig({
    onSuccess: () => pushToast({ variant: 'success', message: '비식별 옵션 저장됨' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다' }),
  });

  const storedMaskingType = configs[ConfigKey.KPST_DEID_MASKING_TYPE];
  const storedMaskingRange = configs[ConfigKey.KPST_DEID_MASKING_RANGE];
  const storedDbSave = configs[ConfigKey.KPST_DEID_DB_SAVE];

  /*
   * 저장값 → 폼 값. **선택지에 없는 값은 기본값으로 정규화**한다.
   *
   * DB 를 수기로 고쳐 허용목록 밖 값(예: 벤더 미할당 1)이 들어가 있으면, 그대로 두면
   * select 가 어떤 option 과도 매치되지 않아 화면은 첫 항목을 보여주는데 폼 상태는 그 값을
   * 들고 있게 된다(다른 항목만 고쳐 저장해도 검증에서 막혀 아무것도 저장되지 않는다).
   * BE 도 같은 경우 규격 기본값으로 폴백해 위탁하므로, 화면이 그 실제 동작을 그대로 보여준다.
   */
  const maskingTypeValue = (
    MASKING_TYPE_OPTIONS.some((o) => o.value === storedMaskingType)
      ? storedMaskingType
      : DEFAULT_MASKING_TYPE
  ) as DeidentConfigForm['maskingType'];
  const maskingRangeValue = storedMaskingRange ?? DEFAULT_MASKING_RANGE;
  const dbSaveValue = (
    DB_SAVE_OPTIONS.some((o) => o.value === storedDbSave) ? storedDbSave : DEFAULT_DB_SAVE
  ) as DeidentConfigForm['dbSave'];

  const {
    register,
    handleSubmit,
    watch,
    formState: { isDirty, errors, dirtyFields },
    reset,
  } = useForm<DeidentConfigForm>({
    resolver: zodResolver(deidentConfigSchema),
    defaultValues: {
      maskingType: maskingTypeValue,
      maskingRange: maskingRangeValue,
      dbSave: dbSaveValue,
    },
  });

  // 서버 값 도착 시 폼 동기화
  useEffect(() => {
    reset({
      maskingType: maskingTypeValue,
      maskingRange: maskingRangeValue,
      dbSave: dbSaveValue,
    });
  }, [maskingTypeValue, maskingRangeValue, dbSaveValue, reset]);

  const maskingRange = watch('maskingRange');

  // 변경된 키만 전송한다 — 다른 설정 카드와 동일 원칙.
  // 폼 별칭 → BE dotted 키 매핑은 이 지점 한 곳에서만 한다.
  const onSubmit = (values: DeidentConfigForm) => {
    if (dirtyFields.maskingType) {
      mutate({ key: ConfigKey.KPST_DEID_MASKING_TYPE, value: values.maskingType });
    }
    if (dirtyFields.maskingRange) {
      mutate({ key: ConfigKey.KPST_DEID_MASKING_RANGE, value: values.maskingRange });
    }
    if (dirtyFields.dbSave) {
      mutate({ key: ConfigKey.KPST_DEID_DB_SAVE, value: values.dbSave });
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
        <div className="flex items-center justify-between border-b border-gray-100 pb-3">
          <h3 className="text-title-sm font-semibold text-gray-700">비식별 옵션</h3>
          <Button
            type="submit"
            variant="primary"
            size="sm"
            loading={isPending}
            disabled={!isDirty || isPending}
          >
            저장
          </Button>
        </div>

        {/* ① 마스킹 방식 (0 색상 / 2 모자이크 / 3 블러) */}
        <div className="space-y-2">
          <label className="block text-label font-medium text-gray-700" htmlFor="deident-masking-type">
            마스킹 방식
          </label>
          <select
            id="deident-masking-type"
            className={SELECT_CLASS}
            aria-invalid={errors.maskingType ? 'true' : 'false'}
            {...register('maskingType', { valueAsNumber: true })}
          >
            {MASKING_TYPE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
          <p className="text-caption text-gray-600">
            비식별 처리 시 개인정보 영역을 가리는 방식입니다. 다음 비식별 처리부터 적용됩니다.
          </p>
          {errors.maskingType && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.maskingType.message}
            </p>
          )}
        </div>

        {/* ② 마스킹 범위 (0.5 ~ 2.0 배율) */}
        <div className="space-y-2">
          <label
            className="flex items-center justify-between text-label"
            htmlFor="deident-masking-range"
          >
            <span className="font-medium text-gray-700">마스킹 범위</span>
            <span className="text-primary-600 font-semibold tabular-nums">
              {Number(maskingRange).toFixed(1)}배
            </span>
          </label>
          <input
            id="deident-masking-range"
            type="range"
            min={0.5}
            max={2}
            step={0.1}
            aria-invalid={errors.maskingRange ? 'true' : 'false'}
            className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            {...register('maskingRange', { valueAsNumber: true })}
          />
          <div className="flex justify-between text-caption text-gray-600">
            <span>좁게 (0.5배)</span>
            <span>넓게 (2.0배)</span>
          </div>
          <p className="text-caption text-gray-600">
            값이 클수록 가리는 영역이 넓어져 개인정보가 남을 가능성은 줄지만 화면이 더 많이
            가려집니다. (0.5~2.0배)
          </p>
          {errors.maskingRange && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.maskingRange.message}
            </p>
          )}
        </div>

        {/* ③ 프레임 저장 여부 (0 저장 안 함 / 1 저장) */}
        <div className="space-y-2">
          <label className="block text-label font-medium text-gray-700" htmlFor="deident-db-save">
            프레임 저장 여부
          </label>
          <select
            id="deident-db-save"
            className={SELECT_CLASS}
            aria-invalid={errors.dbSave ? 'true' : 'false'}
            {...register('dbSave', { valueAsNumber: true })}
          >
            {DB_SAVE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
          <p className="text-caption text-gray-600">
            비식별 처리 과정에서 추출한 프레임을 처리 시스템에 남길지 여부입니다. 필요하지 않으면
            저장하지 않는 편이 안전합니다.
          </p>
          {errors.dbSave && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.dbSave.message}
            </p>
          )}
        </div>
      </div>
    </form>
  );
}
