import { http } from 'msw';
import { ok } from './_utils';
import { videos, DEIDENT_FAIL_SIMULATION } from '../data/videos';
import type { ExportRequest, ExportResponse } from '../../api/types';

let exportJobCounter = 1;

/**
 * 포털 서버로 학습데이터셋을 전송한다.
 * targetServer는 현재 PORTAL만 지원하며, deidentify=true면 비식별 처리(마스킹) 후 전송한다.
 *
 * 모델 의미론:
 * - 비식별은 매 전송 시도마다 실행되는 단발 행위다. 영상이 영구적으로 "비식별 실패" 상태를
 *   가지지는 않으며, 그 시도의 결과(사유)로만 기록된다.
 * - 비식별 실패로 그 시도에서 실패 처리된 영상은 다음 시도에서 다시 선택·전송 가능하다
 *   (forceReexport 같은 강제 옵션 없이).
 *
 * 처리 흐름:
 * 0) 검수 완료(승인) 가드 — taskStatus !== 'COMPLETED'인 영상은 모두 스킵
 *    (blockedNotApproved 카운트). 내보내기는 항상 검수 완료 영상만 가능하며 forceReexport와 무관.
 * 1) 이미 내보냄 가드 — forceReexport=false(기본)이면 exportStatus='EXPORTED'인 영상은 스킵
 *    (skippedAlreadyExported 카운트). 이 가드는 '재전송 방지' 용도로만 작동한다.
 *    FAILED 영상은 재시도 대상이므로 가드되지 않는다.
 * 2) 비식별 실패 시뮬레이션 — deidentify=true이면, 남은 영상 중 DEIDENT_FAIL_SIMULATION에
 *    속한 영상은 그 시도에서 비식별 실패 처리:
 *      - exportStatus='FAILED', lastExportFailureReason='비식별 실패' (in-memory 갱신)
 *      - failedDueDeident 카운트 증가
 * 3) 그 외 영상은 정상 전송 시뮬레이션 — exportStatus='EXPORTED', exportedAt=now(),
 *    lastExportFailureReason=undefined (성공 시 사유 클리어).
 *
 * deidentify=false면 시뮬레이션 무시, 모든 영상 정상 전송 처리.
 */
export const exportHandlers = [
  http.post('/api/v1/export', async ({ request }) => {
    const body = (await request.json()) as ExportRequest;
    const jobId = `export-${String(exportJobCounter++).padStart(4, '0')}`;
    const requested = body.videoIds;

    // 0) 검수 완료(승인) 가드 — 승인 영상이 아닌 것은 모두 차단 (forceReexport 무관)
    let candidates = requested;
    let blockedNotApproved = 0;
    {
      const filtered: string[] = [];
      for (const vid of candidates) {
        const v = videos.find((x) => x.id === vid);
        if (v && v.taskStatus === 'COMPLETED') {
          filtered.push(vid);
        } else {
          blockedNotApproved += 1;
        }
      }
      candidates = filtered;
    }

    // 1) 이미 내보냄 가드 — forceReexport=false(기본)일 때만 EXPORTED 스킵
    let skippedAlreadyExported = 0;
    if (!body.forceReexport) {
      const skipped: string[] = [];
      candidates = candidates.filter((vid) => {
        const v = videos.find((x) => x.id === vid);
        if (v && v.exportStatus === 'EXPORTED') {
          skipped.push(vid);
          return false;
        }
        return true;
      });
      skippedAlreadyExported = skipped.length;
    }

    // 2) 비식별 실패 시뮬레이션 + 정상 전송 처리
    const nowIso = new Date().toISOString();
    let failedDueDeident = 0;
    let succeeded = 0;
    for (const vid of candidates) {
      const v = videos.find((x) => x.id === vid);
      if (!v) continue;

      const willFailDeident = body.deidentify && DEIDENT_FAIL_SIMULATION.has(vid);
      if (willFailDeident) {
        v.exportStatus = 'FAILED';
        v.lastExportFailureReason = '비식별 실패';
        v.exportedAt = undefined;
        failedDueDeident += 1;
      } else {
        v.exportStatus = 'EXPORTED';
        v.exportedAt = nowIso;
        v.lastExportFailureReason = undefined;
        succeeded += 1;
      }
    }

    const count = succeeded;

    // 3) 메시지 조립
    const notApprovedSuffix =
      blockedNotApproved > 0 ? `, 검수 미완료 ${blockedNotApproved}건 차단` : '';
    let message: string;
    if (count === 0 && failedDueDeident > 0) {
      // 모두 비식별 실패
      message = `전송 실패 - 비식별 실패 ${failedDueDeident}건`;
      if (skippedAlreadyExported > 0) {
        message += `, 이미 전송 ${skippedAlreadyExported}건 스킵`;
      }
      message += notApprovedSuffix;
    } else if (count === 0) {
      // 후보 없음 (검수 미완료 차단 / 스킵만 있거나 요청 자체가 0건)
      if (blockedNotApproved > 0 && skippedAlreadyExported === 0) {
        message = `전송 가능한 영상이 없습니다 (검수 미완료 ${blockedNotApproved}건 차단)`;
      } else if (skippedAlreadyExported > 0) {
        message = `전송 가능한 영상이 없습니다 (이미 전송됨 ${skippedAlreadyExported}건${notApprovedSuffix})`;
      } else {
        message = '전송 가능한 영상이 없습니다';
      }
    } else if (failedDueDeident > 0) {
      message = `${count}건 포털 전송 시작, 비식별 실패 ${failedDueDeident}건 (다시 시도 가능)`;
      if (skippedAlreadyExported > 0) {
        message += `, 이미 전송 ${skippedAlreadyExported}건 스킵`;
      }
      message += notApprovedSuffix;
    } else if (skippedAlreadyExported > 0) {
      message = `${count}건 포털 전송 시작됨 (이미 전송 ${skippedAlreadyExported}건 스킵${notApprovedSuffix})`;
    } else if (blockedNotApproved > 0) {
      message = `${count}건 포털 전송 시작됨 (검수 미완료 ${blockedNotApproved}건 차단)`;
    } else {
      message = `${count}건 포털 전송 시작됨`;
    }

    const response: ExportResponse = {
      jobId,
      count,
      message,
      deidentify: body.deidentify,
      targetServer: 'PORTAL',
      failedDueDeident,
      skippedAlreadyExported,
      blockedNotApproved,
    };
    return ok(response);
  }),
];
