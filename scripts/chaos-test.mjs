// Chaos test: fires concurrent transfer load across three instances, kills one mid-run, and proves
// the ledger never corrupts even though requests in flight to the killed instance fail visibly.
//
// What this does NOT prove: that no individual request ever fails. A request being processed on
// the instance at the exact moment it dies has no way to complete - there is no distributed
// transaction between the load balancer and the app. What it proves is narrower and more important:
// after the dust settles, the ledger balance still reconciles exactly with its transaction log.
//
// Usage:
//   docker compose --profile multi up -d --build postgres app1 app2 app3
//   node scripts/chaos-test.mjs
//
// Requires Node 18+ (built-in fetch) and the docker CLI. Restart the killed container afterward
// (the compose *service* name is "app2" - "ledger-app2" is only its container_name):
//   docker compose --profile multi up -d app2

import { execSync } from "node:child_process";

const INSTANCES = (
  process.env.LEDGER_API_INSTANCES || "http://localhost:8081,http://localhost:8082,http://localhost:8083"
).split(",");
const KILL_CONTAINER = process.env.LEDGER_KILL_CONTAINER || "ledger-app2";
const KILL_AFTER_MS = Number(process.env.LEDGER_KILL_AFTER_MS || 300);

let nextInstance = 0;
function pickInstance() {
  const url = INSTANCES[nextInstance % INSTANCES.length];
  nextInstance++;
  return url;
}

async function timedFetch(path, opts) {
  const base = pickInstance();
  const start = performance.now();
  try {
    const res = await fetch(`${base}${path}`, opts);
    const body = await res.text();
    return { status: res.status, body, ms: performance.now() - start, instance: base, failed: false };
  } catch (err) {
    return { status: null, body: String(err), ms: performance.now() - start, instance: base, failed: true };
  }
}

async function register() {
  const email = `chaos-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
  const password = "chaos-password-123";
  let r = await timedFetch("/api/auth/register", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (r.status !== 201) throw new Error(`register failed: ${r.status} ${r.body}`);
  r = await timedFetch("/api/auth/login", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (r.status !== 200) throw new Error(`login failed: ${r.status} ${r.body}`);
  const { token } = JSON.parse(r.body);
  return { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
}

async function openAccount(auth, opening) {
  let r = await timedFetch("/api/accounts", { method: "POST", headers: auth, body: JSON.stringify({ currency: "GBP" }) });
  const account = JSON.parse(r.body);
  if (parseFloat(opening) > 0) {
    r = await timedFetch(`/api/accounts/${account.id}/deposit`, {
      method: "POST", headers: auth, body: JSON.stringify({ amount: opening }),
    });
    if (r.status !== 201) throw new Error(`seed deposit failed: ${r.status} ${r.body}`);
  }
  return account;
}

// Reconciliation happens after the kill, so the shared round-robin counter in pickInstance() can
// still land a read on the now-permanently-dead instance (docker kill doesn't come back on its own).
// That's not a ledger-correctness question - it's just "which of the two live instances do I ask?" -
// so reconciliation reads retry against the next instance instead of failing the whole test on a
// routing fluke.
async function fetchLive(path, opts) {
  let last;
  for (let i = 0; i < INSTANCES.length; i++) {
    last = await timedFetch(path, opts);
    if (!last.failed) return last;
  }
  throw new Error(`all instances unreachable for ${path}: ${last.body}`);
}

async function reconcile(auth, accountId) {
  let page = 0, all = [];
  while (true) {
    const r = await fetchLive(`/api/accounts/${accountId}/transactions?page=${page}&size=100`, { headers: auth });
    const body = JSON.parse(r.body);
    all = all.concat(body.content);
    if (page >= body.totalPages - 1) break;
    page++;
  }
  let balance = 0;
  for (const t of all) {
    const amt = parseFloat(t.amount);
    balance += (t.type === "DEPOSIT" || t.type === "TRANSFER_IN") ? amt : -amt;
  }
  const stored = JSON.parse((await fetchLive(`/api/accounts/${accountId}`, { headers: auth })).body).balance;
  return { computed: balance.toFixed(2), stored, matches: balance.toFixed(2) === parseFloat(stored).toFixed(2) };
}

async function main() {
  console.log(`Instances: ${INSTANCES.join(", ")}`);
  console.log(`Will kill container "${KILL_CONTAINER}" ~${KILL_AFTER_MS}ms into the run`);

  const auth = await register();
  const source = await openAccount(auth, "1000.00");
  const destination = await openAccount(auth, "0.00");

  const killTimer = setTimeout(() => {
    console.log(`\n>>> killing ${KILL_CONTAINER}`);
    execSync(`docker kill ${KILL_CONTAINER}`, { stdio: "inherit" });
  }, KILL_AFTER_MS);

  const total = 60;
  const results = await Promise.all(Array.from({ length: total }, () =>
    timedFetch("/api/transfers", {
      method: "POST", headers: auth,
      body: JSON.stringify({ fromAccountId: source.id, toAccountId: destination.id, amount: "5.00" }),
    })
  ));
  clearTimeout(killTimer);

  const succeeded = results.filter((r) => r.status === 201).length;
  const connectionFailures = results.filter((r) => r.failed).length;
  const otherFailures = results.filter((r) => !r.failed && r.status !== 201).length;

  console.log("\n=== Chaos test results ===");
  console.log(`succeeded: ${succeeded}/${total}`);
  console.log(`connection failures (instance down): ${connectionFailures}`);
  console.log(`other non-201 responses: ${otherFailures}`);

  console.log("\nWaiting 2s for in-flight work to settle before reconciling...");
  await new Promise((resolve) => setTimeout(resolve, 2000));

  const sourceRecon = await reconcile(auth, source.id);
  const destRecon = await reconcile(auth, destination.id);
  console.log(`source reconciles: ${sourceRecon.matches} (computed=${sourceRecon.computed}, stored=${sourceRecon.stored})`);
  console.log(`destination reconciles: ${destRecon.matches} (computed=${destRecon.computed}, stored=${destRecon.stored})`);

  const expectedDelta = (succeeded * 5).toFixed(2);
  const actualDelta = parseFloat(destRecon.stored).toFixed(2);

  console.log(`\nsucceeded transfers moved $${expectedDelta} (5.00 x ${succeeded}); destination balance is $${actualDelta}`);

  if (!sourceRecon.matches || !destRecon.matches) {
    throw new Error("CHAOS TEST FAILED: ledger did not reconcile after killing an instance");
  }
  if (expectedDelta !== actualDelta) {
    throw new Error(
      "CHAOS TEST FAILED: destination balance does not match the count of successful transfers - " +
      "money moved without a matching success response, or vice versa"
    );
  }
  if (connectionFailures === 0) {
    console.warn(
      "\nWARNING: no connection failures observed - the kill may not have landed mid-request. " +
      "Try lowering LEDGER_KILL_AFTER_MS or increasing load."
    );
  }

  console.log("\nChaos test passed: the ledger reconciled correctly despite a mid-run instance kill.");
  console.log(`Remember to restart the killed instance "${KILL_CONTAINER}" - via its compose service name, ` +
    "e.g. docker compose --profile multi up -d app2");
}

main().catch((e) => { console.error(e); process.exit(1); });
