package kr.co.cudo.authoring.dataset.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.dataset.util.ShootingEnvironmentVocabulary;

/**
 * 영상 단위 촬영환경 저장 요청 — <b>PUT = 전체 교체(full replace)</b> 계약.
 *
 * <p>3필드를 <b>항상 함께</b> 전송한다. 생략(null)한 필드는 "변경 없음"이 아니라 <b>수동값 삭제</b>로
 * 해석되어 저장값이 초기화되고, 조회 시 촬영일시 파생값으로 폴백한다(부분 수정 PATCH 의미 없음).
 *
 * <p><b>프리필 왕복 주의 — 파생값을 되돌려보내면 수동값으로 승격된다</b>: 조회
 * ({@code GET /v1/videos/{rawSn}/environment-meta})는 수동값이 없으면 촬영일시 <b>파생값</b>을 값으로
 * 채워 돌려준다(출처는 {@code EnvironmentMetaResponse.*Source} 의 {@code DERIVED}). 폼 프리필값을 그대로
 * 재전송하면 그 파생값이 <b>수동값(MANUAL)으로 굳어져</b>, 이후 관제 재적재로 촬영일시(SHT_DT)가 정정돼도
 * 옛 값이 스냅샷·export 에 고정된다. 따라서 클라이언트는 <b>사용자가 직접 고른 필드만 값으로 보내고,
 * 파생 상태로 두려는 필드는 {@code null} 로 전송</b>해야 한다 — 판단 근거는 응답의
 * {@code weatherSource}/{@code timeOfDaySource}/{@code seasonSource}({@code MANUAL}/{@code DERIVED})다.
 *
 * <p>Entity 직접 바인딩 없이 전용 DTO 만 사용해 Mass Assignment(CWE-915)를 차단하고, 길이는 저장
 * 컬럼 상한(VARCHAR(20))으로 제한한다. 허용값 화이트리스트 검증은 서비스 진입부에서 이중 수행한다.
 */
@Schema(description = "영상 촬영환경 저장 요청(전체 교체 — 3필드 항상 함께 전송)")
public record EnvironmentMetaUpdateRequest(

        @Schema(description = "날씨(맑음/흐림/비/눈/안개). null 이면 수동값 삭제", example = "맑음")
        @Size(max = ShootingEnvironmentVocabulary.MAX_LENGTH)
        String weather,

        @Schema(description = "시간대 코드(DAY/NGT). null 이면 수동값 삭제", example = "DAY")
        @Size(max = ShootingEnvironmentVocabulary.MAX_LENGTH)
        String timeOfDay,

        @Schema(description = "계절 코드(SPRING/SUMMER/FALL/WINTER). null 이면 수동값 삭제", example = "SUMMER")
        @Size(max = ShootingEnvironmentVocabulary.MAX_LENGTH)
        String season
) {}
