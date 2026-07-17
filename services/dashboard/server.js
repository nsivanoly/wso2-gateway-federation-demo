// Federation demo dashboard — API server.
// Builds a catalog of every API (from the WSO2 Publisher) with its gateway,
// original + gateway URLs, and proxies "try it" to both sides.
//
// Deploy model (important): the 6 "template" APIs are pushed DIRECTLY to the
// chosen gateway's runtime — Kong Admin API or the mock third-party gateway —
// WITHOUT any WSO2 ownership marker. WSO2's reverse federated-discovery then
// pulls them into the control plane within ~1 min. This is the discovery
// showcase: the dashboard never routes these deploys through WSO2 (doing so
// would tag them wso2-apim-managed and discovery would skip them).
// The WSO2-native gateway has no separate runtime, so "Deploy to WSO2" still
// goes through the Publisher (there is nothing to discover for the native gw).
const http = require('http');
const fs = require('fs');
const path = require('path');

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const PORT = process.env.PORT || 3000;
const CP = process.env.CONTROL_PLANE_URL || 'https://control-plane:9443';
const KONG_ADMIN = process.env.KONG_ADMIN_URL || 'http://kong:8001';
const TPGW = process.env.THIRD_PARTY_GW_URL || 'http://mock-gateway:8090';
const USER = process.env.WSO2_ADMIN_USER || 'admin';
const PASS = process.env.WSO2_ADMIN_PASSWORD || 'admin';
const PUB = `${CP}/api/am/publisher/v4`;

// Host-facing (browser-clickable) gateway URLs, overridable via env.
const PUBLIC_WSO2_GW = process.env.PUBLIC_WSO2_GW_URL || 'https://localhost:8243';
const PUBLIC_KONG = process.env.PUBLIC_KONG_URL || 'http://localhost:8000';
const PUBLIC_MOCK_GW = process.env.PUBLIC_MOCK_GW_URL || 'http://localhost:8090';

const GATEWAYS = {
  Default:    { label: 'WSO2 Gateway',        kind: 'native',     badge: 'wso2',      internal: 'https://control-plane:8243', external: PUBLIC_WSO2_GW, versioned: false, vendor: 'wso2',     type: 'wso2/synapse', vhost: 'localhost' },
  Kong:       { label: 'Kong Gateway',        kind: 'kong',       badge: 'KongLocal', internal: 'http://kong:8000',           external: PUBLIC_KONG,    versioned: true,  vendor: 'external', type: 'KongLocal',    vhost: 'kong' },
  ThirdParty: { label: 'Third-Party Gateway', kind: 'thirdparty', badge: 'HomeGrown', internal: 'http://mock-gateway:8090',    external: PUBLIC_MOCK_GW, versioned: true,  vendor: 'external', type: 'HomeGrown',    vhost: 'mock-gateway' },
};
// All APIs share the unified backend; map its in-cluster host to the host-exposed
// port so the dashboard can show a clickable "original" URL.
const BACKEND_PORTS = {
  'backend:8080': Number(process.env.BACKEND_PORT) || 4000,
};
const TEMPLATES = [
  { name: 'DepartmentAPI', context: '/department', collection: 'departments' },
  { name: 'PayrollAPI',    context: '/payroll',    collection: 'payrolls' },
  { name: 'FuelAPI',       context: '/fuel',       collection: 'fuels' },
  { name: 'TollAPI',       context: '/toll',       collection: 'tolls' },
  { name: 'TaxAPI',        context: '/tax',        collection: 'taxes' },
  { name: 'LicenseAPI',    context: '/license',    collection: 'licenses' },
];
const norm = (s) => (s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
const TEMPLATE_NORMS = new Set(TEMPLATES.map((t) => norm(t.name)));
const templateBackend = (ctx) => `http://backend:8080${ctx}`;
const templateResources = (col) => [
  { verb: 'GET', target: `/${col}` }, { verb: 'POST', target: `/${col}` }, { verb: 'GET', target: `/${col}/{id}` },
];

function inferEnv(vendor, type) {
  if ((type || '').includes('KongLocal')) return 'Kong';
  if ((type || '').includes('HomeGrown')) return 'ThirdParty';
  return 'Default';
}
function displayBackend(u) {
  if (!u) return u;
  for (const [hp, p] of Object.entries(BACKEND_PORTS)) if (u.includes(hp)) return u.replace('http://' + hp, `http://localhost:${p}`);
  return u;
}

// ---- WSO2 auth ----
let token = null;
async function register() {
  const r = await fetch(`${CP}/client-registration/v0.17/register`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Basic ' + Buffer.from(`${USER}:${PASS}`).toString('base64') },
    body: JSON.stringify({ callbackUrl: 'https://localhost', clientName: 'dashboard', owner: USER, grantType: 'password refresh_token', saasApp: true }),
  });
  return r.json();
}
async function getToken(force) {
  if (token && !force) return token;
  const { clientId, clientSecret } = await register();
  const scope = 'apim:api_view apim:api_create apim:api_publish apim:api_manage apim:api_delete apim:api_import_export apim:admin';
  const r = await fetch(`${CP}/oauth2/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: 'Basic ' + Buffer.from(`${clientId}:${clientSecret}`).toString('base64') },
    body: new URLSearchParams({ grant_type: 'password', username: USER, password: PASS, scope }),
  });
  token = (await r.json()).access_token; return token;
}
async function wso2(pq, opts = {}) {
  const call = (t) => fetch(`${PUB}${pq}`, { ...opts, headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...(opts.headers || {}) } });
  let r = await call(await getToken());
  if (r.status === 401) r = await call(await getToken(true));
  return r;
}

// ---- live gateway runtimes -------------------------------------------------
// Read what is actually deployed on each federated gateway so the console can
// show gateway-native APIs BEFORE WSO2 discovery imports them, and tell apart
// "pushed by WSO2" (has the connector's managed marker) from "deployed directly".
const ctxNorm = (c) => norm(String(c || '').replace(/\/v\d+$/i, '')); // drop trailing /v1 then normalize
const firstSeg = (p) => '/' + (String(p || '').split('/').filter(Boolean).filter((s) => !/^v\d+$/i.test(s))[0] || '');

async function kongLiveRoutes() {
  const out = [];
  try {
    const svcs = (await (await fetch(`${KONG_ADMIN}/services`)).json()).data || [];
    // Fetch every service's routes concurrently.
    const perSvc = await Promise.all(svcs.map(async (s) => {
      const routes = (await (await fetch(`${KONG_ADMIN}/services/${s.id}/routes`)).json()).data || [];
      // Connector-pushed services carry the wso2-apim-managed tag; direct deploys
      // (e.g. from this dashboard) do not. The tag is authoritative — do NOT infer
      // from the route name (the dashboard names its direct routes "<name>-ext-route").
      return routes.map((r) => ({
        gateway: 'Kong', name: s.name, context: firstSeg((r.paths || [])[0]),
        managed: [...(s.tags || []), ...(r.tags || [])].includes('wso2-apim-managed'),
      }));
    }));
    for (const rs of perSvc) out.push(...rs);
  } catch { /* Kong unreachable */ }
  return out;
}
async function mockLiveRoutes() {
  const out = [];
  try {
    const routes = (await (await fetch(`${TPGW}/mock/registry`)).json()).routes || [];
    for (const r of routes) out.push({ gateway: 'ThirdParty', name: r.name, context: firstSeg(r.context), managed: !!r.managedBy });
  } catch { /* mock gw unreachable */ }
  return out;
}

// ---- catalog ----
async function buildCatalog() {
  const list = (await (await wso2('/apis?limit=100')).json()).list || [];
  const wsoNorms = new Set(list.map((it) => norm(it.name)));

  // Snapshot the live gateway routes and index them by normalized context/name.
  // Kong + mock reads run concurrently.
  const [kongR, mockR] = await Promise.all([kongLiveRoutes(), mockLiveRoutes()]);
  const gwRoutes = [...kongR, ...mockR];
  const routeMatched = new Set();
  const findRoute = (name, context) => {
    const cn = ctxNorm(context), nn = norm(name);
    return gwRoutes.find((r) => ctxNorm(r.context) === cn || norm(r.name) === nn);
  };

  // Per-API detail + deployments fetched concurrently (was N sequential pairs).
  const rows = await Promise.all(list.map(async (it) => {
    const [detail, deployments] = await Promise.all([
      wso2(`/apis/${it.id}`).then((r) => r.json()).catch(() => ({})),
      wso2(`/apis/${it.id}/deployments`).then((r) => r.json()).then((d) => (Array.isArray(d) ? d : (d.list || []))).catch(() => []),
    ]);
    const vendor = detail.gatewayVendor || it.gatewayVendor, type = detail.gatewayType || it.gatewayType;
    const envKey = (deployments.length ? deployments[0].name : null) || inferEnv(vendor, type);
    const gw = GATEWAYS[envKey] || GATEWAYS.Default;
    let backendInternal = null; try { backendInternal = (detail.endpointConfig || {}).production_endpoints?.url; } catch {}
    const ops = (detail.operations || []).map((o) => ({ verb: o.verb || o.httpVerb, target: o.target }));
    const primary = ops.find((o) => (o.verb || '').toUpperCase() === 'GET' && !/\{/.test(o.target)) || ops[0] || { verb: 'GET', target: '/' };
    // Match against a live gateway route (external gateways only).
    const external = envKey !== 'Default';
    const deployed = deployments.length > 0;
    const route = external ? findRoute(it.name, it.context) : null;
    if (route) routeMatched.add(route);
    // Federation direction for an external-gateway API:
    //   discovered = on the gateway WITHOUT the connector's managed marker
    //                (it originated on the gateway; reverse-discovery pulled it in)
    //   pushed     = any other deployed external API (WSO2 deployed it to the gateway)
    const discovered = !!route && !route.managed;
    const pushed = external && deployed && !discovered;
    return {
      id: it.id, name: it.name, version: it.version, context: it.context,
      deployed,
      isTemplate: TEMPLATE_NORMS.has(norm(it.name)),
      discovered, pushed,
      gateway: { key: envKey, label: gw.label, badge: gw.badge, kind: gw.kind },
      backend: { display: displayBackend(backendInternal), internal: backendInternal },
      gatewayBase: { display: gw.external + it.context + (gw.versioned ? '/v1' : '') },
      resources: ops, primary,
    };
  }));
  const out = [...rows];

  // Gateway-native routes not (yet) in WSO2: show them immediately as awaiting
  // discovery, so a direct deploy is visible before the next discovery cycle.
  for (const r of gwRoutes) {
    if (routeMatched.has(r) || r.managed) continue;      // already shown, or WSO2-pushed
    if ([...out].some((a) => ctxNorm(a.context) === ctxNorm(r.context) || norm(a.name) === norm(r.name))) continue;
    const gw = GATEWAYS[r.gateway] || GATEWAYS.Default;
    out.push({
      id: null, name: r.name, version: 'v1', context: r.context,
      deployed: true, isTemplate: TEMPLATE_NORMS.has(norm(r.name)), awaitingDiscovery: true, discovered: false,
      gateway: { key: r.gateway, label: gw.label, badge: gw.badge, kind: gw.kind },
      backend: { display: null, internal: null },
      gatewayBase: { display: gw.external + r.context + (gw.versioned ? '/v1' : '') },
      resources: [], primary: { verb: 'GET', target: '/' },
    });
  }

  for (const t of TEMPLATES) {
    if (wsoNorms.has(norm(t.name))) continue;                              // already in WSO2
    if (gwRoutes.some((r) => ctxNorm(r.context) === ctxNorm(t.context))) continue; // already on a gateway (shown above)
    out.push({
      id: null, name: t.name, version: 'v1', context: t.context, deployed: false, isTemplate: true, pending: true,
      gateway: null, backend: { display: displayBackend(templateBackend(t.context)), internal: templateBackend(t.context) },
      gatewayBase: null, resources: templateResources(t.collection), primary: { verb: 'GET', target: `/${t.collection}` },
    });
  }
  const order = { Default: 0, Kong: 1, ThirdParty: 2 };
  out.sort((a, b) => ((a.gateway ? order[a.gateway.key] : 9) - (b.gateway ? order[b.gateway.key] : 9)) || a.name.localeCompare(b.name));
  return out;
}

// ---- try it ----
async function tryUrl(url, method) {
  const t0 = Date.now();
  try {
    const r = await fetch(url, { method: method || 'GET', headers: { Accept: 'application/json' } });
    const text = await r.text(); let body; try { body = JSON.parse(text); } catch { body = text; }
    return { ok: r.ok, status: r.status, ms: Date.now() - t0, url, body };
  } catch (e) { return { ok: false, status: 0, ms: Date.now() - t0, url, error: String(e.message || e) }; }
}

// ---- DIRECT deploy to a gateway runtime (no WSO2 marker => discoverable) ----
async function deployKongDirect(t) {
  // create Service (no wso2-apim-managed tag) + Route (path is versioned like the
  // connector's; NOT named "<svc>-route" so discovery won't treat it as connector-owned)
  const svcBody = { name: t.name, url: templateBackend(t.context) };
  const s = await fetch(`${KONG_ADMIN}/services`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(svcBody) });
  const svc = await s.json();
  if (!svc.id) return { ok: false, error: 'kong service create failed', detail: svc };
  // Route MUST have a name (the connector's discovery reads it), but NOT
  // "<service>-route" — that's the pattern KongLocal treats as connector-managed
  // and would skip during discovery. Use an "-ext-route" suffix instead.
  const routeBody = { name: `${t.name}-ext-route`, paths: [`${t.context}/v1`], strip_path: false, methods: ['GET', 'POST', 'DELETE'] };
  const r = await fetch(`${KONG_ADMIN}/services/${svc.id}/routes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(routeBody) });
  return { ok: r.ok, via: 'kong-admin', discover: true };
}
async function deployMockDirect(t) {
  // push to the mock gateway registry WITHOUT managedBy so HomeGrown discovery imports it
  const body = { name: t.name, version: 'v1', context: `${t.context}/v1`, backendUrl: templateBackend(t.context), resources: templateResources(t.collection) };
  const r = await fetch(`${TPGW}/mock/deploy`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  return { ok: r.ok, via: 'mock-gateway', discover: true };
}
// WSO2 native: create + deploy through the Publisher (no discovery for the native gw)
async function deployNativeViaWSO2(t) {
  const body = {
    name: t.name, context: t.context, version: 'v1', gatewayVendor: 'wso2', gatewayType: 'wso2/synapse',
    endpointConfig: { endpoint_type: 'http', production_endpoints: { url: templateBackend(t.context) } },
    policies: ['Unlimited'],
    operations: templateResources(t.collection).map((o) => ({ ...o, authType: 'None', throttlingPolicy: 'Unlimited' })),
  };
  const created = await (await wso2('/apis', { method: 'POST', body: JSON.stringify(body) })).json();
  if (!created.id) return { ok: false, error: 'create failed', detail: created };
  const api = await (await wso2(`/apis/${created.id}`)).json();
  await wso2(`/apis/${created.id}`, { method: 'PUT', body: JSON.stringify({ ...api, isDefaultVersion: true, securityScheme: ['oauth2'], operations: (api.operations || []).map((o) => ({ ...o, authType: 'None' })) }) });
  const rev = await (await wso2(`/apis/${created.id}/revisions`, { method: 'POST', body: JSON.stringify({ description: 'from-dashboard' }) })).json();
  await wso2(`/apis/${created.id}/deploy-revision?revisionId=${rev.id}`, { method: 'POST', body: JSON.stringify([{ name: 'Default', vhost: 'localhost', displayOnDevportal: true }]) });
  await wso2(`/apis/change-lifecycle?apiId=${created.id}&action=Publish`, { method: 'POST' });
  return { ok: true, via: 'wso2', discover: false };
}
async function deployTemplate(name, gatewayKey) {
  const t = TEMPLATES.find((x) => x.name === name);
  if (!t || !GATEWAYS[gatewayKey]) return { ok: false, error: 'unknown template/gateway' };
  if (gatewayKey === 'Kong') return { ...(await deployKongDirect(t)), env: 'Kong' };
  if (gatewayKey === 'ThirdParty') return { ...(await deployMockDirect(t)), env: 'ThirdParty' };
  return { ...(await deployNativeViaWSO2(t)), env: 'Default' };
}

// ---- remove: delete from every gateway runtime AND from WSO2 (so it doesn't re-discover) ----
async function removeByName(name) {
  const t = TEMPLATES.find((x) => x.name === name);
  if (!t) return { ok: false, error: 'not a template API' };
  const done = [];
  // Kong: delete service(s) named after the template + their routes
  try {
    const svcs = (await (await fetch(`${KONG_ADMIN}/services`)).json()).data || [];
    for (const s of svcs) {
      if (norm(s.name) !== norm(t.name)) continue;
      const routes = (await (await fetch(`${KONG_ADMIN}/services/${s.id}/routes`)).json()).data || [];
      for (const r of routes) await fetch(`${KONG_ADMIN}/routes/${r.id}`, { method: 'DELETE' });
      await fetch(`${KONG_ADMIN}/services/${s.id}`, { method: 'DELETE' });
      done.push('kong');
    }
  } catch {}
  // Mock gateway: undeploy by context (versioned) and by name
  try {
    await fetch(`${TPGW}/mock/undeploy`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ context: `${t.context}/v1` }) });
    await fetch(`${TPGW}/mock/undeploy`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: t.name }) });
    done.push('mock');
  } catch {}
  // WSO2: delete the API (discovered or native) so it doesn't linger / re-appear
  try {
    const list = (await (await wso2(`/apis?query=name:${encodeURIComponent(t.name)}`)).json()).list || [];
    for (const a of list) if (norm(a.name) === norm(t.name)) { await wso2(`/apis/${a.id}`, { method: 'DELETE' }); done.push('wso2'); }
  } catch {}
  return { ok: true, name: t.name, removedFrom: done };
}

// ---- HTTP ----
function json(res, c, b) { res.writeHead(c, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(b)); }
function readBody(req) { return new Promise((r) => { let b = ''; req.on('data', (c) => (b += c)); req.on('end', () => { try { r(b ? JSON.parse(b) : {}); } catch { r({}); } }); }); }
const PUBLIC = path.join(__dirname, 'public');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

const server = http.createServer(async (req, res) => {
  const url = req.url.split('?')[0];
  try {
    if (url === '/api/health') return json(res, 200, { status: 'UP' });
    if (url === '/api/catalog') return json(res, 200, { apis: await buildCatalog() });
    if (url === '/api/tryout' && req.method === 'POST') {
      const b = await readBody(req);
      const apis = await buildCatalog();
      const api = apis.find((a) => a.id === b.apiId) || apis.find((a) => a.name === b.name);
      if (!api) return json(res, 404, { error: 'api not found' });
      const t = b.target || (api.primary && api.primary.target) || '/';
      let u;
      if (b.side === 'gateway') { const g = GATEWAYS[api.gateway.key]; u = g.internal + api.context + (g.versioned ? '/v1' : '') + t; }
      else u = (api.backend.internal || '') + t;
      return json(res, 200, await tryUrl(u, b.method));
    }
    if (url === '/api/deploy' && req.method === 'POST') {
      const b = await readBody(req);
      if (b.name && b.gateway) return json(res, 200, await deployTemplate(b.name, b.gateway));
      return json(res, 400, { error: 'need {name,gateway}' });
    }
    if (url === '/api/remove' && req.method === 'POST') {
      const b = await readBody(req);
      return json(res, 200, await removeByName(b.name));
    }
    let f = url === '/' ? '/index.html' : url;
    const fp = path.join(PUBLIC, path.normalize(f).replace(/^(\.\.[/\\])+/, ''));
    if (fp.startsWith(PUBLIC) && fs.existsSync(fp) && fs.statSync(fp).isFile()) {
      res.writeHead(200, { 'Content-Type': MIME[path.extname(fp)] || 'application/octet-stream' });
      return fs.createReadStream(fp).pipe(res);
    }
    json(res, 404, { error: 'not found' });
  } catch (e) { json(res, 500, { error: String(e.message || e) }); }
});
server.listen(PORT, () => console.log(`dashboard listening on :${PORT}`));
