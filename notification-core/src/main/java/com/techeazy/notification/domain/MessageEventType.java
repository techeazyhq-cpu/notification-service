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
package com.techeazy.notification.domain;

/**
 * What can happen to a message after it is accepted, as recorded in its event log (ADR-035). Acceptance itself is
 * the message's creation time and needs no event.
 */
public enum MessageEventType {
    /** A delivery attempt failed for a temporary reason; another is scheduled. */
    ATTEMPT_FAILED,
    /** The provider accepted the message. */
    SENT,
    /** The message will not be delivered; its error code says why. */
    FAILED,
    /** A one-time password ran out of validity before it could be sent. */
    EXPIRED,
    /** The broker gave up handing the message to a worker. */
    DEAD_LETTERED,
    /** The platform operator put a failed message back on the send path. */
    REQUEUED,
    /** The recipient's personal data was erased, by retention or on request. */
    DATA_ERASED
}
