// Phase 4 — 영상 단위 촬영환경(날씨·시간대·계절) 입력 패널.
//
// 라벨링 RightPanel '메타' 탭(내부 채널만)에 삽입되는 접이식 편집 패널.
// - useEnvironmentMeta(rawSn) 로 BE 프리필값(수동값 우선, 없으면 촬영일시 파생) 로드 → 폼 바인딩
// - 작업자가 select/토글로 수정 후 저장 → useUpdateEnvironmentMeta(rawSn) (전체 교체 PUT)
// - dirty 체크: 원본과 다를 때만 저장 활성. 영상(rawSn) 전환 시 로컬상태 동기화.
//
// 값 코드(BE 화이트리스트 정합): weather=맑음/흐림/비/눈/안개, timeOfDay=DAY/NGT,
//   season=SPRING/SUMMER/FALL/WINTER. 코드↔한글 표시는 FE 매핑, 자유입력은 select/토글로 차단.
// 보안(저장형 XSS 방어): 모든 값은 select/radio value 로만 바인딩(고정 코드) — React 기본 escape.
//   dangerouslySetInnerHTML 미사용.
// a11y: 각 컨트롤에 <label htmlFor> ↔ id. 색상만으로 정보 전달 안 함(텍스트 병행).

import { useEffect, useMemo, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Select, type SelectOption } from '@/components/common/Select';

import type { MetaSource } from '../api/environmentMeta';
import {
  useEnvironmentMeta,
  useUpdateEnvironmentMeta,
} from '../hooks/useEnvironmentMeta';

import { MetaSection } from './MetaSection';

export interface EnvironmentMetaPanelProps {
  rawSn: number | undefined;
}

/** 날씨 옵션 — 코드와 표시가 동일한 한글 5종. */
const WEATHER_OPTIONS = ['맑음', '흐림', '비', '눈', '안개'] as const;

/** 시간대 옵션 — 코드(BE)↔한글 표시. */
const TIME_OF_DAY_OPTIONS = [
  { code: 'DAY', label: '주간' },
  { code: 'NGT', label: '야간' },
] as const;

/** 계절 옵션 — 코드(BE)↔한글 표시. */
const SEASON_OPTIONS = [
  { code: 'SPRING', label: '봄' },
  { code: 'SUMMER', label: '여름' },
  { code: 'FALL', label: '가을' },
  { code: 'WINTER', label: '겨울' },
] as const;

const WEATHER_ID = 'env-weather-select';
const SEASON_ID = 'env-season-select';

/** 미선택(빈 값) 옵션 — 공통 Select 의 placeholder(disabled·hidden)와 달리 <b>선택 가능</b>해야 한다. */
const UNSELECTED_OPTION: SelectOption = { value: '', label: '선택 안 함' };

const FIELD_LABEL_CLASS = 'block text-caption text-gray-500 mb-1';

/** BE 값(null 포함)을 select value(빈 문자열=미선택)로 정규화. */
function toValue(v: string | null): string {
  return v ?? '';
}

/** select value(빈 문자열=미선택)를 BE 전송값(null=수동값 삭제)으로 정규화. */
function toPayload(v: string): string | null {
  return v === '' ? null : v;
}

/**
 * 저장 시 필드별 전송값 결정 — 파생값의 조용한 MANUAL 승격 방지(BE 전체 교체 계약).
 * 사용자가 값을 바꿨거나(touched=현재값≠원본값) 원본이 이미 수동값(MANUAL)이면 값을 전송하고,
 * 손대지 않은 파생값(DERIVED, 또는 source 미상+미입력)은 null 로 보내 BE 가 촬영일시
 * 파생 프리필을 유지하게 한다. 이렇게 해야 "파생값 그대로 재전송 → MANUAL 승격"이 방지된다.
 */
function resolveField(
  current: string,
  original: string,
  source: MetaSource,
): string | null {
  if (current !== original || source === 'MANUAL') return toPayload(current);
  return null;
}

export function EnvironmentMetaPanel({ rawSn }: EnvironmentMetaPanelProps) {
  const { data, isLoading } = useEnvironmentMeta(rawSn);
  const update = useUpdateEnvironmentMeta(rawSn);

  const [weather, setWeather] = useState('');
  const [timeOfDay, setTimeOfDay] = useState('');
  const [season, setSeason] = useState('');

  // 옵션 순서·값은 상수 정의 순서를 그대로 따른다(표시 순서 변경 금지).
  const weatherOptions = useMemo<SelectOption[]>(
    () => [UNSELECTED_OPTION, ...WEATHER_OPTIONS.map((w) => ({ value: w, label: w }))],
    [],
  );
  const seasonOptions = useMemo<SelectOption[]>(
    () => [UNSELECTED_OPTION, ...SEASON_OPTIONS.map((s) => ({ value: s.code, label: s.label }))],
    [],
  );

  // 영상 전환(data 변경) 시 로컬 폼 상태 동기화.
  useEffect(() => {
    setWeather(toValue(data?.weather ?? null));
    setTimeOfDay(toValue(data?.timeOfDay ?? null));
    setSeason(toValue(data?.season ?? null));
  }, [data?.weather, data?.timeOfDay, data?.season, rawSn]);

  const original = {
    weather: toValue(data?.weather ?? null),
    timeOfDay: toValue(data?.timeOfDay ?? null),
    season: toValue(data?.season ?? null),
  };
  const dirty =
    weather !== original.weather ||
    timeOfDay !== original.timeOfDay ||
    season !== original.season;
  const disabled = rawSn === undefined || isLoading || update.isPending;
  const canSave = rawSn !== undefined && dirty && !update.isPending;

  const handleSave = () => {
    if (!canSave) return;
    update.mutate({
      weather: resolveField(weather, original.weather, data?.weatherSource ?? null),
      timeOfDay: resolveField(
        timeOfDay,
        original.timeOfDay,
        data?.timeOfDaySource ?? null,
      ),
      season: resolveField(season, original.season, data?.seasonSource ?? null),
    });
  };

  return (
    <MetaSection title="촬영환경">
      <div>
        <label htmlFor={WEATHER_ID} className={FIELD_LABEL_CLASS}>
          날씨
        </label>
        <Select
          id={WEATHER_ID}
          value={weather}
          onChange={(e) => setWeather(e.target.value)}
          disabled={disabled}
          options={weatherOptions}
          className="text-body-md"
        />
      </div>

      <div>
        <span className={FIELD_LABEL_CLASS} id="env-timeofday-label">
          시간대
        </span>
        <div
          className="flex gap-2"
          role="radiogroup"
          aria-labelledby="env-timeofday-label"
        >
          {TIME_OF_DAY_OPTIONS.map((opt) => {
            const active = timeOfDay === opt.code;
            const btnId = `env-timeofday-${opt.code}`;
            return (
              <button
                key={opt.code}
                id={btnId}
                type="button"
                role="radio"
                aria-checked={active}
                disabled={disabled}
                onClick={() => setTimeOfDay(active ? '' : opt.code)}
                className={`flex-1 rounded border text-body-md py-1.5 transition-colors disabled:opacity-60 ${
                  active
                    ? 'border-primary-500 bg-primary-600 text-white'
                    : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
                }`}
              >
                {opt.label}
              </button>
            );
          })}
        </div>
      </div>

      <div>
        <label htmlFor={SEASON_ID} className={FIELD_LABEL_CLASS}>
          계절
        </label>
        <Select
          id={SEASON_ID}
          value={season}
          onChange={(e) => setSeason(e.target.value)}
          disabled={disabled}
          options={seasonOptions}
          className="text-body-md"
        />
      </div>

      {update.isError && (
        <p className="text-caption text-danger" role="alert">
          촬영환경 저장에 실패했습니다. 다시 시도해 주세요.
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
