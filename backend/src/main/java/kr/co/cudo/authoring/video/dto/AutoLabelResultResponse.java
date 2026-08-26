package kr.co.cudo.authoring.video.dto;

import java.util.List;

/**
 * 영상별 오토라벨 결과 응답. [design: API-044]
 * FE FrameLabels 인터페이스에 매핑된다 — videoId + objects 리스트.
 *
 * <p><b>표시명·표시색의 단일 진실원은 라벨 마스터({@code LS_LABEL})다.</b>
 * <ul>
 *   <li>{@code labelName} — 마스터에 연결된 라벨은 마스터 등록명({@code LBL_NM}), 미연결이면
 *       저장된 라벨명 원문({@code LS_DATA_LBL.LBL_NM}). <b>어떤 경우에도 null 이 아니다</b> —
 *       화면이 이 값을 그대로 그리므로 비면 빈칸이 된다.</li>
 *   <li>{@code color} — 마스터 등록 색상({@code COLR_VL}). 미연결이면 <b>null</b>이며 서버가
 *       임의의 기본색을 만들지 않는다(모든 라벨이 같은 색으로 보여 분포를 구분할 수 없고, 마스터에서
 *       색을 바꿔도 반영되지 않기 때문). 값 없음이 정상 경로이고 화면이 자체 폴백색으로 표시한다.</li>
 *   <li>{@code labelCode} — 저장된 라벨명 원문. 화면이 이 값으로 분포를 묶으므로 의미를 바꾸지 않는다.</li>
 * </ul>
 *
 * <p>비활성(soft delete, {@code USE_YN='N'}) 마스터는 미연결과 <b>동일 취급</b>한다 — 삭제한 라벨의
 * 이름·색이 화면에 되살아나지 않게 한다.
 *
 * <p>⚠ <b>구 주석 폐기</b>: <i>"라벨 색상/표시명 코드 테이블은 V1 시점 미존재 — labelCode 를
 * labelName 으로 fallback, color 는 기본값(#3B82F6) 사용"</i>. 라벨 마스터가 생기기 전의 전제이며
 * 지금은 거짓이다. 되살리면 표시명이 영문 COCO 클래스명으로, 색이 전부 같은 값으로 되돌아간다.
 */
public record AutoLabelResultResponse(
        Long videoId,
        List<LabelObjectDto> objects
) {

    public record LabelObjectDto(
            String id,
            String labelCode,
            String labelName,
            String color,
            Double confidence,
            String createdBy
    ) {}
}
