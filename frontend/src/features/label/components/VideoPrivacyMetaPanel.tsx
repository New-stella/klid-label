// 영상 단위 개인정보(익명·가명·개인정보 포함여부) 입력 패널.
//
// 라벨링 RightPanel '메타' 탭(내부 채널만)에 삽입되는 접이식 편집 패널.
// - useVideoPrivacyMeta(rawSn) 로 BE 프리필값(수동값 우선, 없으면 비식별 기본상수) 로드 → 체크박스 바인딩
// - 검수자/작업자가 체크박스로 Y/N 토글 후 저장 → useUpdateVideoPrivacyMeta(rawSn) (전체 교체 PUT)
// - dirty 체크: 원본과 다를 때만 저장 활성. 영상(rawSn) 전환 시 로컬상태 동기화.
//
// ★프레임 단위 패널(FramePrivacyMetaPanel)과 <입도가 다른 별개 축>이다:
//   이 패널의 값은 export JSON 의 video 블록, 프레임 패널의 값은 image 블록으로 나간다.
//   두 값이 달라도 모순이 아니라 "영상엔 있지만 이 프레임엔 없다"는 서로 다른 사실이다.
// ★반영 범위: 저장값은 <비식별(deid) 학습데이터에만> 실린다. 원천(orgnl) 학습데이터는 비식별 처리 전이라
//   판정하지 않는다(빈값). 헬프텍스트로 그대로 안내한다.
// ★기본상수는 BE 가 프리필(DERIVED)해 내려주며 FE 는 하드코딩하지 않는다.
//
// 값(BE @Pattern("\\A[YN]\\z") 정합): 체크=Y, 미체크=N. 자유입력 없음(체크박스로만 토글).
// 보안(저장형 XSS 방어): checkbox 상태만 바인딩(자유텍스트 없음) — React 기본 escape.
//   dangerouslySetInnerHTML 미사용.
// a11y: 각 체크박스에 <label htmlFor> ↔ id 연결. 출처는 색상이 아닌 텍스트로 표기.

import { useEffect, useState } from 'react';

import { Field, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';

import type { PrivacyMetaSource, YnFlag } from '../api/videoPrivacyMeta';
import { useVideoPrivacyMeta, useUpdateVideoPrivacyMeta } from '../hooks/useVideoPrivacyMeta';

import { MetaSection } from './MetaSection';

export interface VideoPrivacyMetaPanelProps {
  rawSn: number | undefined;
}

interface PrivacyForm {
  anonymity: boolean;
  pseudonymity: boolean;
  privacyIncluded: boolean;
}

const FIELDS = [
  { key: 'anonymity' as const, id: 'video-privacy-anonymity', label: '익명여부' },
  { key: 'pseudonymity' as const, id: 'video-privacy-pseudonymity', label: '가명여부' },
  { key: 'privacyIncluded' as const, id: 'video-privacy-included', label: '개인정보 포함여부' },
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
 * 저장 시 필드별 전송값 결정 — 프리필값의 조용한 MANUAL 승격 방지(BE 전체 교체 계약).
 * 사용자가 값을 바꿨거나(touched=현재값≠원본값) 원본이 이미 수동값(MANUAL)이면 값을 전송하고,
 * 손대지 않은 프리필(DERIVED)은 null 로 보내 BE 가 기본상수 프리필 상태를 유지하게 한다.
 * (EnvironmentMetaPanel.resolveField 와 동일 규율 — 추정값이 사람의 판정으로 굳는 것을 막는다.)
 */
function resolveField(current: boolean, original: boolean, source: PrivacyMetaSource): YnFlag {
  if (current !== original || source === 'MANUAL') return boolToYn(current);
  return null;
}

export function VideoPrivacyMetaPanel({ rawSn }: VideoPrivacyMetaPanelProps) {
  const { data, isLoading } = useVideoPrivacyMeta(rawSn);
  const update = useUpdateVideoPrivacyMeta(rawSn);

  const [form, setForm] = useState<PrivacyForm>({
    anonymity: false,
    pseudonymity: false,
    privacyIncluded: false,
  });

  // 영상 전환(data 변경) 시 로컬 폼 상태 동기화.
  useEffect(() => {
    setForm({
      anonymity: ynToBool(data?.anonymity ?? null),
      pseudonymity: ynToBool(data?.pseudonymity ?? null),
      privacyIncluded: ynToBool(data?.privacyIncluded ?? null),
    });
  }, [data?.anonymity, data?.pseudonymity, data?.privacyIncluded, rawSn]);

  const original: PrivacyForm = {
    anonymity: ynToBool(data?.anonymity ?? null),
    pseudonymity: ynToBool(data?.pseudonymity ?? null),
    privacyIncluded: ynToBool(data?.privacyIncluded ?? null),
  };
  const sources: Record<keyof PrivacyForm, PrivacyMetaSource> = {
    anonymity: data?.anonymitySource ?? null,
    pseudonymity: data?.pseudonymitySource ?? null,
    privacyIncluded: data?.privacyIncludedSource ?? null,
  };
  const dirty =
    form.anonymity !== original.anonymity ||
    form.pseudonymity !== original.pseudonymity ||
    form.privacyIncluded !== original.privacyIncluded;
  const disabled = rawSn === undefined || isLoading || update.isPending;
  const canSave = rawSn !== undefined && dirty && !update.isPending;

  const toggle = (key: keyof PrivacyForm) => {
    // 불변성 유지 — 새 객체 생성.
    setForm((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const handleSave = () => {
    if (!canSave) return;
    update.mutate({
      anonymity: resolveField(form.anonymity, original.anonymity, sources.anonymity),
      pseudonymity: resolveField(form.pseudonymity, original.pseudonymity, sources.pseudonymity),
      privacyIncluded: resolveField(
        form.privacyIncluded,
        original.privacyIncluded,
        sources.privacyIncluded,
      ),
    });
  };

  return (
    <MetaSection title="개인정보(영상)">
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
            {sources[f.key] === 'DERIVED' && (
              <span className="text-[10px] text-gray-500">기본값</span>
            )}
          </Field>
        ))}
      </div>

      <p className="text-[11px] leading-snug text-gray-500">
        영상 전체 기준의 판정입니다. 저장한 값은 비식별 학습데이터에 반영되며, 원천 영상은 비식별
        처리 전이라 판정하지 않습니다. 프레임별로 다르면 아래 프레임 개인정보에서 따로 지정하세요.
      </p>

      {update.isError && (
        <p className="text-caption text-danger" role="alert">
          영상 개인정보 메타 저장에 실패했습니다. 다시 시도해 주세요.
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
