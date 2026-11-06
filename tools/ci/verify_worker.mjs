/**
 * Worker 契约回归测试（CI 用，无网络依赖）
 *
 * 覆盖：
 *  1. /api/v1/rules 用 ECDSA P-256 签发，且签名可被独立验证
 *  2. 篡改 keyVerified 后不再通过验签
 *  3. 缺少 RULES_SIGNING_KEY 时 fail-closed（503）
 *  4. triggerSniff / triggerAlert 的鉴权与方法限制
 *  5. /api/v1/report 参数校验与按 IP 限流
 */
import { generateKeyPairSync, createVerify } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const workerPath = process.argv[2];
if (!workerPath) {
  console.error("usage: node verify_worker.mjs <worker.js>");
  process.exit(2);
}

const store = new Map();
const kv = {
  get: async (k, type) => {
    if (!store.has(k)) return null;
    const v = store.get(k);
    return type === "json" ? JSON.parse(v) : v;
  },
  put: async (k, v) => {
    store.set(k, String(v));
  }
};

const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const pkcs8 = privateKey.export({ type: "pkcs8", format: "der" }).toString("base64");
const spki = publicKey; // generateKeyPairSync 返回的已是 KeyObject

const mod = await import(pathToFileURL(workerPath).href);
const env = {
  RULES_SIGNING_KEY: pkcs8,
  ADMIN_TOKEN: "ci-token",
  RULES_KV: kv
};

let failures = 0;
const check = (label, cond, extra = "") => {
  console.log(`${cond ? "PASS" : "FAIL"} ${label}${extra ? " :: " + extra : ""}`);
  if (!cond) failures++;
};

const call = async (path, init = {}, e = env) => {
  const res = await mod.default.fetch(new Request(`https://cleanbus.example${path}`, init), e, {});
  const text = await res.text();
  return { status: res.status, text, headers: res.headers };
};

const canonicalJson = (v) => {
  if (v === null || typeof v !== "object") return JSON.stringify(v);
  if (Array.isArray(v)) return "[" + v.map(canonicalJson).join(",") + "]";
  return "{" + Object.keys(v).sort().map((k) => JSON.stringify(k) + ":" + canonicalJson(v[k])).join(",") + "}";
};

const verifySig = (rules) => {
  const payload = canonicalJson({
    chelaile: rules.chelaile,
    keyVerified: rules.keyVerified === true,
    mygolbs: rules.mygolbs,
    ruleVersion: rules.ruleVersion
  });
  const v = createVerify("sha256");
  v.update(payload, "utf8");
  v.end();
  return v.verify(spki, Buffer.from(rules.sig, "base64"));
};

// 1. 正常签发 + 验签
let r = await call("/api/v1/rules");
check("rules 200", r.status === 200, String(r.status));
const rules = JSON.parse(r.text);
check("rules 含 sig", typeof rules.sig === "string" && rules.sig.length > 0);
check("rules 签名可验证", verifySig(rules));

// 2. 篡改 keyVerified → 验签必须失败
const tampered = JSON.parse(JSON.stringify(rules));
tampered.keyVerified = !tampered.keyVerified;
check("篡改 keyVerified 后验签失败", verifySig(tampered) === false);

// 3. 缺私钥 fail-closed
const noKey = await call("/api/v1/rules", {}, { RULES_KV: kv });
check("缺 RULES_SIGNING_KEY → 503", noKey.status === 503, String(noKey.status));

// 4. 鉴权与方法限制
check("triggerSniff GET → 405", (await call("/api/v1/triggerSniff")).status === 405);
check("triggerSniff 无 token → 401", (await call("/api/v1/triggerSniff", { method: "POST" })).status === 401);
check("triggerAlert 无 token → 401", (await call("/api/v1/triggerAlert", { method: "POST" })).status === 401);

// 5. report 校验与限流
const reportBody = JSON.stringify({ source: "chelaile", kind: "decrypt-fail" });
const postReport = (ip) =>
  call("/api/v1/report", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
    body: reportBody
  });
check("report 合法 → 200", (await postReport("10.0.0.1")).status === 200);
check("report 同 IP 60s 内 → 204", (await postReport("10.0.0.1")).status === 204);
check(
  "report 非法 kind → 400",
  (await call("/api/v1/report", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.2" },
    body: JSON.stringify({ source: "chelaile", kind: "bogus" })
  })).status === 400
);
const status = JSON.parse((await call("/api/v1/status")).text);
check("status 暴露 chelaile 健康计数", status.sources?.chelaile?.health?.count === 1, JSON.stringify(status.sources?.chelaile?.health));

console.log(failures === 0 ? "\nALL WORKER CONTRACT TESTS PASSED" : `\n${failures} TEST(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);


