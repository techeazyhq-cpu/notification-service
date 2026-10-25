# Drafting evaluation: OLLAMA llama3.2:3b

Run at 2026-10-25T11:39:56.962585900Z. 24 of 26 passed (92%), 85% at the first attempt; 86% of the listed variables used; 23966 tokens.

| Channel | Passed |
|---|---|
| EMAIL | 6 of 7 |
| SMS | 7 of 7 |
| WHATSAPP | 5 of 6 |
| PUSH | 6 of 6 |

| Brief | Channel | Category | Verdict | Calls | Tokens | Seconds | Unused variables | Problems |
|---|---|---|---|---|---|---|---|---|
| sms-otp-verify-phone | SMS | OTP | PASSED | 1 | 793 | 6.5 |  |  |
| sms-otp-confirm-payment | SMS | OTP | PASSED | 1 | 781 | 4.9 |  |  |
| sms-order-shipped | SMS | TRANSACTIONAL | PASSED | 1 | 800 | 6.6 | orderId |  |
| sms-appointment-reminder | SMS | TRANSACTIONAL | PASSED | 1 | 795 | 6.8 |  |  |
| sms-payment-failed | SMS | TRANSACTIONAL | PASSED | 1 | 814 | 8.8 |  |  |
| sms-promo-weekend-sale | SMS | PROMOTIONAL | PASSED | 1 | 810 | 7.1 | discount |  |
| sms-promo-new-store | SMS | PROMOTIONAL | PASSED | 1 | 821 | 7.4 |  |  |
| email-otp-verify-email | EMAIL | OTP | PASSED | 1 | 810 | 8.1 |  |  |
| email-otp-sign-in | EMAIL | OTP | PASSED | 1 | 869 | 13.4 | deviceName |  |
| email-welcome | EMAIL | TRANSACTIONAL | FELL_SHORT | 2 | 1831 | 24.5 |  | Does not mention: welcome |
| email-password-reset | EMAIL | TRANSACTIONAL | PASSED | 1 | 841 | 10.5 | firstName |  |
| email-invoice-ready | EMAIL | TRANSACTIONAL | PASSED | 1 | 876 | 13.0 |  |  |
| email-promo-loyalty | EMAIL | PROMOTIONAL | PASSED | 1 | 865 | 12.2 |  |  |
| email-promo-new-collection | EMAIL | PROMOTIONAL | PASSED | 1 | 863 | 11.2 | firstName |  |
| whatsapp-otp-verify | WHATSAPP | OTP | PASSED | 1 | 784 | 5.6 |  |  |
| whatsapp-otp-withdrawal | WHATSAPP | OTP | PASSED_AFTER_CORRECTION | 2 | 1731 | 17.4 | amount |  |
| whatsapp-order-delivered | WHATSAPP | TRANSACTIONAL | PASSED | 1 | 805 | 7.2 |  |  |
| whatsapp-booking-confirmed | WHATSAPP | TRANSACTIONAL | FELL_SHORT | 1 | 841 | 10.3 |  | Does not mention: booking |
| whatsapp-promo-flash-sale | WHATSAPP | PROMOTIONAL | PASSED | 1 | 809 | 6.8 | firstName |  |
| whatsapp-promo-referral | WHATSAPP | PROMOTIONAL | PASSED | 1 | 849 | 10.2 |  |  |
| push-otp-sign-in | PUSH | OTP | PASSED | 1 | 775 | 5.0 |  |  |
| push-order-out-for-delivery | PUSH | TRANSACTIONAL | PASSED | 1 | 790 | 6.3 |  |  |
| push-payment-received | PUSH | TRANSACTIONAL | PASSED_AFTER_CORRECTION | 2 | 1599 | 9.5 |  |  |
| push-ride-arriving | PUSH | TRANSACTIONAL | PASSED | 1 | 821 | 11.2 | carPlate |  |
| push-promo-cart | PUSH | PROMOTIONAL | PASSED | 1 | 802 | 7.5 |  |  |
| push-promo-price-drop | PUSH | PROMOTIONAL | PASSED | 1 | 791 | 5.6 |  |  |
