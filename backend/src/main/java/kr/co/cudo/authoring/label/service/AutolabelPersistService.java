package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.step.YoloLabelPersister;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 — YOLO 오토라벨 <b>저장 전담</b> 트랜잭션 경계 (F-1 / #6 TOCTOU 방어).
 *
 * <p>{@link AutolabelOnlineService} 오케스트레이션에서 <b>ai-server 블로킹 호출을 트랜잭션 밖으로 분리</b>하기 위해
 * DB 삭제+삽입만 담당하는 <b>별도 스프링 빈</b>으로 추출한다. self-invocation(같은 클래스 내 @Transactional
 * private 호출은 프록시를 타지 않음) 함정을 피하려고 오케스트레이션과 물리적으로 분리된 컴포넌트로 둔다.
 *
 * <p>보안 / 시나리오 방어:
 * <ul>
 *   <li>커넥션풀 고갈 방지(F-1): 짧은 트랜잭션에서 <b>삭제+삽입만</b> 원자 처리. 70s 블로킹 AI 호출은 이 경계 밖에서
 *       수행되어 control HikariCP 커넥션을 장시간 점유하지 않는다.</li>
 *   <li>TOCTOU(CWE-362, #6): 저장 트랜잭션 <b>진입 직후</b> {@link WorkLockService#isRawLocked} 재확인 —
 *       오케스트레이션의 잠금 체크~저장 사이에 비식별 신고가 라벨 퍼지+잠금했다면 저장을 취소(409)하여
 *       프라이버시 라벨 퍼지 불변식을 보호한다.</li>
 *   <li>idempotency: 저장 전 기존 <b>자동</b> 라벨(AUTO_LBL_YN='Y')만 선삭제 후 재삽입. 수동 라벨은
 *       {@link LsDataLblRepository#findAutoLblSnsBySrcSn} 결과에 포함되지 않아 절대 삭제되지 않는다.</li>
 *   <li>원자성: 삭제+삽입이 하나의 트랜잭션 — 부분 실패 시 전체 롤백.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutolabelPersistService {

    private final LsDataLblRepository lblRepository;
    private final LsDataLblAiInfoRepository aiInfoRepository;
    private final LabelMasterService labelMasterService;
    private final WorkLockService workLockService;
    private final ObjectMapper objectMapper;

    /**
     * 검증 완료된 detection 목록을 원자적으로 저장한다 (기존 자동 라벨 교체).
     *
     * <p><b>짧은 control 트랜잭션</b> — AI 호출은 이미 완료된 상태로 진입하므로 커넥션 점유 시간이 최소화된다.
     *
     * @param srcSn      프레임 PK
     * @param rawSn      영상 PK
     * @param detections 좌표 검증을 통과한 YOLO detection (mock 아님)
     * @param actor      트리거 주체 (감사 로그 추적성 — F-6)
     * @return 저장된 라벨 요약
     * @throws CustomException CONFLICT — 저장 직전 잠금 감지(TOCTOU)
     */
    @Transactional("controlTransactionManager")
    public List<AutolabelResponse.Item> persist(Long srcSn, Long rawSn,
                                                List<YoloResponse.Detection> detections, TokenClaims actor) {
        // (#6 TOCTOU) 저장 트랜잭션 진입 직후 잠금 재확인 — 잠금 체크~저장 사이 창에서
        //  비식별 신고가 라벨 퍼지+잠금했으면 저장을 취소하여 프라이버시 퍼지 불변식 보호.
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상입니다.");
        }

        String actorId = actor == null ? "unknown" : LogSanitizer.sanitize(actor.sub());

        // idempotency — 기존 자동 라벨만 선삭제(자식 AI_INFO → 부모 LBL). 수동 라벨 보존.
        List<Long> staleAuto = lblRepository.findAutoLblSnsBySrcSn(srcSn);
        if (!staleAuto.isEmpty()) {
            aiInfoRepository.deleteByDataLblSnIn(staleAuto);
            lblRepository.deleteAllByIdInBatch(staleAuto);
            log.info("[Autolabel] cleared stale auto labels srcSn={} count={} actor={}",
                    srcSn, staleAuto.size(), actorId);
        }

        // BBOX + AI_INFO 저장 (배치와 공용 헬퍼, 출처 마커 MANUAL_TRIGGER).
        List<AutolabelResponse.Item> items = new ArrayList<>(detections.size());
        for (YoloResponse.Detection d : detections) {
            Long labelId = labelMasterService.findLabelIdByName(d.label()).orElse(null);
            LsDataLbl saved = YoloLabelPersister.persistBbox(lblRepository, aiInfoRepository, objectMapper,
                    srcSn, rawSn, d.label(), labelId,
                    d.points(), d.score(), d.trackId(), YoloLabelPersister.SOURCE_ONLINE);
            items.add(new AutolabelResponse.Item(
                    saved.getLblSn(), labelId, d.label(), d.points(), clampScore(d.score()), d.trackId()));
        }
        log.info("[Autolabel] frame autolabeled srcSn={} rawSn={} saved={} actor={}",
                srcSn, rawSn, items.size(), actorId);
        return items;
    }

    /** ai 응답 score 를 [0.0, 1.0] 로 clamp. NaN 은 null. */
    private Double clampScore(double raw) {
        if (Double.isNaN(raw)) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, raw));
    }
}
