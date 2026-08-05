package com.innbucks.marketplaceservice.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Fleet-standard response envelope: {@code { "code": ..., "message": ..., "data": ... }}.
 * Same shape as the rest of the InnBucks fleet so FE/API consumers see one
 * contract across services.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResult<T>(String code, String message, T data) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>("OK", "Success", data);
    }

    public static <T> ApiResult<T> ok(String message, T data) {
        return new ApiResult<>("OK", message, data);
    }

    public static <T> ApiResult<T> created(T data) {
        return new ApiResult<>("CREATED", "Created", data);
    }

    public static <T> ApiResult<T> error(String code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
