# Drafting evaluation: OLLAMA llama3.2:3b

Run at 2026-10-25T11:35:42.661862700Z. 24 of 26 passed (92%), 77% at the first attempt; 93% of the listed variables used; 25718 tokens.

| Channel | Passed |
|---|---|
| EMAIL | 6 of 7 |
| SMS | 6 of 7 |
| WHATSAPP | 6 of 6 |
| PUSH | 6 of 6 |

| Brief | Channel | Category | Verdict | Calls | Tokens | Seconds | Unused variables | Problems |
|---|---|---|---|---|---|---|---|---|
| sms-otp-verify-phone | SMS | OTP | PASSED | 1 | 780 | 29.7 |  |  |
| sms-otp-confirm-payment | SMS | OTP | PASSED | 1 | 783 | 5.2 |  |  |
| sms-order-shipped | SMS | TRANSACTIONAL | PASSED | 1 | 785 | 5.2 | orderId |  |
| sms-appointment-reminder | SMS | TRANSACTIONAL | FELL_SHORT | 1 | 774 | 4.2 | firstName | Does not mention: appointment |
| sms-payment-failed | SMS | TRANSACTIONAL | PASSED | 1 | 785 | 5.3 |  |  |
| sms-promo-weekend-sale | SMS | PROMOTIONAL | PASSED | 1 | 807 | 7.0 |  |  |
| sms-promo-new-store | SMS | PROMOTIONAL | PASSED | 1 | 815 | 7.3 |  |  |
| email-otp-verify-email | EMAIL | OTP | PASSED_AFTER_CORRECTION | 2 | 1734 | 18.2 |  |  |
| email-otp-sign-in | EMAIL | OTP | REJECTED | 2 | 1852 | 25.0 |  | A placeholder is malformed: use {{name}} with matching braces |
| email-welcome | EMAIL | TRANSACTIONAL | PASSED | 1 | 859 | 13.1 |  |  |
| email-password-reset | EMAIL | TRANSACTIONAL | PASSED | 1 | 849 | 14.5 |  |  |
| email-invoice-ready | EMAIL | TRANSACTIONAL | PASSED | 1 | 851 | 10.4 |  |  |
| email-promo-loyalty | EMAIL | PROMOTIONAL | PASSED | 1 | 898 | 15.4 |  |  |
| email-promo-new-collection | EMAIL | PROMOTIONAL | PASSED_AFTER_CORRECTION | 2 | 1885 | 26.3 | firstName |  |
| whatsapp-otp-verify | WHATSAPP | OTP | PASSED | 1 | 776 | 5.2 |  |  |
| whatsapp-otp-withdrawal | WHATSAPP | OTP | PASSED | 1 | 805 | 7.4 | amount |  |
| whatsapp-order-delivered | WHATSAPP | TRANSACTIONAL | PASSED | 1 | 813 | 7.8 |  |  |
| whatsapp-booking-confirmed | WHATSAPP | TRANSACTIONAL | PASSED | 1 | 802 | 6.3 |  |  |
| whatsapp-promo-flash-sale | WHATSAPP | PROMOTIONAL | PASSED | 1 | 800 | 6.1 |  |  |
| whatsapp-promo-referral | WHATSAPP | PROMOTIONAL | PASSED | 1 | 851 | 10.3 |  |  |
| push-otp-sign-in | PUSH | OTP | PASSED_AFTER_CORRECTION | 2 | 1591 | 9.4 |  |  |
| push-order-out-for-delivery | PUSH | TRANSACTIONAL | PASSED | 1 | 783 | 5.6 |  |  |
| push-payment-received | PUSH | TRANSACTIONAL | PASSED | 1 | 788 | 6.3 |  |  |
| push-ride-arriving | PUSH | TRANSACTIONAL | PASSED | 1 | 808 | 7.3 |  |  |
| push-promo-cart | PUSH | PROMOTIONAL | PASSED_AFTER_CORRECTION | 2 | 1642 | 12.1 |  |  |
| push-promo-price-drop | PUSH | PROMOTIONAL | PASSED | 1 | 802 | 7.0 |  |  |
