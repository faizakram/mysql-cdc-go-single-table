# Sink start-up latency (#130)

## Symptom

After a job starts, the source connector snapshots quickly, but the **sink** often took
**~5 minutes** to begin writing rows to the target — with no error, just a long quiet gap.

## Root cause

Two Kafka/Connect defaults, each **300000 ms (5 min)**, stacked on the cold-start path:

1. **`scheduled.rebalance.max.delay.ms`** (Connect worker, default `300000`).
   Connect uses *incremental cooperative rebalancing*. When a brand-new connector/task appears,
   the worker can defer assigning it for up to this delay, holding the slot open in case an
   "absent" worker returns to reclaim its previous load. In our single-worker data plane there is
   no worker to wait for, so the entire delay is dead time before the sink task is even assigned.

2. **`metadata.max.age.ms`** (sink consumer, default `300000`).
   Once running, the sink consumer only refreshes topic metadata every 5 min. The source topics
   are created moments earlier, so in the worst case the consumer doesn't "see" them until its
   next metadata refresh.

Together these explain the ~5-min observed latency (and its variability).

## Fix

Set both low on the Connect worker (`debezium/connect` maps `CONNECT_*` env vars → worker
properties), in `debezium-setup/docker-compose.dataplane.yml`:

```yaml
CONNECT_SCHEDULED_REBALANCE_MAX_DELAY_MS: "0"   # assign new tasks immediately
CONNECT_CONSUMER_METADATA_MAX_AGE_MS: "5000"    # discover new topics within ~5s
```

`scheduled.rebalance.max.delay.ms=0` is safe here because the data plane runs a single Connect
worker — there is no rebalance-thrash to dampen. In a multi-worker production cluster, prefer a
small non-zero value (e.g. `10000`) to avoid reassigning load during brief, routine worker blips.

## Expected timing after the fix

| Phase | Before | After |
|-------|--------|-------|
| Sink task assigned after deploy | up to ~5 min | ~immediate |
| Sink discovers new source topics | up to ~5 min | ≤ ~5 s |
| First rows written to target | ~5 min + snapshot | seconds + snapshot |

Sink writes should now begin within a few seconds of the source producing records, gated only by
snapshot throughput rather than rebalance/metadata timers.
