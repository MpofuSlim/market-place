package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Who did it: the support agent behind a note, a message or an activity row.
 * {@code uuid} is the caller's {@code userUuid} (the stable identity) and
 * {@code login} the token subject — the e-mail a supervisor recognises.
 */
@Schema(description = "The support agent who performed an action")
public record SupportAgent(
        @Schema(description = "The agent's user uuid", example = "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86")
        String uuid,

        @Schema(description = "The agent's sign-in (e-mail)", example = "tariro.moyo@innbucks.co.zw")
        String login) {

    public static SupportAgent of(AuthenticatedUser caller) {
        return new SupportAgent(caller.uuid(), caller.login());
    }
}
