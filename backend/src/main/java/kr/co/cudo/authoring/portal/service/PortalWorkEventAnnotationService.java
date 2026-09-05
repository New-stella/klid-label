package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.portal.dto.PortalEventAnnotationResponse;
import kr.co.cudo.authoring.portal.dto.PortalEventAnnotationUpdateRequest;
import kr.co.cudo.authoring.portal.entity.LsPortalUserEvntAnno;
import kr.co.cudo.authoring.portal.repository.LsPortalUserEvntAnnoRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 포털 작업 화면의 <b>이벤트 어노테이션 Load·저장</b>.
 *
 * <h3>★★ 저장처는 자산 출처가 가른다</h3>
 * <ul>
 *   <li><b>데이터마트 자산</b> — 원본({@code LS_EVNT_ANNO})과 본인 오버레이
 *       ({@code LS_PORTAL_USER_EVNT_ANNO})를 병합해 내려주고 저장은 <b>오버레이에만</b> 한다.
 *       ★원본과 승인 시점 동결본을 수정하지 않는다 — 단방향이라 데이터마트로 되돌아가지 않고
 *       관제 통지·산출물 재생성을 일으키지 않는다.</li>
 *   <li><b>본인 업로드 자산</b> — 오버레이를 거치지 않고 그 자산의 어노테이션 원장에 그대로 읽고
 *       쓴다. 가려야 할 남의 원본이 없어 병합할 것이 없고 가렸다는 표시도 서지 않는다.</li>
 * </ul>
 *
 * <h3>★ 본문은 내부 원장과 같은 구조체를 그대로 담는다</h3>
 * <p>키별로 펴지 않는다 — 산출 문서와 모양이 갈리면 내보낼 때마다 재조립이 필요하고 사고 단계 같은
 * 중첩이 무너진다. 구조를 강제하지도 않는다(내부 원장이 포맷 미확정이라 원문을 그대로 보관한다).
 *
 * <h3>★★★ 원본을 수정하지 않기 위해 내부 저장 창구를 재사용하지 않는다</h3>
 * <p>같은 값을 고치는 내부 창구({@code EvntAnnoService.upsertOnce})는 저장하면서 <b>원장을 직접
 * 고치고, 검토행을 만들거나 되돌리고, 재검토 표시를 세우고, 관제 통지를 발행하고, 승인 동결본에
 * 영향을 준다</b>. 그것을 그대로 부르면 이 창구의 최상위 불변 둘을 <b>한 번에</b> 위반한다.
 * 이 위반은 「중복 구현을 피하자」는 가장 자연스러운 판단에서 나오므로 금지로 못박는다.
 * <table>
 *   <caption>재사용 경계</caption>
 *   <tr><th>재사용한다</th><td>읽기 경로 · 본문 구조 유효성 규칙</td></tr>
 *   <tr><th>재사용하지 않는다</th><td>저장 동작(원장 쓰기 · 검토행 · 재검토 표시 · 관제 통지 · 동결본)</td></tr>
 * </table>
 * <p>포털에는 검수가 없다. 검토행을 만들면 검수 큐와 데이터마트 뷰에 포털 값이 흘러들고,
 * 이벤트를 발행하면 관제 통지·산출물 재생성이 걸린다. 이 서비스는 <b>어떤 이벤트도 발행하지
 * 않는다</b> — 이벤트 발행자를 여기에 주입하지 말 것.
 *
 * <h3>비식별 누락 신고 게이트 (412) — <b>저장에만</b> 건다</h3>
 * <p>같은 채널의 형제 창구(포털 프레임 라벨 저장·프레임 이미지 서빙·프레임 메타 저장)가 이미 그
 * 구간을 막는다. 이 창구만 열려 있으면 <b>저장만 막고 어노테이션으로 우회</b>가 성립한다. 판정은
 * 복제하지 않고 {@link LabelAccessGuard#requireNotUnderDeidentReport} 단일 원천을 그대로 부르며
 * 응답 코드·사용자 메시지도 형제와 같은 값이다 — 다르면 응답 자체가 영상 상태를 알려 주는 단서가 된다.
 * <p>★ <b>조회(Load)는 막지 않는다.</b> 그 게이트의 <b>조회</b> 차단 범위는 개인정보의 위치를
 * 특정하는 산출물(라벨 좌표·프레임 이미지)에 한정하며 어노테이션 본문은 그 범위가 아니다.
 * ⚠ 형제인 메타 창구는 <b>조회도 막는다</b> — 그쪽은 개인정보 <b>판정</b> 축을 담기 때문이다.
 * 이 비대칭은 의도이며 「일관성」을 이유로 통일하지 말 것.
 * <p>★ <b>본인 업로드 자산은 이 게이트의 대상이 아니다</b> — 내부 파이프라인의 비식별
 * 라이프사이클이 없어 그 구간 자체가 존재하지 않는다.
 *
 * <h3>★ 무변경 저장은 오버레이 행을 만들지 않는다</h3>
 * <p>받은 본문이 <b>원본의 현재 본문과 같으면</b> 오버레이를 만들지 않고, 이미 있으면 지운다.
 * 행이 남으면 그 영상이 <b>저작물을 보유한 행</b>이 되어 본인 작업 목록에 등재되고 보존기간
 * 기산점까지 서는데, 열어 보기만 하고 아무것도 고치지 않았는데 「내 작업물」이 생기고 만료 시계가
 * 도는 것은 틀린 동작이다.
 * <p>형제 메타 창구의 자동 계산값 승격 차단과 <b>목적은 같고 판정 단위가 다르다</b> — 그쪽은
 * 항목마다 가르지만 이 창구는 구조체 한 벌을 통째로 덮어쓰므로 항목별 판정 단위가 없어
 * <b>구조체 전체</b>로 판정한다.
 * <p>같다는 판정은 <b>키 순서에 좌우되지 않는다</b>(같은 내용을 다시 저장했는데 행이 생기면 안 된다).
 * 인지·수용하는 대가: 사용자가 원본과 똑같은 내용을 일부러 확정해도 저장했다는 흔적이 남지 않는다.
 *
 * @design API-236
 * @design API-237
 * @design ERD-018
 * @design UC-024
 * @design AC-1068
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalWorkEventAnnotationService {

    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {
    };

    private final PortalWorkTargetResolver targetResolver;
    private final LsEvntAnnoRepository evntAnnoRepository;
    private final LsPortalUserEvntAnnoRepository overlayRepository;
    private final PortalUploadAssetRepository assetRepository;
    private final LabelAccessGuard accessGuard;
    private final ObjectMapper objectMapper;

    /**
     * 영상 이벤트 어노테이션 Load.
     *
     * <p>★ 비식별 누락 신고 게이트를 <b>걸지 않는다</b> — 그 게이트의 조회 차단 범위는 개인정보의
     * 위치를 특정하는 산출물에 한정하고 어노테이션 본문은 그 범위가 아니다(위 클래스 주석).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public PortalEventAnnotationResponse load(Long rawSn, String portalUserNo) {
        return read(targetResolver.resolveByVideo(rawSn, portalUserNo), portalUserNo);
    }

    /**
     * 영상 이벤트 어노테이션 저장. 적재 키가 (포털사용자, 영상)이라 영상당 한 벌이고 덮어쓴다.
     *
     * <p>게이트는 <b>직렬화(400)보다 먼저</b> 평가해 신고 구간에서는 어떤 쓰기도 시작되지 않게 한다.
     * 무변경 저장이면 오버레이를 만들지 않고 이미 있으면 지운다(위 클래스 주석).
     */
    @Transactional("controlTransactionManager")
    public PortalEventAnnotationResponse save(Long rawSn, String portalUserNo,
                                              PortalEventAnnotationUpdateRequest request) {
        PortalWorkTargetResolver.Target target = targetResolver.resolveByVideo(rawSn, portalUserNo);
        requireNotUnderDeidentReport(target);
        String json = serialize(request.annotation());

        if (target.isUpload()) {
            // ★ 반영 행 수를 버리지 않는다 — 0 은 판별자가 막았다는 뜻이라 저장이 아니다.
            PortalOwnedLedgerGuard.requireApplied(
                    assetRepository.upsertOwnedEventAnnotation(target.rawSn(), portalUserNo, json),
                    "eventAnnotation", target.rawSn());
        } else if (sameAsSource(target.rawSn(), json)) {
            // ★무변경 저장 — 오버레이를 만들지 않고, 이미 쌓여 있으면 지워 원본으로 되돌린다.
            int cleared = overlayRepository.deleteOverlay(portalUserNo, target.rawSn());
            log.info("[Portal] work event annotation unchanged — overlay not created"
                    + " rawSn={} clearedRows={}", target.rawSn(), cleared);
            return read(target, portalUserNo);
        } else {
            overlayRepository.upsertAnnotation(portalUserNo, target.rawSn(), json);
        }
        log.info("[Portal] work event annotation saved rawSn={} origin={}",
                target.rawSn(), target.origin());
        return read(target, portalUserNo);
    }

    // ======================== 내부 ========================

    /**
     * 비식별 누락 신고 구간이면 412. <b>데이터마트 자산에만</b> 건다.
     *
     * <p>본인 업로드 자산은 내부 파이프라인의 비식별 라이프사이클이 없어 그 구간이 존재하지 않는다 —
     * 게이트를 씌우면 정상 자산이 막히고, 반대로 데이터마트 자산에서 빼면 우회가 열린다.
     */
    private void requireNotUnderDeidentReport(PortalWorkTargetResolver.Target target) {
        if (!target.isUpload()) {
            accessGuard.requireNotUnderDeidentReport(target.rawSn());
        }
    }

    /**
     * 받은 본문이 <b>원본의 현재 본문과 같은가</b> — 무변경 저장 판정.
     *
     * <p>문자열이 아니라 <b>파싱한 트리</b>로 견준다. 키 순서·공백만 다른 같은 내용이 「다르다」로
     * 판정되면 사용자가 아무것도 고치지 않았는데 오버레이가 생기고 만료 시계가 돈다.
     *
     * <p>원본이 <b>없거나 손상돼</b> 비교가 성립하지 않으면 <b>같다고 단정하지 않는다</b> — 같다고
     * 보면 사용자가 쓴 값을 저장하지 않고 지우기까지 하므로, 이 판정은 저장하는 쪽으로 기운다.
     */
    private boolean sameAsSource(Long rawSn, String json) {
        String source = evntAnnoRepository.findByRawSn(rawSn).map(LsEvntAnno::getAnnoCn).orElse(null);
        if (source == null || source.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(source).equals(objectMapper.readTree(json));
        } catch (JsonProcessingException e) {
            log.warn("[Portal] stored event annotation is not comparable — treated as changed");
            return false;
        }
    }

    private PortalEventAnnotationResponse read(PortalWorkTargetResolver.Target target,
                                               String portalUserNo) {
        Optional<String> source = evntAnnoRepository.findByRawSn(target.rawSn())
                .map(LsEvntAnno::getAnnoCn);

        if (target.isUpload()) {
            // 본인 자산 — 오버레이 저장소를 아예 건드리지 않는다(조회 필터가 아니라 경로가 갈린다).
            return new PortalEventAnnotationResponse(target.rawSn(), parse(source.orElse(null)), false);
        }

        Optional<String> mine = overlayRepository
                .findByPortalUserNoAndSrcRawSn(portalUserNo, target.rawSn())
                .map(LsPortalUserEvntAnno::getAnnoCn);
        if (mine.isPresent()) {
            // 원본이 있었으면 <가린 것>이고, 없던 자리에 새로 쓴 것이면 가린 것이 없다.
            return new PortalEventAnnotationResponse(target.rawSn(), parse(mine.get()), source.isPresent());
        }
        return new PortalEventAnnotationResponse(target.rawSn(), parse(source.orElse(null)), false);
    }

    /**
     * 저장할 본문을 JSON 문자열로 만든다. 실패는 400 이며 <b>본문을 메시지에 싣지 않는다</b>
     * (전역 예외 처리기가 메시지를 그대로 로깅한다 — CWE-117/209).
     */
    private String serialize(Map<String, Object> annotation) {
        if (annotation == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "annotation 은 필수입니다.");
        }
        try {
            return objectMapper.writeValueAsString(annotation);
        } catch (JsonProcessingException e) {
            log.warn("[Portal] event annotation serialize failed");
            throw new CustomException(ErrorCode.INVALID_INPUT, "이벤트 어노테이션 본문 형식이 올바르지 않습니다.");
        }
    }

    /**
     * 보관된 원문을 객체로 되돌린다. 값이 없으면 {@code null} 을 그대로 내려준다 — 빈 객체로 지어내면
     * 「아직 없다」와 「비어 있다」가 구분되지 않는다.
     *
     * <p>보관된 원문이 객체가 아니거나 손상된 경우에도 <b>조회는 실패시키지 않는다</b> — 화면이 아예
     * 열리지 않으면 사용자가 자기 작업분을 다시 저장할 수단조차 잃는다. 값 없음으로 내리고 경고만 남긴다.
     */
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, OBJECT_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("[Portal] stored event annotation is not a JSON object — treated as absent");
            return null;
        }
    }
}
