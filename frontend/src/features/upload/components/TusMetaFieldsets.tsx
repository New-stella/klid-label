import type { ChangeEvent } from 'react';

import { Input } from '@/components/common/Input';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { SRC_TYPES, type TusFormState } from '@/features/upload/components/tusUploadForm';

/**
 * TUS 업로드 폼의 **입력 fieldset 4종** — 관제 인입 29컬럼 재현.
 *
 * `TusUploadPanel` 이 518줄이 되어 `component.md` 의 "400줄 초과 시 분리 필수" 를 위반했으므로
 * 입력부를 여기로 옮겼다. 상태는 패널이 소유하고 여기서는 `value`/`onChange` 만 받는다(제어
 * 컴포넌트) — fieldset 이 자체 상태를 들면 제출 시점의 값이 갈라진다.
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

/** 식별 정보 — 필수 4종 + 촬영일시. */
export function IdentityFieldset({ form, onField, onValue, disabled }: FieldsetProps) {
  return (
    <fieldset className={FIELDSET_CLASS}>
      <legend className={LEGEND_CLASS}>식별 정보</legend>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Input
          label="영상 클립 ID *"
          hint="저장 파일명이 됩니다 (영문·숫자·_·- 64자)"
          value={form.vmsClipId}
          onChange={onField('vmsClipId')}
          disabled={disabled}
          autoComplete="off"
        />
        <Input
          label="CCTV ID *"
          value={form.cctvId}
          onChange={onField('cctvId')}
          disabled={disabled}
          autoComplete="off"
        />
        <div className="flex flex-col gap-1">
          <label htmlFor="tus-src-type" className="text-body font-medium text-gray-700">
            출처유형 *
          </label>
          <select
            id="tus-src-type"
            value={form.srcType}
            onChange={(e) => onValue('srcType', e.target.value)}
            disabled={disabled}
            className={`h-11 rounded-lg border border-gray-300 bg-white px-3 text-body text-gray-900 ${KRDS_FOCUS}`}
          >
            {SRC_TYPES.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        <Input
          label="지자체코드 *"
          hint="숫자 1~10자리"
          value={form.lclgvCd}
          onChange={onField('lclgvCd')}
          disabled={disabled}
          autoComplete="off"
        />
        <Input
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
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Input label="지역명" value={form.rgnNm} onChange={onField('rgnNm')} disabled={disabled} />
        <Input label="기관코드" value={form.ogCd} onChange={onField('ogCd')} disabled={disabled} />
        <Input label="CCTV명" value={form.cctvNm} onChange={onField('cctvNm')} disabled={disabled} />
        <Input
          label="카메라 높이 (m)"
          type="number"
          step="0.1"
          value={form.cctvHgt}
          onChange={onField('cctvHgt')}
          disabled={disabled}
        />
        <Input
          label="위도 (WGS84)"
          type="number"
          step="0.0000001"
          value={form.wgs84Lat}
          onChange={onField('wgs84Lat')}
          disabled={disabled}
        />
        <Input
          label="경도 (WGS84)"
          type="number"
          step="0.0000001"
          value={form.wgs84Lot}
          onChange={onField('wgs84Lot')}
          disabled={disabled}
        />
        <Input
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
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Input
          label="이벤트 ID"
          hint="예: ABA_0001 (이벤트 유형코드가 아닙니다)"
          value={form.evntId}
          onChange={onField('evntId')}
          disabled={disabled}
        />
        <Input label="이벤트명" value={form.evntNm} onChange={onField('evntNm')} disabled={disabled} />
      </div>
      <div className="flex flex-col gap-1">
        <label htmlFor="tus-mntr-cn" className="text-body font-medium text-gray-700">
          관제일지
        </label>
        <textarea
          id="tus-mntr-cn"
          rows={3}
          maxLength={4000}
          value={form.mntrCn}
          onChange={(e) => onValue('mntrCn', e.target.value)}
          disabled={disabled}
          className={`rounded-lg border border-gray-300 bg-white px-3 py-2 text-body text-gray-900 ${KRDS_FOCUS}`}
        />
      </div>
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
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <Input
          label="영상길이 (초)"
          type="number"
          min={0}
          value={form.vdoLenSec}
          onChange={onField('vdoLenSec')}
          disabled={disabled}
        />
        <Input label="FPS" value={form.fps} onChange={onField('fps')} disabled={disabled} />
        <Input
          label="프레임수"
          type="number"
          min={0}
          value={form.frmCnt}
          onChange={onField('frmCnt')}
          disabled={disabled}
        />
        <Input
          label="가로 (px)"
          type="number"
          min={0}
          value={form.wdth}
          onChange={onField('wdth')}
          disabled={disabled}
        />
        <Input
          label="세로 (px)"
          type="number"
          min={0}
          value={form.vrtc}
          onChange={onField('vrtc')}
          disabled={disabled}
        />
        <Input
          label="해상도"
          hint="예: 1920x1080"
          value={form.resl}
          onChange={onField('resl')}
          disabled={disabled}
        />
        <Input
          label="종횡비"
          hint="예: 16:9"
          value={form.asprtRt}
          onChange={onField('asprtRt')}
          disabled={disabled}
        />
        <Input label="코덱" value={form.vdoCdc} onChange={onField('vdoCdc')} disabled={disabled} />
        <Input
          label="파일형식"
          hint="비우면 확장자"
          value={form.fileFmt}
          onChange={onField('fileFmt')}
          disabled={disabled}
        />
        <Input
          label="파일크기 (byte)"
          type="number"
          min={0}
          hint="비우면 실제 전송 크기"
          value={form.fileSz}
          onChange={onField('fileSz')}
          disabled={disabled}
        />
        <Input
          label="BIT (색심도)"
          hint="예: 24bit — 비트레이트 아님"
          value={form.bit}
          onChange={onField('bit')}
          disabled={disabled}
        />
        <Input
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
