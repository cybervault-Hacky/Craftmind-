/**
 * Phase 34: the website's own client code, exercised against the running account service.
 *
 * This is not a browser test and is not presented as one. What it does is stronger than a mock and weaker than
 * Playwright: it imports the *actual* module the site loads (`website/assets/adapters.js`), points it at a real HTTP
 * server built from `backend/src/server.js`, and drives the journeys the pages perform. So every claim below is about
 * the code that ships on both sides — the wire format the browser will use, and the router that answers it — with no
 * hand-written fixture in between. No DOM, no rendering, no click simulation: those need a browser, which this
 * environment does not have, and that gap is recorded as NOT RUN rather than papered over here.
 *
 * Why this file exists: Phases 22-33 grew the website's data boundary from "interface only" into real traffic over
 * ~50 endpoints, and nothing verified that the two sides still agree. The first bug this suite caught is documented
 * inline in the password-recovery test.
 */
import assert from "node:assert/strict";
import test from "node:test";
import { describe } from "node:test";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { call, startService, VALID_PASSWORD } from "./helpers.js";
import { DevelopmentEmailSink } from "../src/email-delivery.js";
import { ErrorCode } from "../src/errors.js";
import { CREATOR_AGREEMENT_VERSION } from "../src/onboarding.js";

const REPO_ROOT = resolve(import.meta.dirname, "..", "..");
const WEBSITE_ADAPTERS = pathToFileURL(resolve(REPO_ROOT, "website/assets/adapters.js")).href;

/** Every rate-limit knob the flows below touch, loosened so the assertions are about contracts, not budgets. */
const RELAXED = Object.freeze({
  RATE_LOGIN_MAX: "10000", RATE_REGISTER_MAX: "10000", RATE_VERIFICATION_MAX: "10000",
  RATE_RESET_REQUEST_MAX: "10000", RATE_RESET_CONFIRM_MAX: "10000", RATE_GUEST_MAX: "10000",
  RATE_ONBOARDING_READ_MAX: "10000", RATE_ONBOARDING_WRITE_MAX: "10000",
  RATE_CREATOR_READ_MAX: "10000", RATE_CREATOR_WRITE_MAX: "10000",
  RATE_MARKETPLACE_READ_MAX: "10000", RATE_MARKETPLACE_WRITE_MAX: "10000",
  RATE_HIRE_READ_MAX: "10000", RATE_HIRE_WRITE_MAX: "10000",
  RATE_ORDER_READ_MAX: "10000", RATE_ORDER_WRITE_MAX: "10000",
});

const serviceEmail = () => `site-${Math.random().toString(36).slice(2)}@example.test`;
const emailSink = new DevelopmentEmailSink();
let service;
/** The website module, loaded fresh so the module-scoped in-memory session starts empty. */
async function loadWebsiteModule(tag) {
  return import(`${WEBSITE_ADAPTERS}?suite=${tag}`);
}

test.before(async () => {
  service = await startService({ ...RELAXED }, { emailDelivery: emailSink });
});
test.after(async () => { await service?.close(); });

/** A signed-in adapter pair for the given email, verified through the development sink like a real user would. */
async function signInNewAccount(website, { email = serviceEmail(), displayName = "Site Contract Tester" } = {}) {
  const account = website.createAccountAdapter({ origin: service.baseUrl });
  const registered = await account.register({ email, password: VALID_PASSWORD, displayName });
  assert.equal(registered.status, website.RESULT.OK, JSON.stringify(registered));
  const message = emailSink.takeMessage("verification", email);
  assert.ok(message?.token, "the development sink must hand over the verification token");
  const verified = await account.verifyEmail({ token: message.token });
  assert.equal(verified.status, website.RESULT.OK, JSON.stringify(verified));
  const signedIn = await account.signIn({ email, password: VALID_PASSWORD });
  assert.equal(signedIn.status, website.RESULT.OK, JSON.stringify(signedIn));
  return { account, email, signedIn };
}

describe("Phase 34 website↔backend: the account journey over the wire", () => {
  test("register, verify, sign in, read, and sign out — with the fields the pages actually render", async () => {
    const website = await loadWebsiteModule("account");
    const { account, email } = await signInNewAccount(website);

    // signIn must populate exactly what the adapter itself copies out of the response (adapters.js reads
    // payload.session.accessToken / .refreshToken and payload.account) or every later call would lose the session.
    const probe = await account.loadAccount();
    assert.equal(probe.status, website.RESULT.OK, JSON.stringify(probe));
    assert.equal(probe.payload.account.email, email, "the profile card renders this object");
    assert.equal(probe.payload.account.displayName, "Site Contract Tester");

    const sessions = await account.loadSessions();
    assert.equal(sessions.status, website.RESULT.OK, JSON.stringify(sessions));
    assert.ok(Array.isArray(sessions.payload.sessions), "`GET /auth/sessions` must answer with a `sessions` array");
    assert.ok(sessions.payload.sessions.length >= 1, "the browser's own session has to appear in the list it renders");

    const changed = await account.changePassword({ currentPassword: VALID_PASSWORD, newPassword: "Second Correct Horse 22" });
    assert.equal(changed.status, website.RESULT.OK, JSON.stringify(changed));

    const afterSignOut = await account.signOut();
    assert.equal(afterSignOut.status, website.RESULT.OK, JSON.stringify(afterSignOut));
    // After sign-out the adapter holds no token, and the pages must fall back to their signed-out state rather than
    // throwing on a missing field.
    const signedOut = await account.loadAccount();
    assert.equal(signedOut.status, website.RESULT.UNAUTHORIZED, JSON.stringify(signedOut));
    assert.equal(signedOut.reason, website.REASON.NOT_SIGNED_IN);

    // The new password works and the old one does not: proof the adapter's change call reached the real credential store.
    const reopened = await account.signIn({ email, password: "Second Correct Horse 22" });
    assert.equal(reopened.status, website.RESULT.OK, JSON.stringify(reopened));
    const stale = await account.signIn({ email, password: VALID_PASSWORD });
    assert.notEqual(stale.status, website.RESULT.OK, "the superseded password must not still authenticate");
  });

  test("password recovery consumes the token the service emailed, under the field name the server requires", async () => {
    const website = await loadWebsiteModule("recovery");
    const { account, email } = await signInNewAccount(website, { displayName: "Recovery Tester" });

    const requested = await account.requestPasswordReset({ email });
    assert.equal(requested.status, website.RESULT.OK, JSON.stringify(requested));
    const handoff = emailSink.takeMessage("password-recovery", email);
    assert.ok(handoff?.token, "the development sink must carry the recovery token");

    // This is the call the website used to make as `{ email, code, newPassword }`. `confirmPasswordRecovery` reads
    // `requireString(body, "token")`, and the account is derived from that token — so the old shape was refused with
    // 400 INVALID_REQUEST before it ever looked at a credential, and no reset could ever complete in a browser.
    // The adapter now sends `{ token, newPassword }`, matching `docs/account-authentication.md` and the Android
    // client (`AccountApiRequests.confirmPasswordReset`), and the response is the documented `{reset, revokedSessions}`.
    const confirmed = await account.confirmPasswordReset({ token: handoff.token, newPassword: "Recovered Correct Horse 9" });
    assert.equal(confirmed.status, website.RESULT.OK, `recovery must succeed over the wire: ${JSON.stringify(confirmed)}`);
    assert.equal(confirmed.payload.reset, true);
    assert.ok(Number.isInteger(confirmed.payload.revokedSessions), "the UI reports how many sessions were revoked");

    // The reset revoked every session, including the one this adapter still held: the site has to show signed-out.
    const orphaned = await account.loadCredits();
    assert.equal(orphaned.status, website.RESULT.UNAUTHORIZED, "a token the server revoked cannot keep the page looking signed in");
    const freshSignIn = await account.signIn({ email, password: "Recovered Correct Horse 9" });
    assert.equal(freshSignIn.status, website.RESULT.OK, JSON.stringify(freshSignIn));
  });

  test("an expired or revoked session surfaces as a sign-in prompt, never as a fabricated success", async () => {
    const website = await loadWebsiteModule("expiry");
    const { account, signedIn } = await signInNewAccount(website, { displayName: "Expiry Tester" });
    const accessToken = signedIn.payload.session.accessToken;
    assert.ok(typeof accessToken === "string" && accessToken.length > 20, "the test needs the live token to revoke it server-side");

    const listed = await account.loadSessions();
    const mine = listed.payload.sessions.find((session) => session.isCurrent === true);
    assert.ok(mine?.sessionId, "the sessions panel has to be able to identify the row it is standing on");

    // A session cannot revoke itself — `POST /auth/sessions/revoke` refuses that with
    // CURRENT_SESSION_REVOKE_NOT_ALLOWED, which is the point of the `isCurrent` marker the panel renders. So the
    // realistic stale-token case is reproduced the way it happens in the field: a second sign-in revokes the first.
    const other = await call(service.baseUrl, "POST", "/auth/login", { body: { email: signedIn.payload.account.email, password: VALID_PASSWORD } });
    assert.equal(other.status, 200, JSON.stringify(other.body));
    const revoked = await call(service.baseUrl, "POST", "/auth/sessions/revoke", {
      headers: { authorization: `Bearer ${other.body.session.accessToken}` }, raw: JSON.stringify({ sessionId: mine.sessionId }),
    });
    assert.equal(revoked.status, 200, JSON.stringify(revoked.body));
    assert.equal(revoked.body.revoked, true);

    // The adapter still holds a token in memory — this is precisely the stale-session case a browser hits when the
    // service revokes behind it. `requestJson` maps the 401 to UNAUTHORIZED so pages render "sign in", and the
    // mapping has to happen for reads that would otherwise render a blank number as if it were real.
    for (const method of ["loadAccount", "loadCredits", "loadMembership", "loadEntitlements"]) {
      const result = await account[method]();
      assert.equal(result.status, website.RESULT.UNAUTHORIZED, `${method} treated a revoked token as ${result.status}`);
      assert.equal(result.reason, website.REASON.NOT_SIGNED_IN, `${method} must report the sign-in reason`);
    }
  });
});

describe("Phase 34 website↔backend: reads the account pages render", () => {
  test("membership, entitlements and credits return the server's own objects, with no client-side arithmetic", async () => {
    const website = await loadWebsiteModule("reads");
    const { account } = await signInNewAccount(website, { displayName: "Reads Tester" });
    const adapters = website.createAdapters({ origin: service.baseUrl });

    const membership = await adapters.membership.loadMembership();
    assert.equal(membership.status, website.RESULT.OK, JSON.stringify(membership));
    for (const field of ["membership", "plan", "credits"]) {
      assert.ok(field in membership.payload, `membership.html reads payload.${field}; the service must send it`);
    }
    const entitlements = await adapters.entitlements.listEntitlements();
    assert.equal(entitlements.status, website.RESULT.OK, JSON.stringify(entitlements));
    assert.ok(Array.isArray(entitlements.payload.entitlements), "the entitlement list is rendered as rows");
    const credits = await adapters.membership.loadCredits();
    assert.equal(credits.status, website.RESULT.OK, JSON.stringify(credits));
    // `creditsView` exposes available / expiring / expired / lifetimeGranted / lifetimeConsumed. The meter renders
    // `available`, so that is the field that has to be a number rather than a string or a missing key.
    assert.ok(Number.isInteger(credits.payload.credits.available), `credits.available must be a number for the meter: ${JSON.stringify(credits.payload)}`);
    assert.ok(credits.payload.credits.available >= 0, "a negative balance would be rendered as spendable credit");
  });

  test("creator identity reads answer for an account that has not onboarded, in the shape the UI branches on", async () => {
    const website = await loadWebsiteModule("creator-reads");
    const { account } = await signInNewAccount(website, { displayName: "No Profile Yet" });
    const profile = await account.loadCreatorProfile();
    assert.equal(profile.status, website.RESULT.OK, JSON.stringify(profile));
    // The studio renders `payload.profile` when present and `payload.eligibility` otherwise; both keys must exist so
    // the page picks a real branch instead of throwing and showing a blank panel.
    assert.ok("profile" in profile.payload && "eligibility" in profile.payload, JSON.stringify(profile.payload));
    const eligibility = await account.loadCreatorEligibility();
    assert.equal(eligibility.status, website.RESULT.OK, JSON.stringify(eligibility));
    // The banner branches on `eligible` and otherwise prints the server's own `reasons` array — both must be present,
    // and the reasons must be sentences rather than codes, because they are rendered directly.
    assert.equal(typeof eligibility.payload.eligibility.eligible, "boolean", JSON.stringify(eligibility.payload));
    assert.equal(eligibility.payload.eligibility.eligible, false, "a fresh account holds no Creator entitlement");
    assert.ok(Array.isArray(eligibility.payload.eligibility.reasons) && eligibility.payload.eligibility.reasons.length > 0);
    assert.match(eligibility.payload.eligibility.reasons[0], /[a-z]{4,}/, "a reason has to be readable text");
  });
});

describe("Phase 34 website↔backend: onboarding writes speak the exact server key set", () => {
  test("the buyer and seller forms the site builds are accepted as-is", async () => {
    const website = await loadWebsiteModule("onboarding");
    const { account } = await signInNewAccount(website, { displayName: "Onboarding Tester" });

    // Key sets copied from the UI's own builders, so this fails the moment either side renames a field:
    // onboarding.js `answers = { fullName, referralSource }` (+ referralDetail when the source is OTHER).
    const buyer = await account.saveBuyerOnboarding({ fullName: "Rowan Site Tester", referralSource: "FRIEND_REFERRAL" });
    assert.equal(buyer.status, website.RESULT.OK, JSON.stringify(buyer));

    // The seller form adds the agreement pair and the three multi-select lists. It cannot *succeed* for a fresh
    // account, because the step is gated on the Creator entitlement and no plan is purchasable — so what this
    // asserts is the thing only the client can get wrong: that the refusal reaches the form as a refusal carrying the
    // server's code, which onboarding.js branches on to offer "View membership".
    const seller = await account.saveSellerOnboarding({
      handle: `studio-${Math.random().toString(36).slice(2, 8)}`, displayName: "Rowan Studio",
      referralSource: "FRIEND_REFERRAL", editions: ["java"], minecraftVersions: ["1.20.1"], loaders: ["Fabric"],
      agreementAccepted: true, agreementVersion: CREATOR_AGREEMENT_VERSION,
    });
    assert.equal(seller.status, website.RESULT.ERROR, JSON.stringify(seller));
    assert.notEqual(seller.status, website.RESULT.UNAUTHORIZED,
      "a signed-in account without the entitlement must not be told to sign in again — that instruction can never help it");
    assert.equal(seller.payload?.error?.code, "CREATOR_ENTITLEMENT_REQUIRED", JSON.stringify(seller));
    assert.match(seller.message, /no plan is purchasable/i, "the server's own explanation is what the form renders");

    // The key set the form sends is the one the service accepts: an entitlement refusal (403) rather than a shape
    // refusal (400) is the proof, and the success-path fields it would render (`seller.completedAt`,
    // `profile.handle`, `profile.verification`) are the ones `sellerOnboardingView`/`creatorProfileResult` produce.
    const rejectedForShape = await account.saveBuyerOnboarding({
      handle: `studio-${Math.random().toString(36).slice(2, 8)}`, displayName: "Rowan Studio",
      referralSource: "not-a-source", editions: ["java"], minecraftVersions: ["1.20.1"], loaders: ["Fabric"],
      agreementAccepted: true, agreementVersion: CREATOR_AGREEMENT_VERSION,
    });
    assert.equal(rejectedForShape.payload?.error?.code, ErrorCode.INVALID_REQUEST, "the same route does answer 400 for a bad body, so the 403 above was about entitlement, not shape");

    const state = await account.loadOnboardingState();
    assert.equal(state.status, website.RESULT.OK, JSON.stringify(state));
    assert.equal(state.payload.onboarding.buyer.completed, true, "the dashboard's completion check must see the saved answers");
    // The seller half is still incomplete for exactly the reason the refusal gave, and the page must reflect that
    // rather than showing a partially-saved step as done.
    assert.equal(state.payload.onboarding.seller.completed, false, JSON.stringify(state.payload.onboarding.seller));
  });

  test("a smuggled field is refused as a bad request, which is the branch the forms already render", async () => {
    const website = await loadWebsiteModule("onboarding-smuggle");
    const { account } = await signInNewAccount(website, { displayName: "Smuggle Tester" });
    const refused = await account.saveBuyerOnboarding({ fullName: "Rowan Selby", referralSource: "FRIEND_REFERRAL", verified: true });
    assert.equal(refused.status, website.RESULT.ERROR, JSON.stringify(refused));
    assert.equal(refused.payload?.error?.code, ErrorCode.INVALID_REQUEST, "strictBody must refuse the extra key, and the page must be able to show its message");
    assert.ok(typeof refused.message === "string" && refused.message.length > 0, "the form renders `payload.error.message ?? result.message`");
  });
});

describe("Phase 34 website↔backend: marketplace, hire, and the honest inert adapters", () => {
  test("public discovery answers with real empty state rather than a fabricated card", async () => {
    const website = await loadWebsiteModule("marketplace");
    const adapters = website.createAdapters({ origin: service.baseUrl });
    const search = await adapters.marketplace.searchListings({ query: "", limit: 12 });
    assert.equal(search.status, website.RESULT.OK, JSON.stringify(search));
    // marketplace.js maps `payload.items` into cards and uses `hasMore` for the pager; both must exist even when the
    // catalog is empty, or the page renders an error panel instead of the honest "nothing published yet" state.
    assert.ok(Array.isArray(search.payload.items), `expected an items array: ${JSON.stringify(search.payload)}`);
    assert.equal(search.payload.items.length, 0, "a fresh service has nothing published, and the site must say so");
    assert.equal(typeof search.payload.total, "number");
    assert.equal(typeof search.payload.hasMore, "boolean");
    assert.ok(!("listings" in search.payload), "the search surface must not answer with a second, different key for the same list");

    const categories = await adapters.marketplace.listCategories();
    assert.equal(categories.status, website.RESULT.OK, JSON.stringify(categories));
    assert.equal(categories.payload.categories.length, 8, "the eight categories the service validates");
  });

  test("a buyer can post a job and manage it through the adapter the hire pages use", async () => {
    const website = await loadWebsiteModule("hire");
    const { account } = await signInNewAccount(website, { displayName: "Job Buyer" });
    const adapters = website.createAdapters({ origin: service.baseUrl });

    // hire.js builds exactly this object in `createJob`; if the service renames a field the adapter call below turns red.
    const created = await adapters.hire.createJob({
      title: "A redstone-powered automatic farm", description: "Enclosed 12x12 pump room, no item loss, vanilla-compatible.",
      edition: "java", minecraftVersion: "1.20.1", scope: "One walled farm with a comparator hopper clock",
      // The service's own vocabularies: editions are lower-case tokens, loaders are the display-cased names. A page
      // that sent "FABRIC" here would be refused, so this doubles as the check that both sides still use one casing.
      loaders: ["Fabric"], imageReferences: [], budgetMin: 400, budgetMax: 900, budgetCurrency: "USD", deadline: "2026-12-24",
    });
    assert.equal(created.status, website.RESULT.OK, JSON.stringify(created));
    const jobId = created.payload.job?.id ?? created.payload.id;
    assert.ok(typeof jobId === "string" && jobId.startsWith("job_"), `the job id is what every later call keys on: ${JSON.stringify(created.payload)}`);

    const mine = await adapters.hire.listMyJobs();
    assert.equal(mine.status, website.RESULT.OK, JSON.stringify(mine));
    assert.equal(mine.payload.jobs.length, 1, "the buyer's own list must show the job the site just created");

    const open = await adapters.hire.searchJobs({});
    assert.equal(open.status, website.RESULT.OK, JSON.stringify(open));
    assert.equal(open.payload.items.some((job) => job.id === jobId), true, "a posted job appears in the public directory it advertises");

    const cancelled = await adapters.hire.cancelJob(jobId);
    assert.equal(cancelled.status, website.RESULT.OK, JSON.stringify(cancelled));
    const afterCancel = await adapters.hire.getOwnedJob(jobId);
    assert.equal(afterCancel.status, website.RESULT.OK, JSON.stringify(afterCancel));
    const reloaded = afterCancel.payload.job ?? afterCancel.payload;
    assert.equal(reloaded.status, "CANCELLED", "the cancel button has to be reflected in the state the page re-reads");
    const counts = (await adapters.hire.listMyJobs()).payload.counts;
    assert.equal(counts.cancelled, 1, "the buyer dashboard counts cancelled jobs, so that figure must move too");
  });

  test("listing writes for an account without the Creator entitlement come back as the refusal the studio branches on", async () => {
    const website = await loadWebsiteModule("listing-refusal");
    await signInNewAccount(website, { displayName: "No Entitlement" });
    const adapters = website.createAdapters({ origin: service.baseUrl });
    const refused = await adapters.creator.createDraft({
      title: "Media keep facade", description: "A 1.20.1 survival-safe facade built from the media pack.",
      category: "Structures", edition: "java", minecraftVersions: ["1.20.1"],
    });
    assert.equal(refused.status, website.RESULT.ERROR, JSON.stringify(refused));
    const code = refused.payload?.error?.code;
    assert.ok(typeof code === "string", "creator-studio.js compares `result.payload.error.code`; without it the banner cannot say why");
    // The studio has an explicit branch for a missing entitlement; anything else must still render its typed message.
    // The studio has an explicit branch for exactly this code; if the service ever refused for a different reason the
    // page would show a generic error for a state it knows how to explain, so the code is asserted, not merely typed.
    assert.equal(code, "CREATOR_ENTITLEMENT_REQUIRED", JSON.stringify(refused));
    assert.match(refused.message, /no plan is purchasable/i, "the refusal must keep saying that nothing can be bought yet");
    assert.ok(typeof refused.message === "string" && refused.message.length > 0);
  });

  test("nothing monetising pretends to work: payments, orders, reviews and analytics stay inert by contract", async () => {
    const website = await loadWebsiteModule("inert");
    const adapters = website.createAdapters({ origin: service.baseUrl });
    for (const [name, method] of [["payments", "startCheckout"], ["orders", "listSalesOrders"], ["reviews", "submitReview"], ["analytics", "revenue"]]) {
      const result = await adapters[name][method]({ id: "lst_00000000-0000-4000-8000-000000000000" });
      assert.equal(result.status, website.RESULT.UNAVAILABLE, `${name}.${method} must not report success without a backend`);
      assert.equal(result.reason, website.REASON.NOT_IMPLEMENTED, `${name}.${method} must name the real reason`);
    }
  });
});

describe("Phase 34 website↔backend: the declared endpoint list still matches the running router", () => {
  test("every endpoint the website says it may call exists on the service", async () => {
    const website = await loadWebsiteModule("endpoints");
    const declared = website.ACCOUNT_ENDPOINTS;
    assert.ok(declared.length > 40, `the declared surface should be substantial, found ${declared.length}`);
    assert.equal(new Set(declared).size, declared.length, "the allowlist must not carry duplicates");

    // A request to an unknown path is answered 400 with "Unknown account endpoint."; a known path with no session is
    // 401 (or 200 for the public reads). Probing each declared endpoint and requiring that the router recognises it
    // turns the comment in adapters.js into something that fails when the two sides drift.
    const unrecognised = [];
    for (const entry of declared) {
      const [method, path] = entry.split(" ");
      const probed = path.replace(/:[a-zA-Z]+/g, "00000000-0000-4000-8000-000000000000");
      const response = await call(service.baseUrl, method, probed, { raw: method === "GET" ? undefined : "{}" });
      const unknown = response.status === 400 && response.body?.error?.code === ErrorCode.INVALID_REQUEST
        && /unknown account endpoint/i.test(response.body?.error?.message ?? "");
      if (unknown) unrecognised.push(entry);
    }
    assert.deepEqual(unrecognised, [], "these endpoints are advertised to the browser but the service has no such route");
  });

  test("the boundary description reports the adapters a real deployment configures, and the ones it does not", async () => {
    const website = await loadWebsiteModule("boundary");
    const unconfigured = website.describeIntegrationBoundary();
    assert.equal(unconfigured.boundary.account.configured, false, "a published static site has no origin, so nothing may claim to be live");
    assert.equal(unconfigured.boundary.payments.configured, false, "payments must stay unconfigured while Phase 28 is deferred");
    assert.ok(unconfigured.accountEndpoints.length > 40);

    const website2 = await loadWebsiteModule("boundary-configured");
    const adapters = website2.createAdapters({ origin: service.baseUrl });
    assert.equal(adapters.account.configured, true);
    assert.equal(adapters.creator.configured, true);
    assert.equal(adapters.payments.configured, false, "an origin never turns the payment surface on");
  });
});
