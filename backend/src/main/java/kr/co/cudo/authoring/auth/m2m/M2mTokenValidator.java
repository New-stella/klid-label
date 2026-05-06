package kr.co.cudo.authoring.auth.m2m;

public interface M2mTokenValidator {
    boolean isValid(String token);
}
