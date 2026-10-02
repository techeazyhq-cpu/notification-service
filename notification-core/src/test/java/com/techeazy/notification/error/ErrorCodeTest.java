/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogue is a published contract (ADR-031): clients and runbooks key on {@code code} and {@code errorId}, so
 * both must be unique, stable in shape, and fully described.
 */
class ErrorCodeTest {

    @Test
    void everyErrorIdIsUniqueAndShapedNsFollowedByFourDigits() {
        Map<String, Long> idCounts = Arrays.stream(ErrorCode.values())
                .collect(Collectors.groupingBy(ErrorCode::errorId, Collectors.counting()));

        assertThat(idCounts).allSatisfy((errorId, count) -> {
            assertThat(errorId).matches("NS-\\d{4}");
            assertThat(count).as("uses of %s", errorId).isEqualTo(1L);
        });
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void theErrorIdRangeNamesTheCategory(ErrorCode code) {
        assertThat(code.errorId().charAt(3)).isEqualTo(code.category().rangeDigit());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeExplainsItselfToWhoeverMeetsIt(ErrorCode code) {
        assertThat(code.title()).isNotBlank().doesNotEndWith(".");
        assertThat(code.cause()).isNotBlank().endsWith(".");
        assertThat(code.resolution()).isNotBlank().endsWith(".");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void requestErrorsCarryAnHttpStatusAndDeliveryOutcomesDoNot(ErrorCode code) {
        if (code.category() == ErrorCategory.DELIVERY) {
            assertThat(code.httpStatus()).isEmpty();
        } else {
            assertThat(code.httpStatus()).isPresent();
            assertThat(code.httpStatus().getAsInt()).isBetween(400, 599);
        }
    }

    @Test
    void retryableMeansTheSameCallCanSucceedLaterWithoutChanges() {
        assertThat(ErrorCode.RATE_LIMITED.retryable()).isTrue();
        assertThat(ErrorCode.EMAIL_NOT_AVAILABLE.retryable()).isTrue();
        assertThat(ErrorCode.INTERNAL_ERROR.retryable()).isTrue();
        assertThat(ErrorCode.INVALID_REQUEST.retryable()).isFalse();
        assertThat(ErrorCode.INSUFFICIENT_CREDIT.retryable()).isFalse();
        assertThat(ErrorCode.DELIVERY_REJECTED.retryable()).isFalse();
        assertThat(ErrorCode.DELIVERY_ATTEMPTS_EXHAUSTED.retryable()).isTrue();
    }

    @Test
    void theCodesClientsAlreadyParseKeepTheirNamesAndStatuses() {
        Map<String, Integer> publishedBeforeTheCatalogue = Map.ofEntries(
                Map.entry("INVALID_REQUEST", 400), Map.entry("PAYLOAD_TOO_LARGE", 413),
                Map.entry("UNAUTHORIZED", 401), Map.entry("CHANNEL_NOT_ALLOWED", 403),
                Map.entry("TEMPLATE_READ_ONLY", 403), Map.entry("NOT_FOUND", 404), Map.entry("INVALID_STATE", 409),
                Map.entry("TEMPLATE_EXISTS", 409), Map.entry("TEMPLATE_NAME_RESERVED", 409),
                Map.entry("TEMPLATE_LIMIT", 409), Map.entry("SENDER_EXISTS", 409),
                Map.entry("SENDER_NOT_VERIFIED", 422), Map.entry("INSUFFICIENT_CREDIT", 402),
                Map.entry("SPEND_CAP_EXCEEDED", 402), Map.entry("ACCOUNT_SUSPENDED", 403),
                Map.entry("RATE_LIMITED", 429), Map.entry("EMAIL_NOT_AVAILABLE", 503),
                Map.entry("INTERNAL_ERROR", 500), Map.entry("INVALID_CREDENTIALS", 401),
                Map.entry("OTP_REQUIRED", 401), Map.entry("ACCOUNT_LOCKED", 429),
                Map.entry("REAUTHENTICATION_FAILED", 403), Map.entry("ACCOUNT_SETUP_REQUIRED", 403),
                Map.entry("PROVIDER_DESTINATION_REFUSED", 400));
        Map<String, ErrorCode> byCode = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(ErrorCode::code, Function.identity()));

        assertThat(publishedBeforeTheCatalogue).allSatisfy((code, status) ->
                assertThat(byCode.get(code).httpStatus()).as(code).hasValue(status));
    }

    @Test
    void anErrorIdLeadsBackToItsCode() {
        assertThat(ErrorCode.findByErrorId(ErrorCode.RATE_LIMITED.errorId())).contains(ErrorCode.RATE_LIMITED);
        assertThat(ErrorCode.findByErrorId("ns-5001")).contains(ErrorCode.RATE_LIMITED);
        assertThat(ErrorCode.findByErrorId("NS-0000")).isEmpty();
    }

    @Test
    void aFrameworkRefusalMapsOntoTheMatchingCode() {
        assertThat(ErrorCode.forHttpRefusal(404)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.forHttpRefusal(405)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.forHttpRefusal(406)).isEqualTo(ErrorCode.NOT_ACCEPTABLE);
        assertThat(ErrorCode.forHttpRefusal(409)).isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(ErrorCode.forHttpRefusal(413)).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(ErrorCode.forHttpRefusal(415)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorCode.forHttpRefusal(403)).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ErrorCode.forHttpRefusal(400)).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ErrorCode.forHttpRefusal(422)).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void aSymbolicCodeLeadsBackToItsCode() {
        assertThat(ErrorCode.findByCode("SPEND_CAP_EXCEEDED")).contains(ErrorCode.SPEND_CAP_EXCEEDED);
        assertThat(ErrorCode.findByCode("NO_SUCH_CODE")).isEmpty();
    }
}
