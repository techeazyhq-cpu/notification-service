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

import { inFlight, StatusCounts } from './api';

export function StatusBadge({ status }: Readonly<{ status: string }>) {
  return <span className={`badge ${status}`}>{status.replaceAll('_', ' ')}</span>;
}

/** Sent / failed / still-in-flight share of a request, with a text alternative for screen readers. */
export function ProgressBar({ counts, total }: Readonly<{ counts: StatusCounts; total: number }>) {
  const pct = (n: number) => (total > 0 ? (n / total) * 100 : 0);
  const flight = inFlight(counts);
  const label = `${counts.sent} sent, ${counts.failed} failed, ${flight} in progress of ${total}`;
  return (
    <div>
      <div className="bar" role="img" aria-label={label}>
        <i className="sent" style={{ width: `${pct(counts.sent)}%` }} />
        <i className="failed" style={{ width: `${pct(counts.failed)}%` }} />
        <i className="flight" style={{ width: `${pct(flight)}%` }} />
      </div>
      <div className="bar-label">{counts.sent + counts.failed} / {total} done</div>
    </div>
  );
}

export const shortId = (id: string) => id.slice(0, 8);
export const formatTime = (iso: string) => new Date(iso).toLocaleString();
