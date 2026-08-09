import { useState, type ChangeEvent, type ReactNode } from 'react';

import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Input } from '@/components/common/Input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { Textarea } from '@/components/common/Textarea';
import {
  SRC_TYPES,
  VRFC_EVNT_TYPES,
  type TusFormState,
} from '@/features/upload/components/tusUploadForm';

/**
 * TUS 업로드 폼의 **입력 fieldset 4종** — 관제 인입 29컬럼 재현.
 *
 * `TusUploadPanel` 이 518줄이 되어 `component.md` 의 "400줄 초과 시 분리 필수" 를 위반했으므로
 * 입력부를 여기로 옮겼다. 상태는 패널이 소유하고 여기서는 `value`/`onChange` 만 받는다(제어
 * 컴포넌트) — fieldset 이 자체 상태를 들면 제출 시점의 값이 갈라진다.
 *
 * 라벨·보조문구는 `Field`/`FieldLabel`/`FieldDescription` 조립으로 붙인다(UI-099) — 입력
 * 프리미티브는 `label`/`hint` prop 을 갖지 않는다.
 */

export interface FieldsetProps {
  form: TusFormState;
  /** 텍스트/숫자 input 공용 핸들러 팩토리 — 패널이 소유한 setState 를 감싼다. */
  onField: (key: keyof TusFormState) => (e: ChangeEvent<HTMLInputElement>) => void;
  /** select·textarea 처럼 `HTMLInputElement` 가 아닌 요소용 직접 갱신. */
  onValue: (key: keyof TusFormState, value: string) => void;
  disabled: boolean;
}

const FIELDSET_CLASS = 'space-y-3 rounded-lg border border-gray-200 p-4';
const LEGEND_CLASS = 'px-1 text-body font-medium text-gray-800';

/**
 * 이 폼의 텍스트 입력 한 칸 = `Field` + `FieldLabel` + `Input`(+ `FieldDescription`).
 *
 * 같은 조립을 20여 번 반복하지 않기 위한 **이 파일 전용 조립 헬퍼**다 — 공통 프리미티브에
 * 라벨을 되돌리는 것이 아니라, 조립 결과를 지역에서 한 번만 쓰는 것이다.
 */
function TextField({
  label,
  hint,
  ...inputProps
}: { label: ReactNode; hint?: ReactNode } & React.ComponentProps<typeof Input>) {
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <Input {...inputProps} />
      {hint && <FieldDescription>{hint}</FieldDescription>}
    </Field>
  );
}

/** 검증이벤트유형 select 의 "직접 입력" 센티넬 — 전송값이 아니라 화면 모드 표식이다. */
export const VRFC_MANUAL_OPTION = '__manual__';

/**
 * 검증이벤트유형 — 외부 VLM 검증 API 의 `event_type`. [req: R7]
 *
 * **6종 프리셋 + 직접 입력**(2026-08-06 사용자 확정). 관제가 인입으로 보내주기 전까지 dev
 * 업로드에서 지정하는 입력이며, 벤더가 enum 을 넓히거나 우리가 모르는 값을 시험해야 할 때
 * 프리셋에 갇히지 않도록 자유 입력을 연다.
 *
 * - **전송값은 벤더 규격 그대로 소문자 원문**이다(라벨의 한글 병기는 화면 표시용).
 * - `VRFC_MANUAL_OPTION` 은 **화면 모드 표식**이라 전송되지 않는다 — 직접 입력 칸의 값이 전송된다.
 * - 최종 판정은 서버다. 이 화면은 UX 보조일 뿐이며 신뢰 경계가 아니다
 *   (BE `LsDataIngest.isVrfcEvntTypeFormatValid` 가 형식 위반이면 400).
 * - ★ **구 동작(6종 select 만) 폐기** — 그때는 BE 도 6종 allowlist 였다. 위탁 게이트가
 *   "조달값을 그대로 실어 항상 위탁"으로 반전(2026-08-06)한 뒤로 화면만 6종에 갇히는
 *   비대칭이 남아 함께 열었다.
 */
function VrfcEvntTypeField({
  form,
  onValue,
  disabled,
}: Pick<FieldsetProps, 'form' | 'onValue' | 'disabled'>) {
  const isPreset = VRFC_EVNT_TYPES.some((o) => o.value === form.vrfcEvntTypeCd);
  // 값이 있는데 프리셋에 없으면 직접 입력분이다(재렌더·복원에도 모드가 유지된다 — 별도
  // 컴포넌트 상태를 두면 부모가 값을 갈아끼울 때 모드와 값이 어긋난다).
  const [manualOpen, setManualOpen] = useState(false);
  const manual = manualOpen || (form.vrfcEvntTypeCd !== '' && !isPreset);

  const handleSelect = (next: string) => {
    if (next === VRFC_MANUAL_OPTION) {
      setManualOpen(true);
      // 프리셋에서 넘어왔으면 값을 비운다 — 남겨두면 "직접 입력"인데 프리셋 값이 전송된다.
      if (isPreset) onValue('vrfcEvntTypeCd', '');
      return;
    }
    setManualOpen(false);
    onValue('vrfcEvntTypeCd', next);
  };

  return (
    <div className="flex flex-col gap-1">
      <Field>
        <FieldLabel>검증이벤트유형</FieldLabel>
        <Select
          value={manual ? VRFC_MANUAL_OPTION : form.vrfcEvntTypeCd}
          onValueChange={handleSelect}
          disabled={disabled}
        >
          <SelectTrigger id="tus-vrfc-evnt-type">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {/* 프리셋 목록은 VRFC_EVNT_TYPES(FE 단일 진실원)를 그대로 펼친다 — 라벨/값을 여기
                복제하면 한쪽만 갱신돼 전송값이 갈라진다. */}
            <SelectItem value="">미지정 (AI 검증 위탁 생략)</SelectItem>
            {VRFC_EVNT_TYPES.map((o) => (
              <SelectItem key={o.value} value={o.value}>
                {o.label}
              </SelectItem>
            ))}
            <SelectItem value={VRFC_MANUAL_OPTION}>직접 입력</SelectItem>
          </SelectContent>
        </Select>
      </Field>
      {manual && (
        <TextField
          label="검증이벤트유형 직접 입력"
          hint="영문 소문자·숫자·밑줄 20자 이내 (예: earthquake). 벤더 규격 원문 그대로 전송됩니다"
          value={form.vrfcEvntTypeCd}
          onChange={(e) => onValue('vrfcEvntTypeCd', e.target.value)}
          disabled={disabled}
          maxLength={20}
        />
      )}
    </div>
  );
}

/** 식별 정보 — 필수 4종 + 촬영일시. */
export function IdentityFieldset({ form, onField, onValue, disabled }: FieldsetProps) {
  return (
    <fieldset className={FIELDSET_CLASS}>
      <legend className={LEGEND_CLASS}>식별 정보</legend>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <TextField
          label="영상 클립 ID *"
          hint="저장 파일명이 됩니다 (영문·숫자·_·- 64자)"
          value={form.vmsClipId}
          onChange={onField('vmsClipId')}
          disabled={disabled}
          autoComplete="off"
        />
        <TextField
          label="CCTV ID *"
          value={form.cctvId}
          onChange={onField('cctvId')}
          disabled={disabled}
          autoComplete="off"
        />
        <Field>
          <FieldLabel>출처유형 *</FieldLabel>
          <Select
            value={form.srcType}
            onValueChange={(v) => onValue('srcType', v)}
            disabled={disabled}
          >
            <SelectTrigger id="tus-src-type">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {/* SRC_TYPES(입력면 allowlist 4종) 그대로. */}
              {SRC_TYPES.map((o) => (
                <SelectItem key={o.value} value={o.value}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>
        <TextField
          label="지자체코드 *"
          hint="숫자 1~10자리"
          value={form.lclgvCd}
          onChange={onField('lclgvCd')}
          disabled={disabled}
          autoComplete="off"
        />
        <TextField
          label="촬영일시"
          type="datetime-local"
          value={form.shtDtLocal}
          onChange={onField('shtDtLocal')}
          disabled={disabled}
        />
      </div>
    </fieldset>
  );
}

/** 위치 · CCTV 제원 — 촬영 시점 값으로 고정 저장된다(카메라 교체 시 과거 영상 오염 방지). */
export function LocationFieldset({ form, onField, disabled }: FieldsetProps) {
  return (
    <fieldset className={FIELDSET_CLASS}>
      <legend className={LEGEND_CLASS}>위치 · CCTV 제원</legend>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <TextField
          label="지자체명"
          value={form.lclgvNm}
          onChange={onField('lclgvNm')}
          disabled={disabled}
        />
        <TextField
          label="기관코드"
          value={form.ogCd}
          onChange={onField('ogCd')}
          disabled={disabled}
        />
        <TextField
          label="CCTV명"
          value={form.cctvNm}
          onChange={onField('cctvNm')}
          disabled={disabled}
        />
        <TextField
          label="카메라 높이 (m)"
          type="number"
          step="0.1"
          value={form.cctvHgt}
          onChange={onField('cctvHgt')}
          disabled={disabled}
        />
        <TextField
          label="위도 (WGS84)"
          type="number"
          step="0.0000001"
          value={form.wgs84Lat}
          onChange={onField('wgs84Lat')}
          disabled={disabled}
        />
        <TextField
          label="경도 (WGS84)"
          type="number"
          step="0.0000001"
          value={form.wgs84Lot}
          onChange={onField('wgs84Lot')}
          disabled={disabled}
        />
        <TextField
          label="주감시방향 (도)"
          type="number"
          min={0}
          max={360}
          value={form.mainSurvPanAng}
          onChange={onField('mainSurvPanAng')}
          disabled={disabled}
        />
      </div>
    </fieldset>
  );
}

/** 이벤트 · 관제일지 — `MNTR_CN` 은 4000자라 헤더가 아니라 JSON 바디로 전송된다. */
export function EventFieldset({ form, onField, onValue, disabled }: FieldsetProps) {
  return (
    <fieldset className={FIELDSET_CLASS}>
      <legend className={LEGEND_CLASS}>이벤트 · 관제일지</legend>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <TextField
          label="이벤트 ID"
          hint="예: ABA_0001 (이벤트 유형코드가 아닙니다)"
          value={form.evntId}
          onChange={onField('evntId')}
          disabled={disabled}
        />
        <TextField
          label="이벤트명"
          value={form.evntNm}
          onChange={onField('evntNm')}
          disabled={disabled}
        />
        <VrfcEvntTypeField form={form} onValue={onValue} disabled={disabled} />
      </div>
      <Field>
        <FieldLabel>관제일지</FieldLabel>
        <Textarea
          className="min-h-[100px]"
          id="tus-mntr-cn"
          maxLength={4000}
          value={form.mntrCn}
          onChange={(e) => onValue('mntrCn', e.target.value)}
          disabled={disabled}
        />
      </Field>
    </fieldset>
  );
}

/** 영상 기술메타(선택) — 비운 키만 서버가 ffprobe 로 채운다(폴백은 키 단위). */
export function TechnicalMetaFieldset({ form, onField, disabled }: FieldsetProps) {
  return (
    <fieldset className={FIELDSET_CLASS}>
      <legend className={LEGEND_CLASS}>
        영상 기술메타 <span className="text-sub font-normal text-gray-500">(선택)</span>
      </legend>
      <p data-testid="tus-tech-meta-hint" className="text-sub text-gray-500">
        비워 두면 서버가 업로드된 파일에서 자동으로 추출합니다(ffprobe). 값을 입력한 항목만 입력값이
        그대로 사용되고, 비운 항목만 자동 추출됩니다.
      </p>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <TextField
          label="영상길이 (초)"
          type="number"
          min={0}
          value={form.vdoLenSec}
          onChange={onField('vdoLenSec')}
          disabled={disabled}
        />
        <TextField label="FPS" value={form.fps} onChange={onField('fps')} disabled={disabled} />
        <TextField
          label="프레임수"
          type="number"
          min={0}
          value={form.frmeCnt}
          onChange={onField('frmeCnt')}
          disabled={disabled}
        />
        <TextField
          label="가로 (px)"
          type="number"
          min={0}
          value={form.wdth}
          onChange={onField('wdth')}
          disabled={disabled}
        />
        <TextField
          label="세로 (px)"
          type="number"
          min={0}
          value={form.vrtc}
          onChange={onField('vrtc')}
          disabled={disabled}
        />
        <TextField
          label="해상도"
          hint="예: 1920x1080"
          value={form.resl}
          onChange={onField('resl')}
          disabled={disabled}
        />
        <TextField
          label="종횡비"
          hint="예: 16:9"
          value={form.asprtRt}
          onChange={onField('asprtRt')}
          disabled={disabled}
        />
        <TextField
          label="코덱"
          value={form.vdoCdc}
          onChange={onField('vdoCdc')}
          disabled={disabled}
        />
        <TextField
          label="파일형식"
          hint="비우면 확장자"
          value={form.fileFmt}
          onChange={onField('fileFmt')}
          disabled={disabled}
        />
        <TextField
          label="파일크기 (byte)"
          type="number"
          min={0}
          hint="비우면 실제 전송 크기"
          value={form.fileSz}
          onChange={onField('fileSz')}
          disabled={disabled}
        />
        <TextField
          label="BIT (색심도)"
          hint="예: 24bit — 비트레이트 아님"
          value={form.bit}
          onChange={onField('bit')}
          disabled={disabled}
        />
        <TextField
          label="PXL (화소)"
          hint="예: 4K"
          value={form.pxl}
          onChange={onField('pxl')}
          disabled={disabled}
        />
      </div>
    </fieldset>
  );
}
