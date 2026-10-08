/** Small per-process fixed-window limiter. It stores HMAC keys, never email addresses or credentials. */

import { createHmac } from "node:crypto";
import { AccountApiError, ErrorCode } from "./errors.js";

export class InMemoryRateLimiter {
  #buckets = new Map();
  #lastCleanup = 0;
  #nextCapacityCheck = 0;

  constructor({ authSecret, now = Date.now, maximumBuckets = 50_000 }) {
    this.authSecret = authSecret;
    this.now = now;
    if (!Number.isInteger(maximumBuckets) || maximumBuckets < 1 || maximumBuckets > 1_000_000) {
      throw new RangeError("rate limiter bucket capacity is out of range");
    }
    this.maximumBuckets = maximumBuckets;
  }

  consume(operation, subjectParts, { maximum, windowMs }) {
    const subject = subjectParts.map((part) => String(part ?? "").trim().toLowerCase()).join("\0");
    const key = createHmac("sha256", this.authSecret)
      .update(operation, "utf8")
      .update("\0", "utf8")
      .update(subject, "utf8")
      .digest("hex");
    const now = this.now();
    let bucket = this.#buckets.get(key);
    if (!bucket || now - bucket.startedAt >= bucket.windowMs) {
      if (!bucket && this.#buckets.size >= this.maximumBuckets) {
        if (now >= this.#nextCapacityCheck) this.#cleanup(now, true);
        bucket = this.#buckets.get(key);
        if (!bucket && this.#buckets.size >= this.maximumBuckets) {
          const earliestExpiry = Math.min(...[...this.#buckets.values()].map((entry) => entry.startedAt + entry.windowMs));
          this.#nextCapacityCheck = earliestExpiry;
          const retryAfterSeconds = Math.max(1, Math.ceil((earliestExpiry - now) / 1000));
          throw new AccountApiError(ErrorCode.RATE_LIMITED, undefined, { retryAfterSeconds });
        }
      }
      if (!bucket) {
        bucket = { startedAt: now, count: 0, windowMs };
        this.#buckets.set(key, bucket);
      } else {
        bucket.startedAt = now;
        bucket.count = 0;
        bucket.windowMs = windowMs;
      }
    }
    if (bucket.count >= maximum) {
      const retryAfterSeconds = Math.max(1, Math.ceil((bucket.startedAt + bucket.windowMs - now) / 1000));
      throw new AccountApiError(ErrorCode.RATE_LIMITED, undefined, { retryAfterSeconds });
    }
    bucket.count += 1;
    this.#cleanup(now);
  }

  #cleanup(now, force = false) {
    if (!force && now - this.#lastCleanup < 60_000) return;
    this.#lastCleanup = now;
    for (const [key, bucket] of this.#buckets) {
      if (now - bucket.startedAt >= bucket.windowMs) this.#buckets.delete(key);
    }
    if (this.#buckets.size < this.maximumBuckets) this.#nextCapacityCheck = 0;
  }
}
