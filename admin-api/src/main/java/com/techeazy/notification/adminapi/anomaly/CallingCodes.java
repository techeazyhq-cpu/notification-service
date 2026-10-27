/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi.anomaly;

import java.util.Set;

/**
 * The ITU-T E.164 country calling codes, used to tell which country a phone number belongs to without keeping the
 * number. The codes are prefix-free: no code is the start of another, so the first one, two or three digits of a number
 * match at most one code. Shared codes such as +1 (the North American Numbering Plan) and +7 count as one destination.
 *
 * <p>International networks (+881, +882, +883) and other non-geographic codes are included on purpose: traffic to them
 * is a typical sign of SMS pumping (ADR-039).
 */
final class CallingCodes {

    /** The destination recorded for a number whose leading digits match no assigned code. */
    static final String UNKNOWN = "unknown";

    private static final int LONGEST = 3;

    private static final Set<String> CODES = Set.of(
            "1", "7",
            "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49",
            "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63", "64", "65", "66", "81", "82", "84",
            "86", "90", "91", "92", "93", "94", "95", "98",
            "211", "212", "213", "216", "218",
            "220", "221", "222", "223", "224", "225", "226", "227", "228", "229",
            "230", "231", "232", "233", "234", "235", "236", "237", "238", "239",
            "240", "241", "242", "243", "244", "245", "246", "247", "248", "249",
            "250", "251", "252", "253", "254", "255", "256", "257", "258",
            "260", "261", "262", "263", "264", "265", "266", "267", "268", "269",
            "290", "291", "297", "298", "299",
            "350", "351", "352", "353", "354", "355", "356", "357", "358", "359",
            "370", "371", "372", "373", "374", "375", "376", "377", "378", "379",
            "380", "381", "382", "383", "385", "386", "387", "389",
            "420", "421", "423",
            "500", "501", "502", "503", "504", "505", "506", "507", "508", "509",
            "590", "591", "592", "593", "594", "595", "596", "597", "598", "599",
            "670", "672", "673", "674", "675", "676", "677", "678", "679",
            "680", "681", "682", "683", "685", "686", "687", "688", "689",
            "690", "691", "692",
            "800", "808",
            "850", "852", "853", "855", "856",
            "870", "878",
            "880", "881", "882", "883", "886", "888",
            "960", "961", "962", "963", "964", "965", "966", "967", "968",
            "970", "971", "972", "973", "974", "975", "976", "977", "979",
            "992", "993", "994", "995", "996", "998");

    private CallingCodes() {
    }

    /**
     * The calling code a number starts with.
     *
     * @param digits the leading digits of an E.164 number, without the {@code +}; three are enough
     * @return the matching code, or {@link #UNKNOWN}
     */
    static String of(String digits) {
        for (int length = 1; length <= Math.min(LONGEST, digits.length()); length++) {
            String candidate = digits.substring(0, length);
            if (CODES.contains(candidate)) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    static Set<String> all() {
        return CODES;
    }
}
