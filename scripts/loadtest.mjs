// Ad-hoc load test against a running LedgerAPI instance. Registers a throwaway user, opens two
// accounts, and drives read/write throughput and latency at the same concurrency (20) the Hikari
// pool is sized for — including a burst of concurrent requests sharing one idempotency key, and a
// reconciliation pass that recomputes both balances from the transaction log.
//
// Usage:
//   docker compose up -d postgres && SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger \
//     LEDGER_JWT_SECRET=local-loadtest-secret-change-me-0123456789abcdef0123456789 ./mvnw spring-boot:run &
//   node scripts/loadtest.mjs
//
// Requires Node 18+ (built-in fetch). Point it at another instance with LEDGER_API_URL.

const BASE = process.env.LEDGER_API_URL || "http://localhost:8080";

function pct(sorted, p) {
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[idx];
}

function summarize(label, latencies, wallMs, extra = {}) {
  const sorted = [...latencies].sort((a, b) => a - b);
  const n = sorted.length;
  const sum = sorted.reduce((a, b) => a + b, 0);
  const avg = n ? sum / n : 0;
  const throughput = n / (wallMs / 1000);
  console.log(`\n=== ${label} ===`);
  console.log(`requests: ${n}, wall: ${wallMs.toFixed(0)}ms, throughput: ${throughput.toFixed(1)} req/s`);
  console.log(`latency ms -> min:${sorted[0]?.toFixed(1)} avg:${avg.toFixed(1)} p50:${pct(sorted,50).toFixed(1)} p90:${pct(sorted,90).toFixed(1)} p95:${pct(sorted,95).toFixed(1)} p99:${pct(sorted,99).toFixed(1)} max:${sorted[n-1]?.toFixed(1)}`);
  for (const [k, v] of Object.entries(extra)) console.log(`${k}: ${v}`);
  return { label, n, wallMs, throughput, min: sorted[0], avg, p50: pct(sorted,50), p90: pct(sorted,90), p95: pct(sorted,95), p99: pct(sorted,99), max: sorted[n-1], extra };
}

async function timedFetch(url, opts) {
  const start = performance.now();
  const res = await fetch(url, opts);
  const body = await res.text();
  const ms = performance.now() - start;
  return { status: res.status, body, ms };
}

// run `total` requests with `concurrency` in-flight at a time, via a work-queue of workers
async function runConcurrent(total, concurrency, requestFn) {
  let next = 0;
  const latencies = [];
  const results = [];
  const start = performance.now();
  async function worker() {
    while (true) {
      const i = next++;
      if (i >= total) break;
      const r = await requestFn(i);
      latencies.push(r.ms);
      results.push(r);
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker));
  const wallMs = performance.now() - start;
  return { latencies, results, wallMs };
}

async function main() {
  const email = `loadtest-${Date.now()}@example.com`;
  const password = "loadtest-password-123";

  let r = await timedFetch(`${BASE}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (r.status !== 201) throw new Error(`register failed: ${r.status} ${r.body}`);

  r = await timedFetch(`${BASE}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (r.status !== 200) throw new Error(`login failed: ${r.status} ${r.body}`);
  const { token } = JSON.parse(r.body);
  const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };

  r = await timedFetch(`${BASE}/api/accounts`, { method: "POST", headers: auth, body: JSON.stringify({ currency: "USD" }) });
  const source = JSON.parse(r.body);
  r = await timedFetch(`${BASE}/api/accounts`, { method: "POST", headers: auth, body: JSON.stringify({ currency: "USD" }) });
  const dest = JSON.parse(r.body);

  r = await timedFetch(`${BASE}/api/accounts/${source.id}/deposit`, {
    method: "POST", headers: auth, body: JSON.stringify({ amount: "1000000.00" }),
  });
  if (r.status !== 201) throw new Error(`seed deposit failed: ${r.status} ${r.body}`);

  console.log(`setup done. source=${source.id} dest=${dest.id}`);

  const allResults = {};

  // --- Benchmark A: read-path baseline (GET account balance), concurrency 20, 2000 requests ---
  {
    const { latencies, wallMs } = await runConcurrent(2000, 20, async () => {
      return timedFetch(`${BASE}/api/accounts/${source.id}`, { headers: auth });
    });
    allResults.readBaseline = summarize("A: GET /api/accounts/{id} (read baseline, concurrency=20)", latencies, wallMs);
  }

  // --- Benchmark B: single-threaded (concurrency=1) withdraw baseline, no contention ---
  {
    const { latencies, wallMs, results } = await runConcurrent(200, 1, async (i) => {
      return timedFetch(`${BASE}/api/accounts/${source.id}/withdraw`, {
        method: "POST", headers: auth, body: JSON.stringify({ amount: "0.01" }),
      });
    });
    const failures = results.filter((r) => r.status !== 201).length;
    allResults.withdrawSingleThreaded = summarize(
      "B: POST /withdraw, concurrency=1 (no contention baseline)", latencies, wallMs,
      { failures }
    );
  }

  // --- Benchmark C: withdraw under contention on the SAME account, concurrency=20, 400 requests ---
  {
    const { latencies, wallMs, results } = await runConcurrent(400, 20, async (i) => {
      return timedFetch(`${BASE}/api/accounts/${source.id}/withdraw`, {
        method: "POST", headers: auth, body: JSON.stringify({ amount: "0.01" }),
      });
    });
    const statusCounts = {};
    for (const r of results) statusCounts[r.status] = (statusCounts[r.status] || 0) + 1;
    allResults.withdrawContended = summarize(
      "C: POST /withdraw, concurrency=20, SAME account (optimistic-lock contention)", latencies, wallMs,
      { statusCounts: JSON.stringify(statusCounts) }
    );
  }

  // --- Benchmark D: transfer under contention on the SAME source account, concurrency=20, 400 requests ---
  {
    const { latencies, wallMs, results } = await runConcurrent(400, 20, async (i) => {
      return timedFetch(`${BASE}/api/transfers`, {
        method: "POST", headers: auth,
        body: JSON.stringify({ fromAccountId: source.id, toAccountId: dest.id, amount: "0.01" }),
      });
    });
    const statusCounts = {};
    for (const r of results) statusCounts[r.status] = (statusCounts[r.status] || 0) + 1;
    allResults.transferContended = summarize(
      "D: POST /transfers, concurrency=20, SAME source account (optimistic-lock contention)", latencies, wallMs,
      { statusCounts: JSON.stringify(statusCounts) }
    );
  }

  // --- Benchmark E: idempotency burst -- 20 concurrent requests, SAME Idempotency-Key ---
  {
    const key = `bench-key-${Date.now()}`;
    const beforeRes = await timedFetch(`${BASE}/api/accounts/${dest.id}`, { headers: auth });
    const destBefore = JSON.parse(beforeRes.body).balance;

    const { latencies, wallMs, results } = await runConcurrent(20, 20, async () => {
      return timedFetch(`${BASE}/api/transfers`, {
        method: "POST", headers: { ...auth, "Idempotency-Key": key },
        body: JSON.stringify({ fromAccountId: source.id, toAccountId: dest.id, amount: "1.00" }),
      });
    });
    const statusCounts = {};
    for (const r of results) statusCounts[r.status] = (statusCounts[r.status] || 0) + 1;

    const afterRes = await timedFetch(`${BASE}/api/accounts/${dest.id}`, { headers: auth });
    const destAfter = JSON.parse(afterRes.body).balance;
    const delta = (parseFloat(destAfter) - parseFloat(destBefore)).toFixed(2);

    const winnerLatencies = results.filter(r => r.status === 201).map(r => r.ms);
    const otherLatencies = results.filter(r => r.status !== 201).map(r => r.ms);

    allResults.idempotencyBurst = summarize(
      "E: POST /transfers, 20 concurrent requests, SAME Idempotency-Key", latencies, wallMs,
      {
        statusCounts: JSON.stringify(statusCounts),
        destBalanceDelta: `$${delta} (expected $1.00 if exactly-once)`,
        winnerCount: winnerLatencies.length,
        winnerLatencyMs: winnerLatencies.map(x => x.toFixed(1)).join(","),
        otherLatenciesMs: otherLatencies.map(x => x.toFixed(1)).join(","),
      }
    );
  }

  // --- Reconciliation: recompute source/dest balances from the transaction log and compare to stored balance ---
  {
    async function fetchAllTransactions(accountId) {
      let page = 0;
      let all = [];
      while (true) {
        const res = await timedFetch(`${BASE}/api/accounts/${accountId}/transactions?page=${page}&size=100`, { headers: auth });
        const body = JSON.parse(res.body);
        all = all.concat(body.content);
        if (page >= body.totalPages - 1) break;
        page++;
      }
      return all;
    }

    const sourceTxns = await fetchAllTransactions(source.id);
    const destTxns = await fetchAllTransactions(dest.id);

    function reconcile(txns) {
      let balance = 0;
      for (const t of [...txns].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt))) {
        const amt = parseFloat(t.amount);
        if (t.type === "DEPOSIT" || t.type === "TRANSFER_IN") balance += amt;
        else balance -= amt;
      }
      return balance;
    }

    const sourceComputed = reconcile(sourceTxns).toFixed(2);
    const destComputed = reconcile(destTxns).toFixed(2);

    const sourceStored = JSON.parse((await timedFetch(`${BASE}/api/accounts/${source.id}`, { headers: auth })).body).balance;
    const destStored = JSON.parse((await timedFetch(`${BASE}/api/accounts/${dest.id}`, { headers: auth })).body).balance;

    console.log("\n=== RECONCILIATION ===");
    console.log(`source: ${sourceTxns.length} txns, computed=${sourceComputed}, stored=${sourceStored}, match=${sourceComputed === parseFloat(sourceStored).toFixed(2)}`);
    console.log(`dest:   ${destTxns.length} txns, computed=${destComputed}, stored=${destStored}, match=${destComputed === parseFloat(destStored).toFixed(2)}`);
    allResults.reconciliation = {
      sourceTxnCount: sourceTxns.length, sourceComputed, sourceStored,
      destTxnCount: destTxns.length, destComputed, destStored,
    };
  }

  console.log("\n\n=== RAW JSON SUMMARY ===");
  console.log(JSON.stringify(allResults, null, 2));
}

main().catch((e) => { console.error(e); process.exit(1); });
