# Drafting evaluation: OLLAMA llama3.2:3b

Run at 2026-10-25T11:16:20.066078700Z. 24 of 26 passed (92%), 85% at the first attempt; 74% of the listed variables used; 21683 tokens.

| Channel | Passed |
|---|---|
| EMAIL | 5 of 7 |
| SMS | 7 of 7 |
| WHATSAPP | 6 of 6 |
| PUSH | 6 of 6 |

| Brief | Channel | Category | Verdict | Calls | Tokens | Seconds | Unused variables | Problems |
|---|---|---|---|---|---|---|---|---|
| sms-otp-verify-phone | SMS | OTP | PASSED | 1 | 756 | 30.3 |  |  |
| sms-otp-confirm-payment | SMS | OTP | PASSED | 1 | 758 | 6.0 |  |  |
| sms-order-shipped | SMS | TRANSACTIONAL | PASSED | 1 | 747 | 4.5 | firstName, orderId |  |
| sms-appointment-reminder | SMS | TRANSACTIONAL | PASSED | 1 | 751 | 4.9 | firstName |  |
| sms-payment-failed | SMS | TRANSACTIONAL | PASSED | 1 | 748 | 4.6 | firstName |  |
| sms-promo-weekend-sale | SMS | PROMOTIONAL | PASSED | 1 | 776 | 6.9 | promoCode |  |
| sms-promo-new-store | SMS | PROMOTIONAL | PASSED | 1 | 803 | 9.1 |  |  |
| email-otp-verify-email | EMAIL | OTP | PASSED | 1 | 765 | 7.4 | firstName |  |
| email-otp-sign-in | EMAIL | OTP | FELL_SHORT | 1 | 785 | 7.8 | deviceName | Does not mention: sign |
| email-welcome | EMAIL | TRANSACTIONAL | FELL_SHORT | 1 | 787 | 8.5 |  | Does not mention: welcome |
| email-password-reset | EMAIL | TRANSACTIONAL | PASSED | 1 | 787 | 8.6 |  |  |
| email-invoice-ready | EMAIL | TRANSACTIONAL | PASSED | 1 | 785 | 7.3 | invoiceNumber |  |
| email-promo-loyalty | EMAIL | PROMOTIONAL | PASSED | 1 | 843 | 13.2 | firstName |  |
| email-promo-new-collection | EMAIL | PROMOTIONAL | PASSED | 1 | 838 | 13.3 | firstName |  |
| whatsapp-otp-verify | WHATSAPP | OTP | PASSED | 1 | 750 | 5.4 |  |  |
| whatsapp-otp-withdrawal | WHATSAPP | OTP | PASSED | 1 | 780 | 7.1 |  |  |
| whatsapp-order-delivered | WHATSAPP | TRANSACTIONAL | PASSED | 1 | 782 | 8.4 | firstName |  |
| whatsapp-booking-confirmed | WHATSAPP | TRANSACTIONAL | PASSED | 1 | 762 | 7.7 | firstName |  |
| whatsapp-promo-flash-sale | WHATSAPP | PROMOTIONAL | PASSED | 1 | 754 | 5.6 | firstName |  |
| whatsapp-promo-referral | WHATSAPP | PROMOTIONAL | PASSED | 1 | 775 | 5.9 | referralCode |  |
| push-otp-sign-in | PUSH | OTP | PASSED_AFTER_CORRECTION | 2 | 1551 | 10.6 |  |  |
| push-order-out-for-delivery | PUSH | TRANSACTIONAL | PASSED_AFTER_CORRECTION | 2 | 1558 | 11.6 |  |  |
| push-payment-received | PUSH | TRANSACTIONAL | PASSED | 1 | 750 | 5.7 |  |  |
| push-ride-arriving | PUSH | TRANSACTIONAL | PASSED | 1 | 768 | 6.1 | minutes |  |
| push-promo-cart | PUSH | PROMOTIONAL | PASSED | 1 | 764 | 6.3 |  |  |
| push-promo-price-drop | PUSH | PROMOTIONAL | PASSED | 1 | 760 | 5.7 |  |  |
