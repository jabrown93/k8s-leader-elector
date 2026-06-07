# leader-elector

**Under development, bugs are likely and I will be slow to address them for the foreseeable future**

Tiny Kubernetes sidecar binary that:

- Acquires leadership using a Redis distributed lock (Spring Integration `RedisLockRegistry`)
- On gaining leadership, patches its own Pod with a label (default: `dns.jb.io/leader=true`)
- On losing leadership, removes the label

## Configuration

Bind via environment variables (Spring relaxed binding, e.g. `elector.labelKey` →
`ELECTOR_LABEL_KEY`).

| Variable | Default | Description |
|----------|---------|-------------|
| `ELECTOR_LABEL_KEY` | — | Label set to `true`/`false` to mark the leader |
| `ELECTOR_LOCK_NAME` | — | Redis lock name |
| `ELECTOR_SELECTOR_LABEL_KEY` / `ELECTOR_SELECTOR_LABEL_VALUE` | — | Selects the pods to label |
| `ELECTOR_LEASE_DURATION` | `120s` | Lock lease TTL |
| `ELECTOR_RENEW_DEADLINE` | `60s` | Lock renew interval |
| `ELECTOR_RETRY_PERIOD` | `5s` | Acquire retry / `tryLock` wait |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host backing the lock |
| `POD_NAME` | — | This pod's name (downward API) |

### Optional health-gated leadership

When enabled, a pod must be **healthy** to be eligible to acquire — and to keep — leadership.
The elector stays generic and tool-free: the application writes its own notion of "fit to lead"
to a status file (typically on a shared `emptyDir`) and refreshes it; the elector only reads it.

- **Eligibility:** an unhealthy pod will not take the lock, so a degraded pod never becomes leader.
- **Liveness:** a leader that goes unhealthy relinquishes the lock after
  `ELECTOR_HEALTH_PROBE_FAILURE_THRESHOLD` consecutive failures, so a healthy peer can take over.
- **Deadlock escape hatch:** if the lock is free but *no* pod is healthy (fresh install, total
  outage), after `ELECTOR_HEALTH_PROBE_DEADLOCK_GRACE` a pod acquires it anyway and leads in a
  logged "degraded" state, rather than leaving the system leaderless forever.

| Variable | Default | Description |
|----------|---------|-------------|
| `ELECTOR_HEALTH_PROBE_ENABLED` | `false` | Master switch; off ⇒ behaves exactly as before |
| `ELECTOR_HEALTH_PROBE_FILE_PATH` | — | Status file to read |
| `ELECTOR_HEALTH_PROBE_HEALTHY_CONTENT` | `healthy` | Trimmed file content that means healthy |
| `ELECTOR_HEALTH_PROBE_MAX_AGE` | `2m` | Reject the file if not updated within this window (`0` disables) |
| `ELECTOR_HEALTH_PROBE_FAILURE_THRESHOLD` | `3` | Consecutive failures tolerated while leading |
| `ELECTOR_HEALTH_PROBE_DEADLOCK_GRACE` | `5m` | How long to wait before leading degraded when no pod is healthy |


