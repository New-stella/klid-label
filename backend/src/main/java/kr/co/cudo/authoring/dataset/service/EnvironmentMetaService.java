package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaResponse;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.util.ShootingEnvironmentVocabulary;
import kr.co.cudo.authoring.dataset.util.TimeOfDaySeasonDeriver;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Phase 2 — 영상 단위 촬영환경(날씨·시간대·계절) 메타 조회/저장 서비스.
 *
 * <p>조회는 <b>파생 프리필</b>이다 — 수동 저장값이 있으면 그 값, 없으면 촬영일시(SHT_DT)로부터
 * {@link TimeOfDaySeasonDeriver} 가 파생한 값을 반환한다(날씨는 자동 출처가 없어 미입력 시 null).
 * 저장은 <b>PUT 전체 교체</b>이며 3필드를 함께 덮어쓴다.
 *
 * <p>보안:
 * <ul>
 *   <li>인가(CWE-639 IDOR): 진입부에서 라벨 경로와 동일한
 *       {@link LabelAccessGuard#verifyRawAccess}(REVIEWER 전체 / WORKER 본인 배정 영상만) 재사용.
 *       미인증 401, 타인 자원 403. 포털 채널은 {@code SecurityConfig} 의 채널 격리로 차단된다.</li>
 *   <li>입력 검증(CWE-20/79): 3필드 모두 고정 화이트리스트
 *       ({@link ShootingEnvironmentVocabulary})만 허용 — 자유 텍스트/스크립트 문자열이 export JSON·
 *       스냅샷으로 새지 않는다. 길이 상한도 함께 검사한다.</li>
 *   <li>저장(CWE-362): 영속 엔티티의 3필드 전용 도메인 메서드
 *       ({@link LsDataRaw#changeShootingEnvironment}) + dirty checking 만 사용 —
 *       배치가 쓰는 다른 컬럼을 덮어쓰지 않는다.</li>
 *   <li>로그/에러(CWE-209/359): 식별자(rawSn)만 남기고 입력 원문·PII·스택트레이스는 노출하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class EnvironmentMetaService {

    private final VideoRepository videoRepository;
    private final LabelAccessGuard accessGuard;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    /** 검수 완료 영상의 촬영환경 수정 시 동결 스냅샷을 재동결하기 위한 재사용 어댑터. */
    private final DatasetVideoMetaSnapshotService snapshotService;
    /** 활성 동결 스냅샷 조회 + 재동결 직렬화용 rawSn advisory 락(materialize 와 동일 락). */
    private final LsDatasetVideoMetaRepository videoMetaRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 촬영환경 조회 — 수동 저장값 우선, 없으면 촬영일시 파생 프리필. */
    public EnvironmentMetaResponse get(Long rawSn, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        return toResponse(findRaw(rawSn));
    }

    /**
     * 촬영환경 저장 — <b>전체 교체</b>(3필드 함께). 생략된 필드는 수동값 삭제로 처리된다.
     *
     * <p>검수 완료(APPROVED) 후 수정이면 ①동결 스냅샷 재동결({@link #reFreezeApprovedSnapshot})
     * ②관제 outbound {@code TASK_MODIFIED}(META_UPDATED, {@code exportRegenerated=true}) 발행을 함께
     * 수행한다. <b>C-1b(Phase 5C) — export 폴더도 새 버전으로 전량 재생성한다</b>(구 정책 "재생성 미트리거"
     * 폐기): 재동결이 스냅샷만 갱신하고 파일은 옛 촬영환경으로 남으면 관제가 픽업한 산출물과 뷰가 불일치한다.
     * 재산출은 디바운스 flush 가 export → 통지 순서로 직렬화한다(사유는 {@link #reFreezeApprovedSnapshot}).
     *
     * <p><b>수동값 승격 주의(FE 계약)</b>: 본 API 는 전송된 값을 그대로 수동값(MANUAL)으로 저장한다.
     * 조회 응답의 파생 프리필({@code DERIVED})을 화면이 그대로 되돌려 보내면 파생값이 수동값으로 승격되어
     * 이후 촬영일시가 정정돼도 파생이 재계산되지 않는다. 따라서 <b>FE 는 사용자가 직접 고르지 않은
     * 파생 프리필 필드를 null 로 전송</b>해야 한다(BE 는 MANUAL/DERIVED 를 구분해 받지 않는다 — 현행 유지).
     *
     * <p><b>한계 — R5(self-fill 금지) 보증이 클라이언트 규율에 의존한다(L-3, Phase 10B)</b>: BE 는 전송값의
     * 출처를 알 수 없어 DERIVED 프리필의 MANUAL 승격을 <b>막지 못한다</b>. 현재 이 규율은 FE
     * ({@code EnvironmentMetaPanel.tsx} 의 {@code resolveField})만 지키고 있는데, 이 API 는 <b>공개 계약</b>이라
     * 다른 클라이언트(직접 호출·스크립트·향후 화면)는 프리필을 그대로 되돌려 보내 추정값을 수동값으로
     * 승격시킬 수 있다. 그렇게 승격된 값은 승인 동결·export·데이터마트 뷰로 <b>출처 구분자 없이</b> 전파되어
     * R5 가 깨진다. BE 강제(요청에 source 축을 두거나 프리필과 동일한 값을 거부)는 요청 계약 변경이라
     * 이번 범위 밖으로 두고 <b>알려진 한계로 명시</b>한다.
     *
     * <p><b>동시성(CWE-362)</b>: 상태 판정({@link #isReviewApproved}) 이전에 ①{@code flush} 로 촬영환경
     * UPDATE 를 내보내 대상 raw 행을 잠그고 ②{@code materialize} 와 동일한 rawSn advisory 락을 획득한다.
     * 이로써 "env 저장이 미승인으로 판정하는 사이 동시 승인(approve)의 materialize 가 아직 커밋되지 않은
     * 수동값을 못 보고 null 로 동결" 하는 양방향 창을 닫는다 — 승인이 먼저 커밋되면 상태 판정이 APPROVED 를
     * 보고 재동결하고, 승인이 뒤면 그 materialize 가 이 트랜잭션 커밋 후에야 advisory 락을 얻어 최신 수동값을
     * 읽는다. 잠금 순서는 기존 불변식 <b>raw 행락 → advisory</b> 단방향을 그대로 따른다(역순 없음 = 교착 없음).
     */
    @Transactional("controlTransactionManager")
    public EnvironmentMetaResponse update(Long rawSn, EnvironmentMetaUpdateRequest req, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        if (req == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "촬영환경 입력이 필요합니다.");
        }
        String weather = validate(req.weather(), ShootingEnvironmentVocabulary.WEATHERS, "weather");
        String timeOfDay = validate(req.timeOfDay(), ShootingEnvironmentVocabulary.TIME_OF_DAYS, "timeOfDay");
        String season = validate(req.season(), ShootingEnvironmentVocabulary.SEASONS, "season");

        LsDataRaw raw = findRaw(rawSn);
        // 영속 엔티티 dirty checking — 3필드만 UPDATE (전체 save/merge 금지).
        raw.changeShootingEnvironment(weather, timeOfDay, season);
        log.info("[EnvironmentMeta] updated rawSn={}", rawSn);

        // 상태 판정 전 직렬화(위 Javadoc 동시성 항목) — ① raw 행 락(UPDATE flush) → ② advisory 락 순서 고정.
        // 동결 소스는 LS_DATA_RAW 를 native 로 재조회하므로 flush 는 재동결 값 정합도 함께 보장한다.
        videoRepository.flush();
        videoMetaRepository.acquireRawLock(rawSn);

        if (isReviewApproved(rawSn)) {
            reFreezeApprovedSnapshot(rawSn);
            // C-1b(Phase 5C) — 재동결(위)이 동결 스냅샷을 갱신하므로 export 를 새 버전 폴더로 전량 재생성해
            //   JSON video 블록에 새 촬영환경(날씨/시간대/계절)을 실제로 반영한다. exportRegenerated=true 면
            //   디바운스 flush 가 export(force=true) 를 먼저 마친 뒤 통지를 내보낸다(순서 보장).
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, null, ChangeType.META_UPDATED, accessGuard.parseUserNo(actor.sub()), true));
        }
        return toResponse(raw);
    }

    /**
     * 검수 완료(APPROVED) 영상의 촬영환경 <b>승인 후 수정</b>을 동결 스냅샷에 반영한다(재동결).
     *
     * <p>재동결이 없으면 관제가 조회하는 동결 스냅샷({@code LS_DATASET_VIDEO_META} → 데이터마트 뷰
     * {@code export} 산출 JSON)과 포털 복제본이 승인 시점 값(예: null)에 고정되어,
     * {@code TASK_MODIFIED} 통지를 받은 관제가 조회해도 수정 전 값만 보게 된다.
     *
     * <p><b>검수 완료 일시(RVW_CMPL_DT) 보존</b>: 재동결은 새 active 스냅샷 행을 append 하므로
     * {@code materialize(rawSn)} 1-arg(=now()) 로 호출하면 "검수 완료 일시"가 <b>촬영환경 편집 시각</b>으로
     * 덮여 {@code V_COMPLETED_VIDEO.RVW_CMPTN_DT}·포털 복제 페이로드가 오염된다
     * (CLAUDE.md TASK_COMPLETED 페이로드 계약 위반). 따라서 기존 활성 스냅샷의 승인 시각을 읽어
     * {@link DatasetVideoMetaSnapshotService#materialize(Long, LocalDateTime)} 2-arg 로 그대로 넘긴다
     * (백필 경로와 동일한 소급 보존 방식).
     *
     * <p><b>export 폴더는 새 버전으로 재생성한다</b>(C-1b, Phase 5C — 구 "재생성 안 함" 정책 폐기): 재동결이
     * 동결 스냅샷을 갱신해도 export 폴더의 JSON {@code video} 블록이 옛 촬영환경으로 남으면, 관제가 픽업한
     * 학습데이터 파일과 뷰가 불일치해 사업 요구("데이터마트 학습데이터셋의 라벨링 정보 동기화")가 미충족된다.
     * 촬영환경 수정도 라벨 수정과 동일하게 {@code TaskModifiedEvent(exportRegenerated=true)} 로 발행하여
     * 디바운스 flush 가 export 를 전량 재생성한 뒤 통지하도록 한다. 저장소 증폭은 사용자 확정(2026-07-27)으로
     * 감수한다(검수 완료 영상의 재수정 빈도가 낮다는 판단 + 롤백 위해 전 버전 자기완결·보존).
     *
     * <p>호출 조건은 상위의 {@link #isReviewApproved} 가드 — 미검수 영상은 트리거하지 않는다(이후 최초
     * 승인의 materialize 가 수동값을 정상 캡처하므로 중복이 없다). APPROVED 인데 활성 스냅샷이 없는
     * 이례(백필 미완 등)는 fail-safe skip 한다(선례 동일). 로그는 rawSn 식별자만 남긴다(CWE-359/117).
     */
    private void reFreezeApprovedSnapshot(Long rawSn) {
        List<LsDatasetVideoMeta> active =
                videoMetaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        if (active.isEmpty()) {
            log.warn("[EnvironmentMeta] re-freeze skipped — no active snapshot rawSn={}", rawSn);
            return;
        }
        // 원래 검수 완료 일시를 그대로 승계(now() 로 덮지 않음).
        LocalDateTime reviewCompletedAt = active.get(0).getRvwCmplDt();
        snapshotService.materialize(rawSn, reviewCompletedAt);
        log.info("[EnvironmentMeta] re-freeze triggered rawSn={}", rawSn);
    }

    /**
     * 허용값 검증 (CWE-20/79). null/공백은 "수동값 없음"으로 정규화하고, 그 외에는 화이트리스트에
     * 있어야 한다(허용값은 모두 컬럼 길이 이내라 길이 초과 입력도 여기서 함께 거부된다).
     * 실패 시 400 — 클라이언트에는 필드명만 알리고 입력 원문은 응답·로그 어디에도 싣지 않는다(CWE-117/209).
     */
    private String validate(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!allowed.contains(normalized)) {
            log.warn("[EnvironmentMeta] rejected value field={}", field);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "촬영환경 " + field + " 값이 허용 목록에 없습니다.");
        }
        return normalized;
    }

    private LsDataRaw findRaw(Long rawSn) {
        return videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
    }

    /** 수동값 우선 + 파생 폴백 + 항목별 출처 표기. */
    private EnvironmentMetaResponse toResponse(LsDataRaw raw) {
        String timeOfDay = raw.getDayNgtCd();
        String season = raw.getSesnCd();
        boolean manualTimeOfDay = timeOfDay != null;
        boolean manualSeason = season != null;
        if (!manualTimeOfDay) {
            timeOfDay = TimeOfDaySeasonDeriver.dayNight(raw.getShtDt());
        }
        if (!manualSeason) {
            season = TimeOfDaySeasonDeriver.season(raw.getShtDt());
        }
        // 날씨는 자동 출처가 없어 수동값이 곧 전부(미입력이면 null).
        String weather = raw.getWthrNm();
        return new EnvironmentMetaResponse(
                raw.getRawSn(), weather, timeOfDay, season,
                weather == null ? null : EnvironmentMetaResponse.SOURCE_MANUAL,
                source(manualTimeOfDay, timeOfDay != null),
                source(manualSeason, season != null));
    }

    /** 값이 없으면 출처도 null, 있으면 MANUAL/DERIVED. */
    private static String source(boolean manual, boolean hasValue) {
        if (!hasValue) {
            return null;
        }
        return manual ? EnvironmentMetaResponse.SOURCE_MANUAL : EnvironmentMetaResponse.SOURCE_DERIVED;
    }

    /** 영상의 검수 상태가 APPROVED 인지 판정. 상태 row 없으면 미검수로 간주(false). */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }
}
