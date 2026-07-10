package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * SAM2 Track 시작 요청.
 * - srcSn       : 시작 프레임의 SRC_SN
 * - trackId     : 트랙 식별자 (이전 라벨에 부여된 식별자, 신규 트랙이면 클라이언트가 UUID 발급)
 * - prevPolygon : 시작 프레임의 폴리곤 좌표 [[x,y],...] (최소 3 점)
 * - label       : 객체 라벨명
 * - nextSrcSns  : 트래킹 대상 후속 프레임의 SRC_SN 리스트 (CWE-770 — 최대 50)
 */
public record Sam2TrackRequest(
        @NotNull Long srcSn,
        @NotBlank String trackId,
        @NotEmpty @Size(min = 3, max = 1000) List<List<Double>> prevPolygon,
        @NotBlank @Size(max = 80) String label,
        @NotEmpty @Size(max = 50) List<Long> nextSrcSns
) {
}
