import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Input } from '@/components/common/Input';
import { RadioGroup } from '@/components/common/RadioGroup';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { PRVC_OPTIONS } from '@/features/dev/components/devUploadForm';
import {
  EVENT_TYPE_MANUAL_OPTION,
  SERVER_FILLED_GROUPS,
  UNSENT_GROUPS,
  UPLOAD_ROUTE_OPTIONS,
  UploadRoute,
} from '@/features/dev/components/unifiedUploadForm';
import type { PrvcType } from '@/features/dev/types';

/**
 * 업로드 폼의 **경로 선택 + 두 경로가 공유하는 상단 입력**. [@design SCREEN-027]
 *
 * <p>입력 폼은 두 경로가 완전히 같다 — 고른 값에 따라 보내는 곳과 그 뒤 흐름만 달라진다. 다만 각
 * 경로의 BE 계약이 다르므로 «이 경로에서는 전송되지 않는» 항목이 생기는데, 그 사실을
 * {@link UnsentNotice} 로 화면에 드러낸다. 입력을 받아 놓고 조용히 버리지 않는 것이 이 폼의 규칙이다.
 */

/** 적재 경로 라디오 — 올린 영상을 어디로 보낼지. */
export function UploadRouteField({
  value,
  onChange,
  disabled,
}: {
  value: UploadRoute;
  onChange: (next: UploadRoute) => void;
  disabled: boolean;
}) {
  const selected = UPLOAD_ROUTE_OPTIONS.find((o) => o.value === value);
  return (
    <Field>
      {/* `*` 를 붙이지 않는다 — 항상 한쪽이 골라져 있어 사용자가 «채워야 할 것» 이 아니다.
          필수 표시를 남발하면 정말 채워야 하는 식별 정보 세 항목이 묻힌다. */}
      <FieldLabel>적재 경로</FieldLabel>
      <RadioGroup
        name="uploadRoute"
        value={value}
        onChange={(v) => onChange(v as UploadRoute)}
        disabled={disabled}
        orientation="vertical"
        options={UPLOAD_ROUTE_OPTIONS.map((o) => ({
          value: o.value,
          label: (
            <span>
              {o.label}
              <span className="ml-1 text-sub text-gray-500">— {o.hint}</span>
            </span>
          ),
        }))}
      />
      <FieldDescription>
        입력 폼은 두 경로가 같습니다. 고른 값에 따라 보내는 곳과 그 뒤 흐름만 달라집니다.
        {selected && ` 현재 선택: ${selected.label}.`}
      </FieldDescription>
    </Field>
  );
}

/**
 * 선택한 경로에서 전송되지 않는 입력 안내.
 *
 * 조용한 손실(입력은 받았는데 실리지 않음) 차단용이다. 필드를 비활성화하지 않는 이유는 «두 경로가
 * 같은 폼» 이라는 계약을 지켜야 하기 때문이다 — 경로에 따라 입력칸이 잠기면 폼이 갈라진다.
 *
 * <p>문구는 두 갈래다({@link SERVER_FILLED_GROUPS}). 영상 기술메타는 **입력값은 버려지지만 서버가
 * 올린 파일에서 읽어 채우므로**, «전송되지 않습니다» 만 알리면 거짓이 된다. 반대로 위치·CCTV 제원
 * 처럼 파일에서 읽을 수 없는 묶음은 진짜로 버려지므로 기존 문구를 그대로 쓴다.
 *
 * <p>⚠ 서버가 채우는 동작은 서버 설정으로 끌 수 있고 **화면은 그 설정을 알 수 없다**. 그래서
 * «채워집니다» 로 단정하지 않고 «서버가 읽을 수 있는 항목을 채웁니다(설정에 따라 생략될 수
 * 있습니다)» 로 적는다 — 설정이 켜져 있든 꺼져 있든 참인 표현이어야 한다. 그 값을 FE 로 내려주는
 * 계약을 새로 만드는 것은 이 화면의 범위가 아니다. [@design SCREEN-027]
 */
export function UnsentNotice({ group, route }: { group: string; route: UploadRoute }) {
  if (!UNSENT_GROUPS[route].includes(group)) return null;
  const routeLabel = UPLOAD_ROUTE_OPTIONS.find((o) => o.value === route)?.label ?? '';
  return (
    <p data-testid={`unsent-notice-${group}`} className="text-sub text-warning-700">
      {SERVER_FILLED_GROUPS.includes(group) ? (
        <>
          ※ 지금 고른 «{routeLabel}» 경로에서는 이 입력값이 전송되지 않습니다. 대신 서버가 올린 영상
          파일에서 읽을 수 있는 항목을 직접 채웁니다(서버 설정에 따라 생략될 수 있습니다). 값은 폼에
          남아 있으며 경로를 바꾸면 입력값이 그대로 전송됩니다.
        </>
      ) : (
        <>
          ※ 지금 고른 «{routeLabel}» 경로에서는 이 항목이 전송되지 않습니다. 값은 폼에 남아 있으며
          경로를 바꾸면 전송됩니다.
        </>
      )}
    </p>
  );
}

/** 이벤트유형 옵션 — `EventTypeResponse` 중 이 화면이 쓰는 필드만. */
export interface UploadEventTypeOption {
  categoryKey: string;
  label: string;
  memberCodes: string[];
}

/**
 * 이벤트유형 — 마스터에 등록된 코드를 동적으로 조회해 고른다(+ 직접 입력).
 *
 * <p>표시명이 같은 유형코드들은 서버가 한 옵션으로 접어 내려주므로(표시명 그룹 축) 목록에 같은
 * 이름이 여러 번 뜨지 않고, 실제 전송되는 대표코드는 보조문구에 함께 보여 준다.
 *
 * <p><b>직접 입력을 함께 여는 이유</b> — 관제 이벤트 코드 체계는 우리 소유가 아니고 미등록 코드도
 * 실제로 들어오며, 적재가 처음 보는 코드를 마스터에 자동 등록한다. 조회 목록으로만 좁히면 관제가
 * 코드를 넓힐 때 **우리가 먼저 막는다**. 센티넬(`__manual__`)은 화면 모드 표식이라 전송되지 않는다.
 *
 * <p><b>필수 여부는 경로가 정한다</b> — 파이프라인 즉시 실행에서만 필수이고(그 경로의 BE 계약이
 * `@NotNull`) 관제 인입 재현에서는 선택이다. 그래서 이 입력이 `route` 를 받는다. 표기를 조건과 같은
 * 축으로 두지 않으면 별표 없는 항목 때문에 시작 버튼이 잠기고 사유가 화면에 없다
 * (`PrivacyTypeField` 가 `route` 를 받는 것과 같은 관례). [@design SCREEN-027]
 */
export function EventTypeField({
  categoryKey,
  manualCode,
  options,
  resolvedCode,
  onCategoryChange,
  onManualCodeChange,
  route,
  disabled,
}: {
  categoryKey: string;
  manualCode: string;
  options: ReadonlyArray<UploadEventTypeOption>;
  resolvedCode: string;
  onCategoryChange: (next: string) => void;
  onManualCodeChange: (next: string) => void;
  route: UploadRoute;
  disabled: boolean;
}) {
  const manual = categoryKey === EVENT_TYPE_MANUAL_OPTION;
  // 판정 축은 `canStartUpload` 의 경로 분기와 같다 — 둘이 갈리면 사유 없는 잠금이 된다.
  const required = route === UploadRoute.IMMEDIATE;
  return (
    <div className="flex flex-col gap-1">
      <Field>
        <FieldLabel>{required ? '이벤트유형 *' : '이벤트유형'}</FieldLabel>
        <Select value={categoryKey} onValueChange={onCategoryChange} disabled={disabled}>
          <SelectTrigger id="dev-upload-event">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {/* 로딩 중에는 비워 두지 않고 안내 옵션 1건을 보여준다(빈 select 는 고장처럼 보인다). */}
            {options.length === 0 ? (
              <SelectItem value="">이벤트 타입 로딩 중…</SelectItem>
            ) : (
              options.map((o) => (
                <SelectItem key={o.categoryKey} value={o.categoryKey}>
                  {o.label}
                </SelectItem>
              ))
            )}
            <SelectItem value={EVENT_TYPE_MANUAL_OPTION}>직접 입력</SelectItem>
          </SelectContent>
        </Select>
        <FieldDescription>
          {resolvedCode
            ? `전송 코드: ${resolvedCode}`
            : '마스터에 등록된 이벤트유형을 고르거나 코드를 직접 입력하세요'}
        </FieldDescription>
      </Field>
      {manual && (
        <Field>
          <FieldLabel>이벤트유형코드 직접 입력</FieldLabel>
          <Input
            value={manualCode}
            onChange={(e) => onManualCodeChange(e.target.value)}
            disabled={disabled}
            maxLength={20}
            autoComplete="off"
            placeholder="EV01000101"
          />
          <FieldDescription>
            예: EV01000101 — 관제 코드 표기 그대로 전송됩니다(소문자로 적어도 대문자로 보냅니다)
          </FieldDescription>
        </Field>
      )}
    </div>
  );
}

/**
 * 개인정보 유형 — 표시용 메타. 비식별 단계는 업로드된 모든 영상에 예외 없이 자동 실행된다
 * (출처유형 비식별 제외는 원본 복사로 완료 — ADR-066). [@design SCREEN-027]
 */
export function PrivacyTypeField({
  value,
  onChange,
  route,
  disabled,
}: {
  value: PrvcType;
  onChange: (next: PrvcType) => void;
  route: UploadRoute;
  disabled: boolean;
}) {
  return (
    <Field>
      <FieldLabel>개인정보 유형</FieldLabel>
      <RadioGroup
        name="prvcTypeCd"
        value={value}
        onChange={(v) => onChange(v as PrvcType)}
        disabled={disabled}
        options={PRVC_OPTIONS.map((o) => ({
          value: o.value,
          label: (
            <span>
              {o.label}
              <span className="ml-1 text-sub text-gray-400">— {o.hint}</span>
            </span>
          ),
        }))}
      />
      <FieldDescription>
        표시용 메타입니다 — 비식별 단계는 올린 모든 영상에 예외 없이 자동으로 실행되므로 이
        선택이 비식별 수행 여부를 바꾸지 않습니다. 다만 출처유형이 생성형 등 비식별 제외로 지정된
        영상은 그 단계가 원본 복사로 완료됩니다.
      </FieldDescription>
      <UnsentNotice group="개인정보 유형" route={route} />
    </Field>
  );
}

/** 영상 길이 — 입력칸을 두지 않는다(서버가 업로드된 파일에서 직접 읽는다). */
export function VideoLengthNotice() {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-body font-medium text-gray-700">영상 길이</span>
      <div
        data-testid="dev-upload-duration-notice"
        className="flex min-h-11 items-center rounded-lg border border-dashed border-gray-300 bg-gray-50 px-3 text-sub text-gray-600"
      >
        입력하지 않습니다 — 올린 영상 파일에서 서버가 직접 읽어 채웁니다 (1~7200초 범위 검증)
      </div>
    </div>
  );
}

/** 검증이벤트유형 select 의 "직접 입력" 센티넬과 짝을 이루는 이벤트유형 센티넬 재노출. */
export { EVENT_TYPE_MANUAL_OPTION };
