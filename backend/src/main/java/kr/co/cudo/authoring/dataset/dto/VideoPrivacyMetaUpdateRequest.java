package kr.co.cudo.authoring.dataset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * 영상 단위 개인정보 메타 저장 요청 — <b>PUT = 전체 교체(full replace)</b> 계약.
 *
 * <p>3필드를 <b>항상 함께</b> 전송한다. 생략(null)한 필드는 "변경 없음"이 아니라 <b>수동값 삭제</b>로
 * 해석되어 저장값이 초기화되고, 조회 시 비식별 기본상수 프리필({@code DERIVED})로 폴백한다.
 *
 * <p><b>프리필 왕복 주의 — 프리필을 되돌려보내면 수동값으로 승격된다</b>: 조회 응답은 수동값이 없으면
 * 기본상수를 값으로 채워 돌려준다(출처 {@code DERIVED}). 폼 프리필값을 그대로 재전송하면 그 상수가
 * <b>사람이 판정한 값(MANUAL)으로 굳어져</b> export JSON 에 사실처럼 실린다. 클라이언트는 <b>사용자가
 * 직접 고른 필드만 값으로 보내고, 프리필 상태로 두려는 필드는 {@code null} 로 전송</b>해야 한다.
 *
 * <p>보안:
 * <ul>
 *   <li><b>입력검증(CWE-20/79)</b>: {@code @Pattern("\\A[YN]\\z")} 화이트리스트 — CHAR(1) 오염·자유텍스트
 *       (XSS 표면) 차단. 비우려면 null(필드 생략), 빈 문자열/기타 값은 400 으로 거부.
 *       <b>{@code ^...$} 를 쓰지 않는 이유</b>: Java 정규식의 {@code $} 는 <b>후행 개행 앞에서도 매치</b>돼
 *       {@code "Y\n"} 이 그대로 통과한다(CRLF 주입 표면). 지금은 서비스단 {@code trim()}+화이트리스트가
 *       흡수하지만, 이 javadoc 이 "이 {@code @Pattern} 이 CHAR(1) 오염을 차단한다"고 단정하고 있으므로
 *       단정과 실제를 일치시킨다 — {@code \A}/{@code \z} 는 입력 전체의 시작·끝만 매치한다.</li>
 *   <li><b>Mass Assignment(CWE-915)</b>: Entity 직접 바인딩 금지 — 허용 필드만 명시, 미지 필드는 무시.</li>
 * </ul>
 *
 * @param anonymity       익명정보 포함여부(Y/N, null=미입력/삭제)
 * @param pseudonymity    가명정보 포함여부(Y/N, null=미입력/삭제)
 * @param privacyIncluded 개인정보 포함여부(Y/N, null=미입력/삭제)
 */
@Schema(description = "영상 개인정보 메타 저장 요청(전체 교체 — 3필드 항상 함께 전송)")
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoPrivacyMetaUpdateRequest(

        @Schema(description = "익명정보 포함여부(Y/N). null 이면 수동값 삭제", example = "Y")
        @Pattern(regexp = "\\A[YN]\\z", message = "anonymity 는 Y 또는 N 이어야 합니다.")
        String anonymity,

        @Schema(description = "가명정보 포함여부(Y/N). null 이면 수동값 삭제", example = "N")
        @Pattern(regexp = "\\A[YN]\\z", message = "pseudonymity 는 Y 또는 N 이어야 합니다.")
        String pseudonymity,

        @Schema(description = "개인정보 포함여부(Y/N). null 이면 수동값 삭제", example = "N")
        @Pattern(regexp = "\\A[YN]\\z", message = "privacyIncluded 는 Y 또는 N 이어야 합니다.")
        String privacyIncluded
) {}
