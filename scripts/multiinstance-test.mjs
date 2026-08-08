// Proves TransferConcurrencyTest's guarantee holds across separate processes, not just threads in
// one JVM: fires the same contended-transfer scenarios via HTTP, round-robining requests across
// three independently-running LedgerAPI instances sharing one Postgres, then reconciles balances
// from the transaction log exactly as the JUnit test does.
//
// Usage:
//   docker compose --profile multi up -d --build postgres app1 app2 app3
//   node scripts/multiinstance-test.mjs
//
// Requires Node 18+ (built-in fetch). Point it at different instances with LEDGER_API_INSTANCES
// (comma-separated base URLs).

const INSTANCES = (
  process.env.LEDGER_API_INSTANCES || "http://localhost:8081,http://localhost:8082,http://localhost:8083"
).split(",");

let nextInstance = 0;
function pickInstance() {
  const url = INSTANCES[nextInstance % INSTANCES.length];
  nextInstance++;
  return url;
}

async function timedFetch(path, opts) {
  const base = pickInstance();
  const start = performance.now();
  const res = await fetch(`${base}${path}`, opts);
  const body = await res.text();
  return { status: res.status, body, ms: performance.now() - start, instance: base };
}

async function runAcrossInstances(total, requestFn) {
  return Promise.all(Array.from({ length: total }, (_, i) => requestFn(i)));
}

async function register() {
  const email = `multiinstance-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
  const password = "multiinstance-password-123";

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

async function reconcile(auth, accountId) {
  let page = 0, all = [];
  while (true) {
    const r = await timedFetch(`/api/accounts/${accountId}/transactions?page=${page}&size=100`, { headers: auth });
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
  const stored = JSON.parse((await timedFetch(`/api/accounts/${accountId}`, { headers: auth })).body).balance;
  return {
    computed: balance.toFixed(2),
    stored,
    txnCount: all.length,
    matches: balance.toFixed(2) === parseFloat(stored).toFixed(2),
  };
}

async function main() {
  console.log(`Round-robining across ${INSTANCES.length} instances: ${INSTANCES.join(", ")}`);
  const auth = await register();

  // --- Scenario 1: 20-way contention out of one account, requests spread across instances ---
  {
    const source = await openAccount(auth, "1000.00");
    const destination = await openAccount(auth, "0.00");

    const results = await runAcrossInstances(20, () =>
      timedFetch("/api/transfers", {
        method: "POST", headers: auth,
        body: JSON.stringify({ fromAccountId: source.id, toAccountId: destination.id, amount: "10.00" }),
      })
    );

    const byInstance = {};
    for (const r of results) byInstance[r.instance] = (byInstance[r.instance] || 0) + 1;
    const succeeded = results.filter((r) => r.status === 201).length;

    console.log("\n=== Scenario 1: 20-way contention on one account, across instances ===");
    console.log(`requests per instance: ${JSON.stringify(byInstance)}`);
    console.log(`succeeded: ${succeeded}/20`);

    const sourceRecon = await reconcile(auth, source.id);
    const destRecon = await reconcile(auth, destination.id);
    console.log(`source reconciles: ${sourceRecon.matches} (computed=${sourceRecon.computed}, stored=${sourceRecon.stored})`);
    console.log(`destination reconciles: ${destRecon.matches} (computed=${destRecon.computed}, stored=${destRecon.stored})`);

    if (succeeded !== 20 || !sourceRecon.matches || !destRecon.matches) {
      throw new Error("Scenario 1 FAILED: balances did not reconcile across instances");
    }
  }

  // --- Scenario 2: 20 concurrent overdraft attempts against a balance that only covers 5 ---
  {
    const source = await openAccount(auth, "50.00");
    const destination = await openAccount(auth, "0.00");

    const results = await runAcrossInstances(20, () =>
      timedFetch("/api/transfers", {
        method: "POST", headers: auth,
        body: JSON.stringify({ fromAccountId: source.id, toAccountId: destination.id, amount: "10.00" }),
      })
    );

    const succeeded = results.filter((r) => r.status === 201).length;
    console.log("\n=== Scenario 2: overdraft-limited contention, across instances ===");
    console.log(`succeeded: ${succeeded}/20 (expected exactly 5)`);

    const sourceRecon = await reconcile(auth, source.id);
    console.log(`source reconciles: ${sourceRecon.matches} (computed=${sourceRecon.computed}, stored=${sourceRecon.stored})`);

    if (succeeded !== 5 || !sourceRecon.matches || parseFloat(sourceRecon.stored) < 0) {
      throw new Error("Scenario 2 FAILED: overdraft protection or reconciliation broke across instances");
    }
  }

  console.log("\nAll multi-instance concurrency scenarios passed.");
}

main().catch((e) => { console.error(e); process.exit(1); });
