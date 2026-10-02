# Error code dictionary

Generated from `ErrorCode` in notification-core by `ErrorCatalogueDocumentTest`; do not edit by hand.
The same catalogue is served without credentials at `GET /v1/errors` and `GET /v1/errors/{errorId}` on the
client API, and at `GET /api/admin/errors` and `GET /api/admin/errors/{errorId}` on the admin API. The
`docs` link in an error body points at the entry on the API that answered. See ADR-031.

Every error response carries the same fields:

```json
{
  "code": "RATE_LIMITED",
  "message": "API rate limit exceeded",
  "errorId": "NS-5001",
  "category": "CAPACITY",
  "retryable": true,
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "docs": "/v1/errors/NS-5001"
}
```

- **code** is stable and what programs branch on. **message** is for people and may change wording.
- **errorId** is stable and what people quote to support; the digit after `NS-` is the category.
- **retryable** says whether the same call, unchanged, can succeed later.
- **traceId** finds the request's trace and every log line it produced (ADR-023).

A failed message reports its delivery outcome the same way, in `errorCode` and `errorId` on the
message (`GET /v1/messages/{id}`), next to the provider's own wording in `lastError`.

## Categories

| Range | Category | Meaning |
|---|---|---|
| NS-1xxx | Request | The request itself is malformed or incomplete; the caller fixes it. |
| NS-2xxx | Access | Authentication or permission was refused; the caller fixes credentials or rights. |
| NS-3xxx | Resource | The resource is missing or in a state that does not allow the action. |
| NS-4xxx | Billing | The client's billing account does not allow the send; the account owner acts. |
| NS-5xxx | Capacity | A rate limit or capacity limit applies; waiting and retrying succeeds. |
| NS-6xxx | Delivery | A message was accepted but could not be delivered; reported on the message. |
| NS-8xxx | Dependency | A system the service relies on is unavailable; the platform team acts. |
| NS-9xxx | Platform | An unexpected fault in the service; the platform team investigates. |

## Summary

| Error id | Code | HTTP | Retryable | Title |
|---|---|---|---|---|
| [NS-1000](#ns-1000-invalid-request) | `INVALID_REQUEST` | 400 | no | The request is invalid |
| [NS-1001](#ns-1001-payload-too-large) | `PAYLOAD_TOO_LARGE` | 413 | no | The upload is too large |
| [NS-1002](#ns-1002-method-not-allowed) | `METHOD_NOT_ALLOWED` | 405 | no | The HTTP method is not supported here |
| [NS-1003](#ns-1003-unsupported-media-type) | `UNSUPPORTED_MEDIA_TYPE` | 415 | no | The content type is not supported |
| [NS-1004](#ns-1004-not-acceptable) | `NOT_ACCEPTABLE` | 406 | no | The requested response format is not available |
| [NS-1005](#ns-1005-category-not-allowed) | `CATEGORY_NOT_ALLOWED` | 400 | no | The message category is not allowed here |
| [NS-2001](#ns-2001-unauthorized) | `UNAUTHORIZED` | 401 | no | The API key is missing or not valid |
| [NS-2002](#ns-2002-invalid-credentials) | `INVALID_CREDENTIALS` | 401 | no | The username or password is wrong |
| [NS-2003](#ns-2003-otp-required) | `OTP_REQUIRED` | 401 | no | A verification code is required |
| [NS-2004](#ns-2004-account-locked) | `ACCOUNT_LOCKED` | 429 | yes | The account is temporarily locked |
| [NS-2005](#ns-2005-reauthentication-failed) | `REAUTHENTICATION_FAILED` | 403 | no | The sensitive action was not confirmed |
| [NS-2006](#ns-2006-account-setup-required) | `ACCOUNT_SETUP_REQUIRED` | 403 | no | Account setup is not finished |
| [NS-2007](#ns-2007-forbidden) | `FORBIDDEN` | 403 | no | The action is not allowed for this role |
| [NS-2008](#ns-2008-channel-not-allowed) | `CHANNEL_NOT_ALLOWED` | 403 | no | The channel is not enabled for this client |
| [NS-2009](#ns-2009-template-read-only) | `TEMPLATE_READ_ONLY` | 403 | no | The template is read-only |
| [NS-2010](#ns-2010-sign-in-required) | `SIGN_IN_REQUIRED` | 401 | no | Sign-in is required |
| [NS-3001](#ns-3001-not-found) | `NOT_FOUND` | 404 | no | The resource does not exist |
| [NS-3002](#ns-3002-invalid-state) | `INVALID_STATE` | 409 | no | The resource's state does not allow this action |
| [NS-3003](#ns-3003-template-exists) | `TEMPLATE_EXISTS` | 409 | no | A template with this name already exists |
| [NS-3004](#ns-3004-template-name-reserved) | `TEMPLATE_NAME_RESERVED` | 409 | no | The template name is reserved |
| [NS-3005](#ns-3005-template-limit) | `TEMPLATE_LIMIT` | 409 | no | The template limit is reached |
| [NS-3006](#ns-3006-sender-exists) | `SENDER_EXISTS` | 409 | no | The sender address is already registered |
| [NS-3007](#ns-3007-sender-not-verified) | `SENDER_NOT_VERIFIED` | 422 | no | The sender address is not verified |
| [NS-3008](#ns-3008-provider-destination-refused) | `PROVIDER_DESTINATION_REFUSED` | 400 | no | The provider destination is not allowed |
| [NS-4001](#ns-4001-insufficient-credit) | `INSUFFICIENT_CREDIT` | 402 | no | There is not enough prepaid credit |
| [NS-4002](#ns-4002-spend-cap-exceeded) | `SPEND_CAP_EXCEEDED` | 402 | no | The monthly spend cap is reached |
| [NS-4003](#ns-4003-account-suspended) | `ACCOUNT_SUSPENDED` | 403 | no | The billing account is suspended |
| [NS-5001](#ns-5001-rate-limited) | `RATE_LIMITED` | 429 | yes | The rate limit is exceeded |
| [NS-6001](#ns-6001-delivery-rejected) | `DELIVERY_REJECTED` | n/a (on the message) | no | The provider rejected the message |
| [NS-6002](#ns-6002-template-variable-missing) | `TEMPLATE_VARIABLE_MISSING` | n/a (on the message) | no | A template variable has no value |
| [NS-6003](#ns-6003-message-content-missing) | `MESSAGE_CONTENT_MISSING` | n/a (on the message) | no | The message has no content to send |
| [NS-6004](#ns-6004-delivery-attempts-exhausted) | `DELIVERY_ATTEMPTS_EXHAUSTED` | n/a (on the message) | yes | Every delivery attempt failed |
| [NS-6005](#ns-6005-delivery-dead-lettered) | `DELIVERY_DEAD_LETTERED` | n/a (on the message) | yes | The message could not be handed to a worker |
| [NS-6006](#ns-6006-provider-temporarily-failing) | `PROVIDER_TEMPORARILY_FAILING` | n/a (on the message) | yes | The provider is failing; delivery will be retried |
| [NS-6007](#ns-6007-otp-expired) | `OTP_EXPIRED` | n/a (on the message) | no | The one-time password expired before it could be sent |
| [NS-8001](#ns-8001-email-not-available) | `EMAIL_NOT_AVAILABLE` | 503 | yes | E-mail is not available |
| [NS-9001](#ns-9001-internal-error) | `INTERNAL_ERROR` | 500 | yes | Something went wrong on our side |

## Request (NS-1xxx)

### NS-1000 INVALID_REQUEST

**The request is invalid** · HTTP 400 · not retryable

- **Cause:** A required field is missing, a value is out of range or badly formatted, or the body is not valid JSON.
- **Resolution:** Correct the request using the message, which names the field, and send it again.

### NS-1001 PAYLOAD_TOO_LARGE

**The upload is too large** · HTTP 413 · not retryable

- **Cause:** The uploaded file or request body exceeds the size the service accepts.
- **Resolution:** Split the recipients over several bulk requests, or send a smaller file.

### NS-1002 METHOD_NOT_ALLOWED

**The HTTP method is not supported here** · HTTP 405 · not retryable

- **Cause:** The path exists but does not accept this HTTP method.
- **Resolution:** Use the method documented for the endpoint in the OpenAPI description.

### NS-1003 UNSUPPORTED_MEDIA_TYPE

**The content type is not supported** · HTTP 415 · not retryable

- **Cause:** The Content-Type header names a format this endpoint does not read.
- **Resolution:** Send application/json, or multipart/form-data for CSV uploads.

### NS-1004 NOT_ACCEPTABLE

**The requested response format is not available** · HTTP 406 · not retryable

- **Cause:** The Accept header asks for a format this endpoint cannot produce.
- **Resolution:** Accept application/json, or text/csv for exports.

### NS-1005 CATEGORY_NOT_ALLOWED

**The message category is not allowed here** · HTTP 400 · not retryable

- **Cause:** One-time passwords are sent one at a time, so a bulk request cannot have the OTP category.
- **Resolution:** Send each one-time password as a single request with category OTP.

## Access (NS-2xxx)

### NS-2001 UNAUTHORIZED

**The API key is missing or not valid** · HTTP 401 · not retryable

- **Cause:** The X-API-Key header is absent, or the key is unknown, rotated or belongs to a disabled client.
- **Resolution:** Send a current API key in X-API-Key; ask an administrator to rotate or re-enable it if needed.

### NS-2002 INVALID_CREDENTIALS

**The username or password is wrong** · HTTP 401 · not retryable

- **Cause:** The administrator sign-in did not match an active account.
- **Resolution:** Check the username and password; repeated failures lock the account for a while.

### NS-2003 OTP_REQUIRED

**A verification code is required** · HTTP 401 · not retryable

- **Cause:** The administrator account has two-factor authentication and no valid code was sent.
- **Resolution:** Send the current code from the authenticator app with the password.

### NS-2004 ACCOUNT_LOCKED

**The account is temporarily locked** · HTTP 429 · retryable

- **Cause:** Too many failed sign-ins in a short time locked the administrator account.
- **Resolution:** Wait for the lockout to end, or ask another administrator to unlock the account.

### NS-2005 REAUTHENTICATION_FAILED

**The sensitive action was not confirmed** · HTTP 403 · not retryable

- **Cause:** The current password or verification code needed to confirm this action was wrong.
- **Resolution:** Re-enter the current password and code, then repeat the action.

### NS-2006 ACCOUNT_SETUP_REQUIRED

**Account setup is not finished** · HTTP 403 · not retryable

- **Cause:** The administrator must change the initial password and enable two-factor authentication first.
- **Resolution:** Open My account in the admin console and complete the steps it lists.

### NS-2007 FORBIDDEN

**The action is not allowed for this role** · HTTP 403 · not retryable

- **Cause:** The signed-in administrator's role does not include this action.
- **Resolution:** Ask an administrator with the ADMIN role to perform it or to change your role.

### NS-2008 CHANNEL_NOT_ALLOWED

**The channel is not enabled for this client** · HTTP 403 · not retryable

- **Cause:** The client may only send on the channels an administrator enabled for it.
- **Resolution:** Use an enabled channel (GET /v1/account lists them), or ask an administrator to enable this one.

### NS-2009 TEMPLATE_READ_ONLY

**The template is read-only** · HTTP 403 · not retryable

- **Cause:** Shared templates are managed by administrators and cannot be changed by a client.
- **Resolution:** Copy it into a template of your own under a new name, or ask an administrator to change it.

### NS-2010 SIGN_IN_REQUIRED

**Sign-in is required** · HTTP 401 · not retryable

- **Cause:** The admin API call carried no session token, or the session expired or was signed out.
- **Resolution:** Sign in again in the admin console, or send a current bearer token.

## Resource (NS-3xxx)

### NS-3001 NOT_FOUND

**The resource does not exist** · HTTP 404 · not retryable

- **Cause:** No resource with this identifier exists, or it belongs to another client.
- **Resolution:** Check the identifier; resources are only visible to the client that created them.

### NS-3002 INVALID_STATE

**The resource's state does not allow this action** · HTTP 409 · not retryable

- **Cause:** The resource changed or is in a state where this action does not apply.
- **Resolution:** Read the resource again, then decide whether the action still makes sense.

### NS-3003 TEMPLATE_EXISTS

**A template with this name already exists** · HTTP 409 · not retryable

- **Cause:** Template names are unique per client and channel.
- **Resolution:** Choose another name, or update the existing template instead.

### NS-3004 TEMPLATE_NAME_RESERVED

**The template name is reserved** · HTTP 409 · not retryable

- **Cause:** A shared template already uses this name, so a client template may not shadow it.
- **Resolution:** Choose another name.

### NS-3005 TEMPLATE_LIMIT

**The template limit is reached** · HTTP 409 · not retryable

- **Cause:** The client already has the maximum number of templates.
- **Resolution:** Delete templates that are no longer used, or ask an administrator to raise the limit.

### NS-3006 SENDER_EXISTS

**The sender address is already registered** · HTTP 409 · not retryable

- **Cause:** This e-mail sender address is already registered for the client.
- **Resolution:** Use the existing sender; resend its verification if it is not verified yet.

### NS-3007 SENDER_NOT_VERIFIED

**The sender address is not verified** · HTTP 422 · not retryable

- **Cause:** E-mail can only be sent from an address whose owner confirmed the verification link.
- **Resolution:** Open the verification e-mail and confirm, or send without 'from' to use the default sender.

### NS-3008 PROVIDER_DESTINATION_REFUSED

**The provider destination is not allowed** · HTTP 400 · not retryable

- **Cause:** The provider URL points at a private, loopback or link-local address, or is not HTTPS (ADR-022).
- **Resolution:** Use the provider's public HTTPS endpoint, or add an internal gateway to the trusted hosts list.

## Billing (NS-4xxx)

### NS-4001 INSUFFICIENT_CREDIT

**There is not enough prepaid credit** · HTTP 402 · not retryable

- **Cause:** The client's prepaid balance does not cover the messages in this request.
- **Resolution:** Top up the balance (or ask the account owner to), then send again.

### NS-4002 SPEND_CAP_EXCEEDED

**The monthly spend cap is reached** · HTTP 402 · not retryable

- **Cause:** This request would take the client over the spending cap set on its billing account.
- **Resolution:** Wait for the next billing period, or ask an administrator to raise the cap.

### NS-4003 ACCOUNT_SUSPENDED

**The billing account is suspended** · HTTP 403 · not retryable

- **Cause:** The client's billing account is suspended, so no messages are accepted.
- **Resolution:** Contact the platform's billing administrator to settle and reactivate the account.

## Capacity (NS-5xxx)

### NS-5001 RATE_LIMITED

**The rate limit is exceeded** · HTTP 429 · retryable

- **Cause:** The client sent more requests than its rate limit allows in the current window.
- **Resolution:** Wait the number of seconds in the Retry-After header, then retry; spread sends over time.

## Delivery (NS-6xxx)

### NS-6001 DELIVERY_REJECTED

**The provider rejected the message** · HTTP n/a (on the message) · not retryable

- **Cause:** The SMS, e-mail or messaging provider refused this message, typically an invalid or blocked recipient.
- **Resolution:** Check the recipient; the message's lastError carries the provider's reason. Do not resend unchanged.

### NS-6002 TEMPLATE_VARIABLE_MISSING

**A template variable has no value** · HTTP n/a (on the message) · not retryable

- **Cause:** The template uses a {{variable}} that the request did not supply for this recipient.
- **Resolution:** Send the request again with every variable the template lists (GET /v1/templates).

### NS-6003 MESSAGE_CONTENT_MISSING

**The message has no content to send** · HTTP n/a (on the message) · not retryable

- **Cause:** The request this message belongs to has no body, typically because its personal data was erased.
- **Resolution:** Send a new request; erased content cannot be recovered.

### NS-6004 DELIVERY_ATTEMPTS_EXHAUSTED

**Every delivery attempt failed** · HTTP n/a (on the message) · retryable

- **Cause:** All retries failed for temporary reasons such as provider outages or timeouts.
- **Resolution:** Retry the message later; operators can reprocess it from the dead-letter queue once providers recover.

### NS-6005 DELIVERY_DEAD_LETTERED

**The message could not be handed to a worker** · HTTP n/a (on the message) · retryable

- **Cause:** The message broker gave up delivering this message to the dispatcher after repeated failures.
- **Resolution:** Operators reprocess it from the dead-letter queue; the platform team checks the dispatcher logs.

### NS-6006 PROVIDER_TEMPORARILY_FAILING

**The provider is failing; delivery will be retried** · HTTP n/a (on the message) · retryable

- **Cause:** The last attempt failed for a temporary reason; the message is RETRYING with back-off.
- **Resolution:** No action needed; the message is retried automatically until it is sent or attempts run out.

### NS-6007 OTP_EXPIRED

**The one-time password expired before it could be sent** · HTTP n/a (on the message) · not retryable

- **Cause:** The OTP could not reach a provider within its validity, so it was dropped rather than sent late.
- **Resolution:** Ask the user to request a new code; check the provider and backlog alerts if this happens often.

## Dependency (NS-8xxx)

### NS-8001 EMAIL_NOT_AVAILABLE

**E-mail is not available** · HTTP 503 · retryable

- **Cause:** The e-mail service needed for this action (for example sender verification) is not configured or up.
- **Resolution:** Retry later; if it persists, the platform team checks the e-mail provider configuration.

## Platform (NS-9xxx)

### NS-9001 INTERNAL_ERROR

**Something went wrong on our side** · HTTP 500 · retryable

- **Cause:** An unexpected fault occurred while handling the request.
- **Resolution:** Retry once; if it happens again, report the traceId in the response to the platform team.
