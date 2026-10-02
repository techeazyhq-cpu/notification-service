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

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The service's error dictionary (ADR-031): every error an API returns and every reason a message fails, each with a
 * stable symbolic {@link #code()} (what clients branch on), a stable numbered {@link #errorId()} (what people quote
 * to support and search runbooks for), and the cause and resolution in plain words. docs/error-codes.md and
 * {@code GET /v1/errors} are generated from this enum.
 *
 * <p>Codes are never renamed, renumbered or reused; a retired code stays here. A new code takes the next free id in
 * its category's range.
 */
public enum ErrorCode {

    INVALID_REQUEST("NS-1000", ErrorCategory.REQUEST, 400, false,
            "The request is invalid",
            "A required field is missing, a value is out of range or badly formatted, or the body is not valid JSON.",
            "Correct the request using the message, which names the field, and send it again."),
    PAYLOAD_TOO_LARGE("NS-1001", ErrorCategory.REQUEST, 413, false,
            "The upload is too large",
            "The uploaded file or request body exceeds the size the service accepts.",
            "Split the recipients over several bulk requests, or send a smaller file."),
    METHOD_NOT_ALLOWED("NS-1002", ErrorCategory.REQUEST, 405, false,
            "The HTTP method is not supported here",
            "The path exists but does not accept this HTTP method.",
            "Use the method documented for the endpoint in the OpenAPI description."),
    UNSUPPORTED_MEDIA_TYPE("NS-1003", ErrorCategory.REQUEST, 415, false,
            "The content type is not supported",
            "The Content-Type header names a format this endpoint does not read.",
            "Send application/json, or multipart/form-data for CSV uploads."),
    NOT_ACCEPTABLE("NS-1004", ErrorCategory.REQUEST, 406, false,
            "The requested response format is not available",
            "The Accept header asks for a format this endpoint cannot produce.",
            "Accept application/json, or text/csv for exports."),
    CATEGORY_NOT_ALLOWED("NS-1005", ErrorCategory.REQUEST, 400, false,
            "The message category is not allowed here",
            "One-time passwords are sent one at a time, so a bulk request cannot have the OTP category.",
            "Send each one-time password as a single request with category OTP."),

    UNAUTHORIZED("NS-2001", ErrorCategory.ACCESS, 401, false,
            "The API key is missing or not valid",
            "The X-API-Key header is absent, or the key is unknown, rotated or belongs to a disabled client.",
            "Send a current API key in X-API-Key; ask an administrator to rotate or re-enable it if needed."),
    INVALID_CREDENTIALS("NS-2002", ErrorCategory.ACCESS, 401, false,
            "The username or password is wrong",
            "The administrator sign-in did not match an active account.",
            "Check the username and password; repeated failures lock the account for a while."),
    OTP_REQUIRED("NS-2003", ErrorCategory.ACCESS, 401, false,
            "A verification code is required",
            "The administrator account has two-factor authentication and no valid code was sent.",
            "Send the current code from the authenticator app with the password."),
    ACCOUNT_LOCKED("NS-2004", ErrorCategory.ACCESS, 429, true,
            "The account is temporarily locked",
            "Too many failed sign-ins in a short time locked the administrator account.",
            "Wait for the lockout to end, or ask another administrator to unlock the account."),
    REAUTHENTICATION_FAILED("NS-2005", ErrorCategory.ACCESS, 403, false,
            "The sensitive action was not confirmed",
            "The current password or verification code needed to confirm this action was wrong.",
            "Re-enter the current password and code, then repeat the action."),
    ACCOUNT_SETUP_REQUIRED("NS-2006", ErrorCategory.ACCESS, 403, false,
            "Account setup is not finished",
            "The administrator must change the initial password and enable two-factor authentication first.",
            "Open My account in the admin console and complete the steps it lists."),
    FORBIDDEN("NS-2007", ErrorCategory.ACCESS, 403, false,
            "The action is not allowed for this role",
            "The signed-in administrator's role does not include this action.",
            "Ask an administrator with the ADMIN role to perform it or to change your role."),
    CHANNEL_NOT_ALLOWED("NS-2008", ErrorCategory.ACCESS, 403, false,
            "The channel is not enabled for this client",
            "The client may only send on the channels an administrator enabled for it.",
            "Use an enabled channel (GET /v1/account lists them), or ask an administrator to enable this one."),
    TEMPLATE_READ_ONLY("NS-2009", ErrorCategory.ACCESS, 403, false,
            "The template is read-only",
            "Shared templates are managed by administrators and cannot be changed by a client.",
            "Copy it into a template of your own under a new name, or ask an administrator to change it."),
    SIGN_IN_REQUIRED("NS-2010", ErrorCategory.ACCESS, 401, false,
            "Sign-in is required",
            "The admin API call carried no session token, or the session expired or was signed out.",
            "Sign in again in the admin console, or send a current bearer token."),

    NOT_FOUND("NS-3001", ErrorCategory.RESOURCE, 404, false,
            "The resource does not exist",
            "No resource with this identifier exists, or it belongs to another client.",
            "Check the identifier; resources are only visible to the client that created them."),
    INVALID_STATE("NS-3002", ErrorCategory.RESOURCE, 409, false,
            "The resource's state does not allow this action",
            "The resource changed or is in a state where this action does not apply.",
            "Read the resource again, then decide whether the action still makes sense."),
    TEMPLATE_EXISTS("NS-3003", ErrorCategory.RESOURCE, 409, false,
            "A template with this name already exists",
            "Template names are unique per client and channel.",
            "Choose another name, or update the existing template instead."),
    TEMPLATE_NAME_RESERVED("NS-3004", ErrorCategory.RESOURCE, 409, false,
            "The template name is reserved",
            "A shared template already uses this name, so a client template may not shadow it.",
            "Choose another name."),
    TEMPLATE_LIMIT("NS-3005", ErrorCategory.RESOURCE, 409, false,
            "The template limit is reached",
            "The client already has the maximum number of templates.",
            "Delete templates that are no longer used, or ask an administrator to raise the limit."),
    SENDER_EXISTS("NS-3006", ErrorCategory.RESOURCE, 409, false,
            "The sender address is already registered",
            "This e-mail sender address is already registered for the client.",
            "Use the existing sender; resend its verification if it is not verified yet."),
    SENDER_NOT_VERIFIED("NS-3007", ErrorCategory.RESOURCE, 422, false,
            "The sender address is not verified",
            "E-mail can only be sent from an address whose owner confirmed the verification link.",
            "Open the verification e-mail and confirm, or send without 'from' to use the default sender."),
    PROVIDER_DESTINATION_REFUSED("NS-3008", ErrorCategory.RESOURCE, 400, false,
            "The provider destination is not allowed",
            "The provider URL points at a private, loopback or link-local address, or is not HTTPS (ADR-022).",
            "Use the provider's public HTTPS endpoint, or add an internal gateway to the trusted hosts list."),

    INSUFFICIENT_CREDIT("NS-4001", ErrorCategory.BILLING, 402, false,
            "There is not enough prepaid credit",
            "The client's prepaid balance does not cover the messages in this request.",
            "Top up the balance (or ask the account owner to), then send again."),
    SPEND_CAP_EXCEEDED("NS-4002", ErrorCategory.BILLING, 402, false,
            "The monthly spend cap is reached",
            "This request would take the client over the spending cap set on its billing account.",
            "Wait for the next billing period, or ask an administrator to raise the cap."),
    ACCOUNT_SUSPENDED("NS-4003", ErrorCategory.BILLING, 403, false,
            "The billing account is suspended",
            "The client's billing account is suspended, so no messages are accepted.",
            "Contact the platform's billing administrator to settle and reactivate the account."),

    RATE_LIMITED("NS-5001", ErrorCategory.CAPACITY, 429, true,
            "The rate limit is exceeded",
            "The client sent more requests than its rate limit allows in the current window.",
            "Wait the number of seconds in the Retry-After header, then retry; spread sends over time."),

    DELIVERY_REJECTED("NS-6001", ErrorCategory.DELIVERY, null, false,
            "The provider rejected the message",
            "The SMS, e-mail or messaging provider refused this message, typically an invalid or blocked recipient.",
            "Check the recipient; the message's lastError carries the provider's reason. Do not resend unchanged."),
    TEMPLATE_VARIABLE_MISSING("NS-6002", ErrorCategory.DELIVERY, null, false,
            "A template variable has no value",
            "The template uses a {{variable}} that the request did not supply for this recipient.",
            "Send the request again with every variable the template lists (GET /v1/templates)."),
    MESSAGE_CONTENT_MISSING("NS-6003", ErrorCategory.DELIVERY, null, false,
            "The message has no content to send",
            "The request this message belongs to has no body, typically because its personal data was erased.",
            "Send a new request; erased content cannot be recovered."),
    DELIVERY_ATTEMPTS_EXHAUSTED("NS-6004", ErrorCategory.DELIVERY, null, true,
            "Every delivery attempt failed",
            "All retries failed for temporary reasons such as provider outages or timeouts.",
            "Retry the message later; operators can reprocess it from the dead-letter queue once providers recover."),
    DELIVERY_DEAD_LETTERED("NS-6005", ErrorCategory.DELIVERY, null, true,
            "The message could not be handed to a worker",
            "The message broker gave up delivering this message to the dispatcher after repeated failures.",
            "Operators reprocess it from the dead-letter queue; the platform team checks the dispatcher logs."),
    PROVIDER_TEMPORARILY_FAILING("NS-6006", ErrorCategory.DELIVERY, null, true,
            "The provider is failing; delivery will be retried",
            "The last attempt failed for a temporary reason; the message is RETRYING with back-off.",
            "No action needed; the message is retried automatically until it is sent or attempts run out."),
    OTP_EXPIRED("NS-6007", ErrorCategory.DELIVERY, null, false,
            "The one-time password expired before it could be sent",
            "The OTP could not reach a provider within its validity, so it was dropped rather than sent late.",
            "Ask the user to request a new code; check the provider and backlog alerts if this happens often."),

    EMAIL_NOT_AVAILABLE("NS-8001", ErrorCategory.DEPENDENCY, 503, true,
            "E-mail is not available",
            "The e-mail service needed for this action (for example sender verification) is not configured or up.",
            "Retry later; if it persists, the platform team checks the e-mail provider configuration."),

    INTERNAL_ERROR("NS-9001", ErrorCategory.PLATFORM, 500, true,
            "Something went wrong on our side",
            "An unexpected fault occurred while handling the request.",
            "Retry once; if it happens again, report the traceId in the response to the platform team.");

    private final String errorId;
    private final ErrorCategory category;
    private final Integer httpStatus;
    private final boolean retryable;
    private final String title;
    private final String cause;
    private final String resolution;

    ErrorCode(String errorId, ErrorCategory category, Integer httpStatus, boolean retryable, String title,
              String cause, String resolution) {
        this.errorId = errorId;
        this.category = category;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.title = title;
        this.cause = cause;
        this.resolution = resolution;
    }

    /** The symbolic code clients branch on, e.g. {@code RATE_LIMITED}. */
    public String code() {
        return name();
    }

    /** The numbered identifier people quote and search for, e.g. {@code NS-5001}. */
    public String errorId() {
        return errorId;
    }

    public ErrorCategory category() {
        return category;
    }

    /** The HTTP status an API answers with; empty for delivery outcomes, which are reported on the message. */
    public OptionalInt httpStatus() {
        return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }

    /** Whether the same call, unchanged, can succeed later. */
    public boolean retryable() {
        return retryable;
    }

    public String title() {
        return title;
    }

    public String cause() {
        return cause;
    }

    public String resolution() {
        return resolution;
    }

    public static Optional<ErrorCode> findByErrorId(String errorId) {
        String wanted = errorId.toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(code -> code.errorId.equals(wanted)).findFirst();
    }

    /**
     * The code for a refusal raised by the web framework or a library with only an HTTP status, such as an unknown
     * path or an unsupported method. Statuses without a more specific code count as an invalid request.
     */
    public static ErrorCode forHttpRefusal(int httpStatus) {
        return switch (httpStatus) {
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> INVALID_STATE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> INVALID_REQUEST;
        };
    }

    public static Optional<ErrorCode> findByCode(String code) {
        return Arrays.stream(values()).filter(candidate -> candidate.name().equals(code)).findFirst();
    }

    /** Looks a code up by its numbered error id ({@code NS-5001}, any case) or by its symbolic code. */
    public static Optional<ErrorCode> findByErrorIdOrCode(String errorIdOrCode) {
        return findByErrorId(errorIdOrCode).or(() -> findByCode(errorIdOrCode));
    }
}
