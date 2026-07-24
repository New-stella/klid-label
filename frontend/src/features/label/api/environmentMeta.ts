// Phase 4 — 영상 단위 촬영환경(날씨·시간대·계절) 메타 API 클라이언트.
//
// BE 계약(Phase 2 확정):
//   GET /v1/videos/{rawSn}/environment-meta → ApiResponse<EnvironmentMetaResponse>
//   PUT /v1/videos/{rawSn}/environment-meta  body { weather, timeOfDay, season }
//     → 전체 교체(full replace). null 필드는 수동값 삭제 → 촬영일시 파생 프리필로 폴백.
// 응답은 apiClient interceptor 가 ApiResponse.data 만 언랩해 반환한다.
//
// 코드값(BE 화이트리스트와 정합): weather=맑음/흐림/비/눈/안개,
//   timeOfDay=DAY/NGT, season=SPRING/SUMMER/FALL/WINTER. 자유입력은 select 로 차단.
// 보안: rawSn 은 number 로 강제(path 조작 불가). 응답 본문은 로그 미출력.

import { apiClient } from '@/lib/api/client';

/** 항목별 출처 — 작업자 수동 저장값 vs 촬영일시 파생 프리필. */
export type MetaSource = 'MANUAL' | 'DERIVED' | null;

export interface EnvironmentMeta {
  rawSn: number;
  /** 날씨(맑음/흐림/비/눈/안개) — 미입력 시 null */
  weather: string | null;
  /** 시간대 코드(DAY/NGT) — 미상 시 null */
  timeOfDay: string | null;
  /** 계절 코드(SPRING/SUMMER/FALL/WINTER) — 미상 시 null */
  season: string | null;
  weatherSource: MetaSource;
  timeOfDaySource: MetaSource;
  seasonSource: MetaSource;
}

/** PUT 요청 바디 — 3필드 전체 교체(파생 유지는 null). */
export interface EnvironmentMetaUpdate {
  weather: string | null;
  timeOfDay: string | null;
  season: string | null;
}

/** 영상 촬영환경 조회. */
export function getEnvironmentMeta(rawSn: number): Promise<EnvironmentMeta> {
  return apiClient
    .get<EnvironmentMeta>(`/videos/${rawSn}/environment-meta`)
    .then((r) => normalize(r.data, rawSn));
}

/** 영상 촬영환경 저장(전체 교체). */
export function putEnvironmentMeta(
  rawSn: number,
  body: EnvironmentMetaUpdate,
): Promise<EnvironmentMeta> {
  return apiClient
    .put<EnvironmentMeta>(`/videos/${rawSn}/environment-meta`, body)
    .then((r) => normalize(r.data, rawSn));
}

function normalize(data: EnvironmentMeta | undefined, rawSn: number): EnvironmentMeta {
  return {
    rawSn: Number(data?.rawSn ?? rawSn),
    weather: data?.weather ?? null,
    timeOfDay: data?.timeOfDay ?? null,
    season: data?.season ?? null,
    weatherSource: data?.weatherSource ?? null,
    timeOfDaySource: data?.timeOfDaySource ?? null,
    seasonSource: data?.seasonSource ?? null,
  };
}
