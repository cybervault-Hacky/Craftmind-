/**
 * Provider-neutral one-time-code delivery boundary. The local memory sink never sends mail and never logs token values;
 * production uses an HTTPS JSON webhook adapter that can target an organization's chosen mail provider.
 */

export class DevelopmentEmailSink {
  #messages = [];
  #maximumMessages;
  mode = "DEVELOPMENT_SINK";

  constructor({ maximumMessages = 1000 } = {}) {
    if (!Number.isInteger(maximumMessages) || maximumMessages < 1 || maximumMessages > 10_000) {
      throw new RangeError("development email sink capacity is out of range");
    }
    this.#maximumMessages = maximumMessages;
  }

  async sendVerification({ email, token, expiresAt }) {
    this.#store(Object.freeze({ kind: "verification", email, token, expiresAt }));
    return { mode: this.mode };
  }

  async sendPasswordRecovery({ email, token, expiresAt }) {
    this.#store(Object.freeze({ kind: "password-recovery", email, token, expiresAt }));
    return { mode: this.mode };
  }

  #store(message) {
    if (this.#messages.length >= this.#maximumMessages) this.#messages.shift();
    this.#messages.push(message);
  }

  /** Test-only local inspection. There is deliberately no HTTP route for reading this mailbox. */
  takeMessage(kind, email) {
    const index = this.#messages.findIndex((message) => message.kind === kind && message.email === email);
    if (index < 0) return null;
    return this.#messages.splice(index, 1)[0];
  }

  get pendingCount() {
    return this.#messages.length;
  }
}

export class HttpsWebhookEmailDelivery {
  constructor({ url, token, from, timeoutMs = 10_000, fetchImpl = fetch }) {
    this.url = url;
    this.token = token;
    this.from = from;
    this.timeoutMs = timeoutMs;
    this.fetchImpl = fetchImpl;
    this.mode = "PROVIDER_CONFIGURED";
  }

  async sendVerification({ email, token, expiresAt }) {
    await this.#send({
      to: email,
      subject: "Verify your CraftMind email",
      text: `Open CraftMind Account > Verify email and enter this one-time code: ${token}\n\nThis code expires at ${expiresAt}. If you did not create this account, ignore this message.`,
    });
    return { mode: this.mode };
  }

  async sendPasswordRecovery({ email, token, expiresAt }) {
    await this.#send({
      to: email,
      subject: "Reset your CraftMind password",
      text: `Open CraftMind Account > Password recovery and enter this one-time code: ${token}\n\nThis code expires at ${expiresAt}. If you did not request a reset, ignore this message.`,
    });
    return { mode: this.mode };
  }

  async #send(message) {
    const response = await this.fetchImpl(this.url, {
      method: "POST",
      redirect: "error",
      signal: AbortSignal.timeout(this.timeoutMs),
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: `Bearer ${this.token}`,
      },
      body: JSON.stringify({ from: this.from, ...message }),
    });
    // Do not read or log the provider response: it could echo the one-time token or private recipient data.
    if (!response.ok) throw new Error("email provider did not accept the delivery");
    await response.body?.cancel().catch(() => {});
  }
}

export function createEmailDelivery(configuration) {
  if (configuration.emailProvider === "memory") return new DevelopmentEmailSink();
  if (configuration.emailProvider === "webhook") {
    return new HttpsWebhookEmailDelivery({
      url: configuration.emailWebhookUrl,
      token: configuration.emailWebhookToken,
      from: configuration.emailFrom,
    });
  }
  // Configuration validation should make this unreachable; fail closed if an unvalidated object is injected.
  throw new Error("email delivery provider is not configured");
}
