package com.innbucks.marketplaceservice.api;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place a phone number becomes E.164 in this service.
 *
 * <p>Two surfaces store numbers — the payer on an order and the recipient on a
 * delivery address — and both feed something that dials or messages: the
 * EcoCash PIN prompt on one side, a courier on the other. They must agree on
 * what a valid number is, because a number one surface accepts and the other
 * rejects is a shopper who can save an address they can never check out with.
 *
 * <p>Fails CLOSED: letters, stray symbols and non-dialable numbers are
 * REFUSED, never stripped into something that parses. The raw value is never
 * logged — it is a (possibly mistyped) phone number.
 */
@Component
public class Msisdns {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    /** Default region for a number typed without a country code — the cell's
     *  own country, since that is where its shoppers are. */
    private final String defaultRegion;

    public Msisdns(@Value("${innbucks.country}") String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /**
     * @param field the request field to name in the refusal, so the app can
     *              highlight the right input
     * @throws ApiException 400 {@code invalid_msisdn}
     */
    public String normalize(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("invalid_msisdn", field + " is required");
        }
        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(raw, defaultRegion);
            if (!PHONE_UTIL.isValidNumber(parsed)) {
                throw ApiException.badRequest("invalid_msisdn",
                        field + " is not a valid phone number");
            }
            return PHONE_UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException ex) {
            throw ApiException.badRequest("invalid_msisdn", field + " is not a valid phone number");
        }
    }
}
