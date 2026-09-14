// 영상 기술 정보 (읽기 전용) — 라벨링·검수 두 화면의 메타 탭이 <b>같은 부품</b>을 쓴다.
// [@design SCREEN-005] [@design SCREEN-019] [@design API-066]
//
// ★왜 부품 하나인가 — 사양이 두 화면의 문구를 글자 단위로 같게 써 두었다. 화면마다 따로 그리면
//   한쪽만 다듬어져 같은 값이 서로 다른 이름·단위로 보인다(이 저장소의 반복 결함).
//
// ★고친 것 — 이 자리는 서버가 준 K/V 를 <b>날것 그대로</b> 늘어놓고 있었다.
//   `video.fps 30.003982863999408` · `video.duration_ms 144581` · `video.filesize 11243026` 처럼
//   ①내부 저장 키가 라벨이고 ②밀리초·바이트가 그대로 보였다. 사양은 <b>네 항목만</b>
//   사람이 읽는 값으로 보이라고 정한다 — 해상도 · 코덱 · 프레임률 · 길이.
//
// ★보이지 않는 키(파일 크기·비트레이트 등)는 <b>버린 것이 아니라 이 자리의 대상이 아니다</b>.
//   서버 응답에는 그대로 있으며, 늘리려면 사양(SCREEN-005·SCREEN-019)부터 고친다.
//
// 보안: 값은 텍스트 노드로만 출력해 자동 escape 된다(CWE-79).

import { useMeta } from '@/features/auto/hooks/useMeta';
import type { MetaItem } from '@/features/auto/types';

import { MetaSection } from './MetaSection';

/** 이 패널이 보이는 네 항목의 저장 키 — BE {@code VideoMetaService} 의 상수 미러. */
export const TECHNICAL_META_KEYS = {
  resolution: 'video.resolution',
  codec: 'video.codec',
  fps: 'video.fps',
  durationMs: 'video.duration_ms',
} as const;

export const VIDEO_TECHNICAL_META_TITLE = '영상 기술 정보 (읽기 전용)';

/**
 * 해상도 — 저장값은 {@code "WIDTHxHEIGHT"} 이고 화면 표기는 {@code 640×480} 이다.
 *
 * ★형태가 맞지 않으면 <b>받은 값을 그대로</b> 돌려준다 — 못 읽는다고 감추면 값이 있는데
 * 없는 것처럼 보인다(이 저장소의 「조용한 손실 금지」 규칙).
 */
export function formatResolution(raw: string): string {
  const m = /^\s*(\d+)\s*[x×X]\s*(\d+)\s*$/.exec(raw);
  return m === null ? raw : `${m[1]}×${m[2]}`;
}

/** 프레임률 — 소수 둘째 자리까지 + 단위. 숫자로 못 읽으면 받은 값 그대로. */
export function formatFps(raw: string): string {
  const n = Number(raw);
  return Number.isFinite(n) ? `${n.toFixed(2)} fps` : raw;
}

/**
 * 길이 — 저장은 밀리초이고 화면은 분·초다({@code 144581} → {@code 2분 24.6초}).
 *
 * ★1분 미만이면 「0분」을 앞에 두지 않는다 — 사람이 그렇게 읽지 않는다. 사양의 보기가
 * 1분 이상 한 건뿐이라 이 갈래는 우리가 정했고, 그래서 여기 적어 둔다.
 */
export function formatDurationMs(raw: string): string {
  const ms = Number(raw);
  if (!Number.isFinite(ms) || ms < 0) return raw;
  const totalSec = ms / 1000;
  const minutes = Math.floor(totalSec / 60);
  const seconds = totalSec - minutes * 60;
  const secText = `${seconds.toFixed(1)}초`;
  return minutes > 0 ? `${minutes}분 ${secText}` : secText;
}

interface Row {
  key: string;
  label: string;
  value: string;
}

/**
 * 표시할 행 목록 — <b>순서는 사양 고정</b>(해상도 · 코덱 · 프레임률 · 길이)이고,
 * 값이 없는 항목은 행 자체를 두지 않는다.
 *
 * ⚠ 코덱은 <b>받은 값 그대로</b> 보인다. 사양이 변환 규칙을 정한 것은 프레임률·길이 둘뿐이고,
 * 코덱 이름 표를 여기 두면 라벨 마스터·이벤트 유형에서 이미 겪은 「두 번째 진실원」이 된다
 * (표에 없는 코덱이 조용히 빈칸이 되는 실패 모드까지 같다).
 */
export function technicalRows(items: readonly MetaItem[]): Row[] {
  const byKey = new Map<string, string>();
  for (const it of items) {
    const v = (it.metaVal ?? '').trim();
    if (v !== '') byKey.set(it.metaKey, v);
  }
  const spec: Array<[string, string, (raw: string) => string]> = [
    [TECHNICAL_META_KEYS.resolution, '해상도', formatResolution],
    [TECHNICAL_META_KEYS.codec, '코덱', (raw) => raw],
    [TECHNICAL_META_KEYS.fps, '프레임률', formatFps],
    [TECHNICAL_META_KEYS.durationMs, '길이', formatDurationMs],
  ];
  const rows: Row[] = [];
  for (const [key, label, format] of spec) {
    const raw = byKey.get(key);
    if (raw !== undefined) rows.push({ key, label, value: format(raw) });
  }
  return rows;
}

export interface VideoTechnicalMetaPanelProps {
  /** 현재 프레임 SRC_SN — 메타 조회 키. 다른 메타 패널과 같은 쿼리를 공유해 추가 요청이 없다. */
  srcSn: number | undefined;
}

export function VideoTechnicalMetaPanel({ srcSn }: VideoTechnicalMetaPanelProps) {
  const { data } = useMeta(srcSn);
  const rows = technicalRows(data?.technicalMeta ?? []);
  // 네 항목이 하나도 없으면 구역째 두지 않는다 — 빈 상자만 남으면 무엇이 빠졌는지도 알 수 없다.
  if (rows.length === 0) return null;

  return (
    <MetaSection title={VIDEO_TECHNICAL_META_TITLE}>
      <dl className="space-y-1" aria-label={VIDEO_TECHNICAL_META_TITLE} data-testid="video-technical-meta">
        {rows.map((row) => (
          <div key={row.key} className="grid grid-cols-[88px_1fr] gap-x-2 text-body-md">
            <dt className="text-gray-500">{row.label}</dt>
            <dd className="break-words text-gray-900" data-testid={`video-technical-${row.key}`}>
              {row.value}
            </dd>
          </div>
        ))}
      </dl>
    </MetaSection>
  );
}
