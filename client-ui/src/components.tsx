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
