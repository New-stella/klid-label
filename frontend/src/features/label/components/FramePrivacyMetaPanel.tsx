// Phase 4 — 프레임 단위 개인정보(익명·가명·개인정보 포함여부) 입력 패널.
//
// 라벨링 RightPanel '메타' 탭(내부 채널만)에 삽입되는 접이식 편집 패널.
// - useFramePrivacyMeta(srcSn) 로 BE 프리필값(수동값 우선, 없으면 비식별 기본상수) 로드 → 체크박스 바인딩
//   ★프리필 원천 정정(2026-08-03): BE 가 더 이상 영상 개인정보 유형(PRVC_TYPE_CD)에서 파생하지 않고
//   export 가 실제로 쓰는 비식별 기본상수(ExportPrivacyPolicy)를 내려준다. 구 파생은 업로드가 PRVC
//   고정이 된 뒤 사실상 항상 익명여부=N 을 보여줬는데 파일에는 Y 가 실려 화면과 산출물이 어긋났고,
//   영상 패널(기본 Y)과 프레임 패널(기본 N)이 같은 개념에 다른 기본값을 표시했다.
//   FE 는 상수를 하드코딩하지 않는다 — BE 프리필을 그대로 표시한다.
// - 작업자가 체크박스로 Y/N 토글 후 저장 → useUpdateFramePrivacyMeta(srcSn) (전체 교체 PUT)
// - dirty 체크: 원본과 다를 때만 저장 활성. 프레임(srcSn) 전환 시 로컬상태 동기화.
//
// 값(BE @Pattern("\\A[YN]\\z") 정합): 체크=Y, 미체크=N. 자유입력 없음(체크박스로만 토글).
// ★수동값 반영 범위 안내(2026-08-03 확정 — 구 "원본에만 반영" 문구 폐기, 정확히 반전됨):
//   저장한 3필드는 <b>비식별(deid) 산출물의 image 블록에만</b> 반영된다. 원천(orgnl) 산출물은 비식별
//   처리 전이라 판정 자체를 하지 않으므로 빈값(null)이다. 헬프텍스트로 그대로 안내한다.
//   ★영상 단위 판정은 별개 패널(VideoPrivacyMetaPanel)이며 export 의 video 블록으로 나간다 —
//   두 값이 달라도 모순이 아니라 입도가 다른 두 사실이다.
//   근거·경위는 BE ExportPrivacyPolicy 클래스 주석 참조.
// 보안(저장형 XSS 방어): checkbox 상태만 바인딩(자유텍스트 없음) — React 기본 escape.
//   dangerouslySetInnerHTML 미사용.
// a11y: 각 체크박스에 <label htmlFor> ↔ id 연결.

import { useEffect, useState } from 'react';

import { Field, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';

import type { YnFlag } from '../api/framePrivacyMeta';
import { useFramePrivacyMeta, useUpdateFramePrivacyMeta } from '../hooks/useFramePrivacyMeta';
import { isUserDeterminedMetaValue } from '../utils/metaPromotion';

import { MetaReadonlyField, MetaSection, ynLabel } from './MetaSection';

export interface FramePrivacyMetaPanelProps {
  srcSn: number | undefined;
  /**
   * 읽기 전용 모드 — 체크박스·저장 버튼을 <b>렌더하지 않고</b> 판정값만 보여준다.
   *
   * ★기본값은 <b>편집 가능</b>이다(false). 기본값이 읽기 전용으로 새면 작업자가 개인정보
   * 판정을 입력하지 못한다. 검수 화면만 명시적으로 켠다.
   */
  readOnly?: boolean;
}

interface PrivacyForm {
  anonymity: boolean;
  pseudonymity: boolean;
  privacyIncluded: boolean;
}

const FIELDS = [
  {
    key: 'anonymity' as const,
    id: 'privacy-anonymity',
    label: '익명여부',
  },
  {
    key: 'pseudonymity' as const,
    id: 'privacy-pseudonymity',
    label: '가명여부',
  },
  {
    key: 'privacyIncluded' as const,
    id: 'privacy-included',
    label: '개인정보 포함여부',
  },
];

/** Y → true, 그 외(N/null) → false. */
function ynToBool(v: YnFlag): boolean {
  return v === 'Y';
}

/** true → 'Y', false → 'N'. */
function boolToYn(v: boolean): 'Y' | 'N' {
  return v ? 'Y' : 'N';
}

/**
 * 저장 시 필드별 전송값 결정 — 파생값의 조용한 MANUAL 승격 방지(BE 전체 교체 계약).
 * FramePrivacyMetaResponse 에는 source(MANUAL/DERIVED) 필드가 없으므로(BE 미제공) touched
 * 추적으로 판단한다: 사용자가 실제 토글한(현재값≠원본 프리필값) 필드만 Y/N 을 전송하고,
 * 손대지 않은 필드는 null 로 보내 BE 가 파생 프리필을 유지하게 한다.
 * (후속 여지: BE 가 *Source 를 내려주면 EnvironmentMetaPanel 과 동일한 source 기반 판정으로 강화 가능.)
 *
 * ★판정 자체는 {@link isUserDeterminedMetaValue} 가 <b>단독 소유</b>한다. 이 창구만 출처를 받지
 *   못하므로 `null` 을 넘기며, 그 경우 판정은 «손댔는가» 한 축으로 줄어든다 — 지금 동작 그대로다.
 *   BE 가 출처를 내려주기 시작하면 이 인자만 바꾸면 된다(판정식을 다시 쓰지 않는다).
 */
function resolveField(current: boolean, original: boolean): YnFlag {
  return isUserDeterminedMetaValue(current, original, null) ? boolToYn(current) : null;
}

export function FramePrivacyMetaPanel({
  srcSn,
  readOnly = false,
}: FramePrivacyMetaPanelProps) {
  const { data, isLoading } = useFramePrivacyMeta(srcSn);
  const update = useUpdateFramePrivacyMeta(srcSn);

  const [form, setForm] = useState<PrivacyForm>({
    anonymity: false,
    pseudonymity: false,
    privacyIncluded: false,
  });

  // 프레임 전환(data 변경) 시 로컬 폼 상태 동기화.
  useEffect(() => {
    setForm({
      anonymity: ynToBool(data?.anonymity ?? null),
      pseudonymity: ynToBool(data?.pseudonymity ?? null),
      privacyIncluded: ynToBool(data?.privacyIncluded ?? null),
    });
  }, [data?.anonymity, data?.pseudonymity, data?.privacyIncluded, srcSn]);

  const original: PrivacyForm = {
    anonymity: ynToBool(data?.anonymity ?? null),
    pseudonymity: ynToBool(data?.pseudonymity ?? null),
    privacyIncluded: ynToBool(data?.privacyIncluded ?? null),
  };
  const dirty =
    form.anonymity !== original.anonymity ||
    form.pseudonymity !== original.pseudonymity ||
    form.privacyIncluded !== original.privacyIncluded;
  const disabled = srcSn === undefined || isLoading || update.isPending;
  const canSave = srcSn !== undefined && dirty && !update.isPending;

  const toggle = (key: keyof PrivacyForm) => {
    // 불변성 유지 — 새 객체 생성.
    setForm((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const handleSave = () => {
    if (!canSave) return;
    update.mutate({
      anonymity: resolveField(form.anonymity, original.anonymity),
      pseudonymity: resolveField(form.pseudonymity, original.pseudonymity),
      privacyIncluded: resolveField(form.privacyIncluded, original.privacyIncluded),
    });
  };

  // 읽기 전용(검수 화면) — 판정값만 보여준다. 비활성 체크박스를 두지 않고 렌더 자체를 하지 않는다.
  if (readOnly) {
    // ★검수 화면의 구역 이름은 라벨링과 **일부러 다르다** — 검수는 「…검토」로 끝난다
    //   (SCREEN-019). 두 이름을 같게 「통일」하면 확정된 사양을 되돌리는 것이다.
    return (
      <MetaSection title="개인정보 판정 검토 (프레임 축)">
        <div className="space-y-1.5" data-testid="frame-privacy-meta-readonly">
          {FIELDS.map((f) => (
            <MetaReadonlyField
              key={f.key}
              label={f.label}
              // BE 원본값을 그대로 읽는다(편집 폼 상태가 아니다) — 읽기 전용에는 편집이 없다.
              value={ynLabel(data?.[f.key] ?? null)}
            />
          ))}
        </div>
        <p className="text-[11px] leading-snug text-gray-500">
          이 프레임 기준의 판정입니다. 영상 전체 기준 판정은 위 영상 개인정보에서 확인하세요.
        </p>
      </MetaSection>
    );
  }

  return (
    <MetaSection title="개인정보(프레임)">
      <div className="space-y-1.5">
        {FIELDS.map((f) => (
          <Field key={f.key} orientation="horizontal">
            <Checkbox
              id={f.id}
              checked={form[f.key]}
              onCheckedChange={() => toggle(f.key)}
              disabled={disabled}
            />
            <FieldLabel>{f.label}</FieldLabel>
          </Field>
        ))}
      </div>

      <p className="text-[11px] leading-snug text-gray-500">
        이 프레임 기준의 판정입니다. 저장한 값은 비식별 학습데이터에 반영되며, 원천 학습데이터는
        비식별 처리 전이라 판정하지 않습니다. 직접 지정하지 않은 항목은 기본값이 그대로 적용됩니다.
      </p>

      {update.isError && (
        <p className="text-caption text-danger" role="alert">
          개인정보 메타 저장에 실패했습니다. 다시 시도해 주세요.
        </p>
      )}

      <Button
        size="sm"
        fullWidth
        onClick={handleSave}
        disabled={!canSave}
        loading={update.isPending}
      >
        저장
      </Button>
    </MetaSection>
  );
}
