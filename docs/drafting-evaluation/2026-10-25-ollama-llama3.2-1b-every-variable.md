# Drafting evaluation: OLLAMA llama3.2:1b

Run at 2026-10-25T11:46:10.435861400Z. 10 of 26 passed (38%), 12% at the first attempt; 96% of the listed variables used; 43616 tokens.

| Channel | Passed |
|---|---|
| EMAIL | 3 of 7 |
| SMS | 3 of 7 |
| WHATSAPP | 2 of 6 |
| PUSH | 2 of 6 |

| Brief | Channel | Category | Verdict | Calls | Tokens | Seconds | Unused variables | Problems |
|---|---|---|---|---|---|---|---|---|
| sms-otp-verify-phone | SMS | OTP | REJECTED | 2 | 1661 | 23.1 |  | Uses placeholders that were not asked for: trackingUrl, cancellationLink; SMS messages have no subject |
| sms-otp-confirm-payment | SMS | OTP | REJECTED | 2 | 1797 | 13.7 |  | A placeholder is malformed: use {{name}} with matching braces; SMS messages have no subject |
| sms-order-shipped | SMS | TRANSACTIONAL | PASSED_AFTER_CORRECTION | 2 | 1717 | 8.5 |  |  |
| sms-appointment-reminder | SMS | TRANSACTIONAL | REJECTED | 2 | 1723 | 10.8 |  | Uses placeholders that were not asked for: yes-no, link, greeting; SMS messages have no subject; SMS messages are plain text: remove the HTML tags |
| sms-payment-failed | SMS | TRANSACTIONAL | REJECTED | 2 | 1914 | 17.3 |  | Uses placeholders that were not asked for: lastName, payDate, updateAction, cardDetail, contactInfo, supportMessage; SMS messages have no subject |
| sms-promo-weekend-sale | SMS | PROMOTIONAL | PASSED_AFTER_CORRECTION | 2 | 1832 | 13.6 |  |  |
| sms-promo-new-store | SMS | PROMOTIONAL | PASSED_AFTER_CORRECTION | 2 | 1885 | 15.2 | openingDate |  |
| email-otp-verify-email | EMAIL | OTP | REJECTED | 2 | 2023 | 22.6 |  | A link must point to a placeholder, such as href="{{trackingUrl}}", not: http://cinambarg.com/verify/{{code}} |
| email-otp-sign-in | EMAIL | OTP | REJECTED | 2 | 1952 | 17.4 |  | Uses placeholders that were not asked for: trackingUrl, company |
| email-welcome | EMAIL | TRANSACTIONAL | PASSED | 1 | 824 | 4.7 |  |  |
| email-password-reset | EMAIL | TRANSACTIONAL | REJECTED | 2 | 1985 | 24.5 |  | A placeholder is malformed: use {{name}} with matching braces |
| email-invoice-ready | EMAIL | TRANSACTIONAL | PASSED_AFTER_CORRECTION | 2 | 1769 | 11.4 |  |  |
| email-promo-loyalty | EMAIL | PROMOTIONAL | FELL_SHORT | 1 | 899 | 8.8 |  | Does not mention: thank |
| email-promo-new-collection | EMAIL | PROMOTIONAL | PASSED_AFTER_CORRECTION | 2 | 1925 | 17.4 |  |  |
| whatsapp-otp-verify | WHATSAPP | OTP | PASSED_AFTER_CORRECTION | 2 | 1692 | 10.0 |  |  |
| whatsapp-otp-withdrawal | WHATSAPP | OTP | REJECTED | 2 | 1785 | 12.9 |  | Uses placeholders that were not asked for: message, emailLink; WhatsApp messages have no subject |
| whatsapp-order-delivered | WHATSAPP | TRANSACTIONAL | REJECTED | 2 | 1885 | 17.4 |  | Uses placeholders that were not asked for: phoneNumber; WhatsApp messages have no subject |
| whatsapp-booking-confirmed | WHATSAPP | TRANSACTIONAL | REJECTED | 2 | 1791 | 13.5 |  | A placeholder is malformed: use {{name}} with matching braces; Uses placeholders that were not asked for: phoneNumber; WhatsApp messages have no subject |
| whatsapp-promo-flash-sale | WHATSAPP | PROMOTIONAL | REJECTED | 2 | 1711 | 8.8 |  | WhatsApp messages are plain text: remove the HTML tags |
| whatsapp-promo-referral | WHATSAPP | PROMOTIONAL | PASSED | 1 | 818 | 4.1 |  |  |
| push-otp-sign-in | PUSH | OTP | REJECTED | 2 | 1787 | 13.3 |  | Uses placeholders that were not asked for: loginLink; Contains a stand-in to fill in by hand; use a placeholder or write around it: [ ] |
| push-order-out-for-delivery | PUSH | TRANSACTIONAL | PASSED_AFTER_CORRECTION | 2 | 1802 | 13.5 |  |  |
| push-payment-received | PUSH | TRANSACTIONAL | REJECTED | 2 | 2121 | 32.9 |  | Uses placeholders that were not asked for: accountType, processingTime, supportEmail; Push messages are plain text: remove the HTML tags; The text is longer than 240 characters |
| push-ride-arriving | PUSH | TRANSACTIONAL | REJECTED | 2 | 1811 | 13.4 |  | The text is longer than 240 characters |
| push-promo-cart | PUSH | PROMOTIONAL | PASSED | 1 | 790 | 3.4 |  |  |
| push-promo-price-drop | PUSH | PROMOTIONAL | REJECTED | 2 | 1717 | 9.0 |  | The reply was not a readable draft |
