package kr.co.cudo.authoring.dataset.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * outbox {@code PAYLOAD}(동결 스냅샷 JSON) 역직렬화 DTO.
 *
 * <p>Phase 2 {@code DatasetVideoMetaSnapshotService.toPayload()} 가 직렬화한 비식별 메타 컬럼형 JSON 을
 * 그대로 매핑한다. 알 수 없는 필드는 무시(전방 호환). 관리 컬럼(activeYn·regDt·regId)은 페이로드에
 * 없으므로 복제 시 워커가 기본값(activeYn='Y', regDt=now)으로 채운다.
 * 촬영환경 수동값({@code wthrNm})과 event_annotation 동결값({@code evntAnnoCn})은 동결 내용이라
 * 페이로드에 포함된다(구 배포 페이로드엔 키가 없어 null 매핑 — 아래 하위호환 참조).
 *
 * <p><b>@design INT-009</b> — 「복제 범위 — 원본과 동형이며 전 컬럼을 옮긴다」. 이 record 는 복제 경로의
 * <b>전송 계약</b>이라, 원본 동결 메타에 컬럼이 추가되면 여기에도 대응 컴포넌트가 함께 추가돼야 한다.
 * 한 단이라도 빠지면 복제본 DDL 에 컬럼이 있어도 값이 영구히 비어 있게 된다.
 *
 * <p><b>하위호환(구 페이로드)</b>: 이 record 에 컴포넌트를 추가해도 <b>이미 적재된 발신함 행</b>은
 * 그 키를 갖지 않는다. Jackson 은 누락된 creator 프로퍼티를 참조형 기본값(null)으로 채우므로
 * (모든 컴포넌트가 boxed 타입 — primitive 를 쓰면 이 성질이 깨진다) 구 페이로드도 오류 없이 복원된다.
 * 이 성질이 깨지면 미처리 발신함이 전량 역직렬화 실패 → dead-letter 로 간다.
 *
 * <p><b>보안</b>: 페이로드는 비식별 메타(경로/좌표/코드값)에 더해 <b>동결된 이벤트 어노테이션</b>
 * ({@code evntAnnoCn} — 캡션·CoT·질문 등 자유서술 원문)을 함께 나른다. 자유서술이라 형태를 좁힐 수
 * 없지만, 이는 INT-009 「전 컬럼 복제」에 따른 정상 범위이며 그 값은 검수 승인 시점에 동결된 작업
 * 산출물이다. PII·토큰·원본 비-비식별 이미지는 담지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetaReplicationPayload(
        Long rawSn,
        String snpshtHash,
        Long orgnlRawSn,
        String vmsClipId,
        String vmsCctvId,
        String rawFilePathNm,
        LocalDateTime shtDt,
        Integer vdoLenSec,
        String lclgvCd,
        String prvcYn,
        String prvcTypeCd,
        String deIdentYn,
        String aiCrtYn,
        String evntTypeCd,
        String cctvNm,
        BigDecimal wgs84Lat,
        BigDecimal wgs84Lot,
        String sidoNm,
        String sggNm,
        String fileFmt,
        String evntNm,
        String vdoCdc,
        BigDecimal fps,
        Long bitRt,
        BigDecimal asprtRt,
        String resl,
        Integer vdoWdth,
        Integer vdoHgt,
        Long fileSz,
        String dayNgtCd,
        String sesnCd,
        String wthrNm,
        String evntAnnoCn,
        LocalDateTime rvwCmplDt
) {
}
