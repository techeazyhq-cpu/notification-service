# Drafting evaluation: OLLAMA llama3.2:1b

Run at 2026-10-25T11:21:05.383213400Z. 9 of 26 passed (35%), 23% at the first attempt; 58% of the listed variables used; 38410 tokens.

| Channel | Passed |
|---|---|
| EMAIL | 2 of 7 |
| SMS | 4 of 7 |
| WHATSAPP | 1 of 6 |
| PUSH | 2 of 6 |

| Brief | Channel | Category | Verdict | Calls | Tokens | Seconds | Unused variables | Problems |
|---|---|---|---|---|---|---|---|---|
| sms-otp-verify-phone | SMS | OTP | PASSED | 1 | 755 | 3.5 |  |  |
| sms-otp-confirm-payment | SMS | OTP | REJECTED | 2 | 1737 | 12.9 |  | SMS messages are plain text: remove the HTML tags |
| sms-order-shipped | SMS | TRANSACTIONAL | REJECTED | 2 | 1679 | 10.7 |  | Uses placeholders that were not asked for: address; Contains a stand-in to fill in by hand; use a placeholder or write around it: [Your Name]; SMS messages have no subject |
| sms-appointment-reminder | SMS | TRANSACTIONAL | REJECTED | 2 | 1654 | 9.1 |  | SMS messages are plain text: remove the HTML tags |
| sms-payment-failed | SMS | TRANSACTIONAL | PASSED_AFTER_CORRECTION | 2 | 1695 | 10.8 | firstName |  |
| sms-promo-weekend-sale | SMS | PROMOTIONAL | PASSED_AFTER_CORRECTION | 2 | 1611 | 7.3 | promoCode |  |
| sms-promo-new-store | SMS | PROMOTIONAL | PASSED_AFTER_CORRECTION | 2 | 1712 | 10.7 |  |  |
| email-otp-verify-email | EMAIL | OTP | REJECTED | 2 | 1682 | 11.4 |  | Uses placeholders that were not asked for: email |
| email-otp-sign-in | EMAIL | OTP | REJECTED | 2 | 1802 | 14.9 |  | Uses placeholders that were not asked for: supportEmail |
| email-welcome | EMAIL | TRANSACTIONAL | REJECTED | 2 | 1862 | 18.7 |  | Uses placeholders that were not asked for: phoneNumber |
| email-password-reset | EMAIL | TRANSACTIONAL | FELL_SHORT | 2 | 1898 | 19.3 | firstName, resetUrl | Does not mention: {{resetUrl}} |
| email-invoice-ready | EMAIL | TRANSACTIONAL | REJECTED | 2 | 1875 | 18.5 |  | Uses placeholders that were not asked for: contactInfo |
| email-promo-loyalty | EMAIL | PROMOTIONAL | PASSED | 1 | 851 | 8.0 | firstName, promoCode |  |
| email-promo-new-collection | EMAIL | PROMOTIONAL | PASSED | 1 | 765 | 3.0 | firstName, shopUrl, unsubscribeUrl |  |
| whatsapp-otp-verify | WHATSAPP | OTP | REJECTED | 2 | 1737 | 13.3 |  | Uses placeholders that were not asked for: number, optOutCode; WhatsApp messages have no subject |
| whatsapp-otp-withdrawal | WHATSAPP | OTP | REJECTED | 2 | 1696 | 11.2 |  | WhatsApp messages have no subject |
| whatsapp-order-delivered | WHATSAPP | TRANSACTIONAL | REJECTED | 2 | 1580 | 6.9 |  | WhatsApp messages have no subject |
| whatsapp-booking-confirmed | WHATSAPP | TRANSACTIONAL | PASSED | 1 | 771 | 3.7 | firstName |  |
| whatsapp-promo-flash-sale | WHATSAPP | PROMOTIONAL | REJECTED | 2 | 1645 | 9.2 |  | Uses placeholders that were not asked for: timestamp, optOutUrl; WhatsApp messages have no subject; WhatsApp messages are plain text: remove the HTML tags |
| whatsapp-promo-referral | WHATSAPP | PROMOTIONAL | REJECTED | 2 | 1852 | 16.5 |  | Uses placeholders that were not asked for: shopName; WhatsApp messages have no subject; WhatsApp messages are plain text: remove the HTML tags |
| push-otp-sign-in | PUSH | OTP | PASSED | 1 | 789 | 5.4 |  |  |
| push-order-out-for-delivery | PUSH | TRANSACTIONAL | REJECTED | 2 | 1739 | 13.4 |  | The text is longer than 240 characters |
| push-payment-received | PUSH | TRANSACTIONAL | REJECTED | 2 | 1757 | 15.8 |  | Uses placeholders that were not asked for: trackingUrl, companyName; The text is longer than 240 characters |
| push-ride-arriving | PUSH | TRANSACTIONAL | FELL_SHORT | 1 | 785 | 4.4 | driverName | Does not mention: driver |
| push-promo-cart | PUSH | PROMOTIONAL | PASSED | 1 | 772 | 3.9 |  |  |
| push-promo-price-drop | PUSH | PROMOTIONAL | REJECTED | 2 | 1709 | 11.6 |  | Uses placeholders that were not asked for: product_name; The text is longer than 240 characters |
