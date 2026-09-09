/**
 * 포털 라벨링 화면의 **업로드 자산 출처 어댑터**. @design SCREEN-029
 *
 * <h3>왜 이 훅이 있나</h3>
 * 포털의 라벨링 화면은 하나뿐이고(`/portal/label/:id`) 그 화면은 관제용 라벨링 도구를 그대로
 * 쓴다. 두 출처가 갈리는 것은 **화면이 아니라 창구**다 — 데이터마트는 프레임 축
 * (`/portal/frames/...`), 본인이 올린 자산은 자산 축(`/portal/uploads/...`)이다.
 * 이 훅이 그 차이를 흡수해 **데이터마트와 같은 모양의 응답**(`LabelsResponse`)을 만든다.
 *
 * ⚠ 업로드 자산은 이미 공용 원장에 앉아 있다(`ADR-058`) — **자산 식별자가 곧 영상 식별자이고
 *   프레임 식별자가 곧 공용 원장의 프레임 PK** 다. 그래서 여기서 하는 일은 데이터 모델 변환이
 *   아니라 **창구 이름 번역**뿐이다.
 *
 * <h3>두 요청을 하나로 합친다</h3>
 * <ul>
 *   <li>자산 상세(API-140) → 프레임 목록 + 준비 상태. 데이터마트의 `siblings` 자리를 채운다.</li>
 *   <li>업로드 프레임 라벨(API-155) → 현재 프레임의 라벨.</li>
 * </ul>
 * ★ **자산 상세는 화면이 아니라 이 훅이 부른다** — 화면이 따로 조회하면 프레임 목록의 진실원이
 *   둘이 되어 어긋난다.
 *
 * <h3>라벨이 도착하기 전에는 편집 창을 열지 않는다 (미저장 편집 보호)</h3>
 * 업로드 축의 저장은 **현재 프레임 전체교체 PUT** 이라, 「사용자가 그리는 사이에 조회 응답이
 * 도착해 작업본을 덮는」 경합이 곧 <b>작업 통째 유실</b>이다. 병합으로 때우지 않고 **창 자체를
 * 없애는 쪽**을 택했다. (그 대가로 프레임을 옮길 때 잠깐 로딩 화면이 보인다. 인지·수용한 맞바꿈.)
 *
 * ★**창을 실제로 닫는 것은 아래 `isLoading` 이다** — 그 값이 참인 동안 화면이 로딩 분기에서
 *   돌아서므로 캔버스·도구가 렌더되지 않는다. `data` 를 `undefined` 로 유지하는 것은 **2중 방어**
 *   이고 1차 방어가 아니다(그 줄만 지우는 변이는 로딩 분기가 앞서 살아남는다 — 실측 SURVIVE).
 * ⚠ 그러니 **`isLoading` 에서 `labelsQuery.isLoading` 을 빼지 말 것** — 그 순간 라벨이 오기 전에
 *   캔버스가 서서 경합 창이 열린다(그 변이는 RED 로 확인됨). 두 가드는 서로를 대체하지 않는다.
 *
 * ⚠ `placeholderData: keepPreviousData` 를 쓰지 말 것 — 그러면 새 프레임의 식별자에 **이전
 *   프레임의 라벨**이 짝지어져, 사용자가 그대로 저장하면 남의 프레임 라벨이 이 프레임에
 *   전체교체로 적재된다.
 */

import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';

import { PORTAL_KEYS } from '@/lib/queryKeys';
import { FrameImageType, type LabelsResponse, type SiblingFrame } from '@/features/label/types';
import { readPortalLabelFrameSn } from '@/features/portal/labelingEntry';

import { getUpload } from '../api';
import { PortalUploadStatus } from '../types';

import { useUploadFrameLabels } from './useUploadFrameLabels';

/**
 * 캔버스를 그리지 않고 안내만 표시하는 사유.
 *
 * ★ `no-frame` 은 <b>오류가 아니다</b> — 업로드 자산의 프레임은 마킹으로 뽑을 위치를 정한 뒤에
 *   생기므로 아직 없는 것이 정상인 구간이 있다. 실패로 그리면 사용자는 자기 자산이 깨진 줄 안다.
 * ★ `not-ready` 는 두 문구로 갈린다 — 처리 실패는 기다려도 되지 않고 준비 중은 기다리면 된다.
 *   그 둘은 본인 자산의 진행 상태라 갈라도 남의 것이 드러나지 않는다.
 * ⚠ `detail-error` 는 「없다」와 「남의 것이다」를 <b>가르지 않는다</b> — 가르면 그 구분 자체가
 *   남의 저작물이 있는지 알아내는 수단이 된다.
 */
export type UploadLabelNoticeKind = 'invalid' | 'detail-error' | 'not-ready' | 'no-frame';

export interface UploadLabelNotice {
  kind: UploadLabelNoticeKind;
  message: string;
}

export interface UploadLabelSourceResult {
  /** 데이터마트 갈래와 <b>같은 모양</b>의 응답. 준비 전이면 `undefined`. */
  data: LabelsResponse | undefined;
  isLoading: boolean;
  /** 라벨 조회 실패만 올린다 — 자산 상세 실패는 `notice` 가 받는다. */
  error: Error | null;
  /** 캔버스를 그리지 않고 안내만 표시해야 하는 사유. 없으면 `null`. */
  notice: UploadLabelNotice | null;
}

const NOT_READY_FAILED = '처리에 실패한 자산입니다. 라벨링을 진행할 수 없습니다.';
const NOT_READY_PENDING = '아직 준비 중인 자산입니다. 처리가 완료되면(READY) 라벨링할 수 있습니다.';

/**
 * @param uldSn 자산 PK. **`undefined` 면 이 축이 통째로 꺼진다**(데이터마트 갈래) — 요청도
 *   나가지 않고 `notice` 도 서지 않는다. 화면이 출처를 판정해 그 결과를 여기로 내린다.
 */
export function useUploadLabelSource(uldSn: number | undefined): UploadLabelSourceResult {
  const [searchParams] = useSearchParams();
  const requestedFrameSn = readPortalLabelFrameSn(searchParams);

  const enabled = uldSn !== undefined;
  const validUldSn = enabled && Number.isFinite(uldSn) && (uldSn as number) > 0;

  const detailQuery = useQuery({
    queryKey: PORTAL_KEYS.uploadDetail(uldSn ?? -1),
    queryFn: () => getUpload(uldSn as number),
    enabled: validUldSn,
  });

  const detail = detailQuery.data;
  const frames = useMemo(() => detail?.frames ?? [], [detail]);
  const isReady = detail?.uldSttsCd === PortalUploadStatus.READY;

  /*
   * 현재 프레임은 **주소가 나른다**(`?frame=`) — 화면 안의 숫자가 아니다. 화면 안에 인덱스 상태를
   * 따로 두면 주소와 상태 둘이 진실원이 되어 어긋난다. 주소의 값이 지금 목록에 없으면(프레임이
   * 아직 안 왔거나 값이 잘못됐다) **첫 프레임**으로 읽는다 — 예외를 던지거나 빈 화면을 내지 않는다.
   */
  const indexFromUrl = frames.findIndex((f) => f.uldFrmeSn === requestedFrameSn);
  const currentFrame = frames[indexFromUrl >= 0 ? indexFromUrl : 0];
  const uldFrmeSn = isReady ? currentFrame?.uldFrmeSn : undefined;

  const labelsQuery = useUploadFrameLabels(uldFrmeSn, currentFrame?.frmeNo ?? 0);

  const siblings: SiblingFrame[] = useMemo(
    () => frames.map((f) => ({ srcSn: f.uldFrmeSn, frameNo: f.frmeNo })),
    [frames],
  );

  const data = useMemo<LabelsResponse | undefined>(() => {
    if (!enabled || !isReady || currentFrame === undefined) return undefined;
    const items = labelsQuery.data;
    // 라벨이 도착하기 전에는 만들지 않는다 — **2중 방어**다(1차는 아래 `isLoading`).
    // ⚠ 「중복이니 지운다」로 판단하지 말 것: 이 줄이 없으면 로딩 판정이 조금이라도 느슨해질 때
    //   곧바로 빈 라벨 캔버스가 서고, 그 상태에서 그린 라벨이 전체교체 PUT 으로 유실된다.
    if (items === undefined) return undefined;
    return {
      frameNo: currentFrame.frmeNo,
      srcSn: currentFrame.uldFrmeSn,
      // ADR-058 — 자산 식별자가 곧 영상 식별자다. 메타·이벤트 어노테이션이 이 값을 영상 축으로 쓴다.
      videoId: uldSn,
      // 업로드 자산은 본인 데이터라 비식별 라이프사이클이 없다. 원본/비식별 토글도 잠금도 없다.
      frameImageType: FrameImageType.DEID,
      lockSttsCd: null,
      siblings,
      labels: items,
    };
  }, [enabled, isReady, currentFrame, labelsQuery.data, siblings, uldSn]);

  const notice = useMemo<UploadLabelNotice | null>(() => {
    if (!enabled) return null;
    if (!validUldSn) return { kind: 'invalid', message: '잘못된 자산 주소입니다.' };
    if (detailQuery.isError || (detailQuery.isSuccess && detail === undefined)) {
      return {
        kind: 'detail-error',
        message: '자산을 불러올 수 없습니다. 본인 자산인지 확인해 주세요.',
      };
    }
    if (detail === undefined) return null;
    if (!isReady) {
      return {
        kind: 'not-ready',
        message:
          detail.uldSttsCd === PortalUploadStatus.FAILED ? NOT_READY_FAILED : NOT_READY_PENDING,
      };
    }
    if (frames.length === 0) {
      return { kind: 'no-frame', message: '표시할 프레임이 없습니다.' };
    }
    return null;
  }, [enabled, validUldSn, detailQuery.isError, detailQuery.isSuccess, detail, isReady, frames]);

  return {
    data,
    /*
     * ★**미저장 편집 보호의 1차 방어선이다.** `labelsQuery.isLoading` 이 여기 들어 있어야 라벨이
     *   도착하기 전에 화면이 로딩 분기에 머물고, 그래야 그리는 사이에 조회 응답이 도착해 작업본을
     *   덮는 경합 창이 아예 열리지 않는다(전체교체 PUT 이라 그 유실이 곧 작업 통째 유실이다).
     *   회귀 가드: `PortalUploadLabelingBranch.test.tsx(★라벨이_도착하기_전에는_캔버스를_그리지_않는다)`.
     * ⚠ 안내가 서 있으면 로딩이 아니다 — 둘이 동시에 참이면 화면이 스피너에 갇힌다.
     */
    isLoading: enabled && notice === null && (detailQuery.isLoading || labelsQuery.isLoading),
    error: enabled ? ((labelsQuery.error as Error | null) ?? null) : null,
    notice,
  };
}
