// Mock third-party API gateway.
// Represents any non-WSO2 gateway (NGINX, APISIX, Apigee, ...).
// Exposes a proprietary "deploy" API and a route registry, then proxies
// runtime traffic for whatever routes have been deployed to it.
const http = require('http');

const PORT = process.env.PORT || 8090;
const STARTED_AT = new Date().toISOString();

// ---- WSO2 control-plane cross-check ----------------------------------------
// So the console can show the TRUE state of a directly-deployed route: has the
// control plane's reverse discovery imported it yet? We query the Publisher
// API list and match by normalized name/context.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const CP = process.env.CONTROL_PLANE_URL || 'https://control-plane:9443';
const CP_USER = process.env.WSO2_ADMIN_USER || 'admin';
const CP_PASS = process.env.WSO2_ADMIN_PASSWORD || 'admin';
const PUB = `${CP}/api/am/publisher/v4`;
const norm = (s) => (s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
// strip a trailing version segment (…/v1) before normalizing a context
const ctxNorm = (c) => norm((c || '').replace(/\/v\d+$/i, ''));

let cpToken = null;
async function cpGetToken(force) {
  if (cpToken && !force) return cpToken;
  const basic = 'Basic ' + Buffer.from(`${CP_USER}:${CP_PASS}`).toString('base64');
  const reg = await (await fetch(`${CP}/client-registration/v0.17/register`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: basic },
    body: JSON.stringify({ callbackUrl: 'https://localhost', clientName: 'homegrown-console', owner: CP_USER, grantType: 'password refresh_token', saasApp: true }),
  })).json();
  const t = await (await fetch(`${CP}/oauth2/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: 'Basic ' + Buffer.from(`${reg.clientId}:${reg.clientSecret}`).toString('base64') },
    body: new URLSearchParams({ grant_type: 'password', username: CP_USER, password: CP_PASS, scope: 'apim:api_view' }),
  })).json();
  cpToken = t.access_token; return cpToken;
}
// cached set of {name,context} tokens known to the control plane (5s TTL)
let cpCache = { at: 0, tokens: new Set() };
async function cpKnownTokens() {
  if (Date.now() - cpCache.at < 5000) return cpCache.tokens;
  const tokens = new Set();
  try {
    const call = (tok) => fetch(`${PUB}/apis?limit=100`, { headers: { Authorization: `Bearer ${tok}` } });
    let r = await call(await cpGetToken());
    if (r.status === 401) r = await call(await cpGetToken(true));
    const list = (await r.json()).list || [];
    for (const it of list) { if (it.name) tokens.add(norm(it.name)); if (it.context) tokens.add(ctxNorm(it.context)); }
    cpCache = { at: Date.now(), tokens };
  } catch { /* control plane unreachable — leave whatever we had */ }
  return cpCache.tokens;
}
// Classify a route's federation state for the UI.
async function routeState(entry) {
  if (entry.managedBy) return 'pushed';           // deployed BY the WSO2 connector
  const known = await cpKnownTokens();
  const hit = known.has(norm(entry.name)) || known.has(ctxNorm(entry.context));
  return hit ? 'discovered' : 'pending';           // direct deploy: has WSO2 discovered it?
}
// registry: context -> { name, version, backendUrl, resources, deployedAt }
const registry = new Map();
let deployCount = 0;

function send(res, code, body) {
  const data = JSON.stringify(body, null, 2);
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(data);
}
function readBody(req) {
  return new Promise((resolve) => {
    let b = '';
    req.on('data', (c) => (b += c));
    req.on('end', () => { try { resolve(b ? JSON.parse(b) : {}); } catch { resolve({}); } });
  });
}

// Proxy an incoming runtime request to the registered backend, forwarding the
// full path (the backend matches its resource as a suffix).
function proxy(entry, req, res, url) {
  const target = new URL(entry.backendUrl.replace(/\/+$/, '') + url);
  const opts = {
    hostname: target.hostname,
    port: target.port || 80,
    path: target.pathname + target.search,
    method: req.method,
    headers: { ...req.headers, host: target.host },
  };
  const upstream = http.request(opts, (r) => {
    res.writeHead(r.statusCode, { ...r.headers, 'x-gateway': 'mock-third-party' });
    r.pipe(res);
  });
  upstream.on('error', (e) =>
    send(res, 502, { error: 'bad gateway', detail: e.message, target: target.href }));
  req.pipe(upstream);
}

function sendHtml(res, code, html) {
  res.writeHead(code, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(html);
}

const server = http.createServer(async (req, res) => {
  const url = (req.url.split('?')[0].replace(/\/+$/, '')) || '/';

  if (url === '/health') return send(res, 200, { status: 'UP', gateway: 'mock-third-party', routes: registry.size });

  // ---- Home-grown gateway admin console (HTML UI) ----
  if ((url === '/' || url === '/ui' || url === '/console') && req.method === 'GET')
    return sendHtml(res, 200, UI_HTML);

  // JSON feed the UI polls for live state. Each route is annotated with its
  // federation state by cross-checking the WSO2 control plane.
  if (url === '/mock/status' && req.method === 'GET') {
    const routes = [];
    for (const entry of registry.values())
      routes.push({ ...entry, state: await routeState(entry) });
    return send(res, 200, {
      gateway: 'HomeGrown Gateway',
      version: '1.4.0',
      status: 'UP',
      startedAt: STARTED_AT,
      deployCount,
      routeCount: registry.size,
      routes,
    });
  }

  if (url === '/mock/registry' && req.method === 'GET')
    return send(res, 200, { count: registry.size, routes: [...registry.values()] });

  if (url === '/mock/deploy' && req.method === 'POST') {
    const body = await readBody(req);
    if (!body.context || !body.backendUrl)
      return send(res, 400, { error: 'context and backendUrl required' });
    const entry = {
      name: body.name || body.context,
      version: body.version || 'v1',
      context: body.context.startsWith('/') ? body.context : '/' + body.context,
      backendUrl: body.backendUrl,
      resources: body.resources || [],
      deployedAt: new Date().toISOString(),
    };
    // Preserve the connector's ownership marker so reverse-discovery can tell
    // connector-deployed routes from genuinely external ones (avoids a sync loop).
    if (body.managedBy) entry.managedBy = body.managedBy;
    if (body.published !== undefined) entry.published = body.published;
    registry.set(entry.context, entry);
    deployCount++;
    console.log(`\n=== [mock-gw] DEPLOY #${deployCount} ===`);
    console.log(`    API      : ${entry.name} (${entry.version})`);
    console.log(`    Context  : ${entry.context}`);
    console.log(`    Backend  : ${entry.backendUrl}`);
    console.log(`    Resources: ${entry.resources.map((r) => r.verb + ' ' + r.target).join(', ') || '(none)'}`);
    console.log(`    -> route is now LIVE on the mock gateway`);
    return send(res, 200, { status: 'deployed', route: entry });
  }

  if (url === '/mock/undeploy' && req.method === 'POST') {
    const body = await readBody(req);
    const ctx = body.context ? (body.context.startsWith('/') ? body.context : '/' + body.context) : null;
    let removed = 0;
    if (ctx && registry.has(ctx)) { registry.delete(ctx); removed = 1; }
    else if (body.name) { for (const [k, v] of registry) if (v.name === body.name) { registry.delete(k); removed++; } }
    console.log(`[mock-gw] UNDEPLOY context=${ctx || body.name} -> removed ${removed} route(s)`);
    return send(res, 200, { status: 'undeployed', removed });
  }

  // Runtime traffic: find a deployed route whose context prefixes the URL.
  for (const entry of registry.values()) {
    if (url === entry.context || url.startsWith(entry.context + '/')) {
      return proxy(entry, req, res, url);
    }
  }
  return send(res, 404, { error: 'no route deployed for this path on the mock gateway', path: url });
});

server.listen(PORT, () => console.log(`mock third-party gateway listening on :${PORT}`));

// ---------------------------------------------------------------------------
// Home-grown gateway admin console. Self-contained (no build, no external
// assets) so it works offline inside the demo network. Polls /mock/status.
// ---------------------------------------------------------------------------
const UI_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>HomeGrown Gateway — Admin Console</title>
<style>
  :root{
    --bg:#0b0f14; --panel:#131a23; --panel2:#0f151c; --border:#22303d;
    --txt:#e6edf3; --muted:#8497a8; --accent:#7c5cff; --accent2:#a78bfa;
    --ok:#2ecc71; --warn:#f5a623; --chip:#1c2733;
    --mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
  }
  *{box-sizing:border-box}
  body{margin:0;background:radial-gradient(1200px 600px at 80% -10%,#1a1030 0%,var(--bg) 55%);
    color:var(--txt);font:14px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;min-height:100vh}
  header{display:flex;align-items:center;gap:16px;padding:20px 28px;border-bottom:1px solid var(--border);
    background:linear-gradient(90deg,rgba(124,92,255,.12),transparent)}
  .logo{width:44px;height:44px;border-radius:12px;display:grid;place-items:center;font-size:22px;
    background:linear-gradient(135deg,var(--accent),#4c2fb3);box-shadow:0 6px 20px rgba(124,92,255,.4)}
  header h1{font-size:18px;margin:0;letter-spacing:.3px}
  header .sub{color:var(--muted);font-size:12px;margin-top:2px}
  .badge{margin-left:auto;display:flex;align-items:center;gap:8px;font-size:12px;color:var(--muted)}
  .dot{width:9px;height:9px;border-radius:50%;background:var(--ok);box-shadow:0 0 10px var(--ok)}
  main{max-width:1080px;margin:0 auto;padding:28px}
  .stats{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:28px}
  .stat{background:var(--panel);border:1px solid var(--border);border-radius:14px;padding:18px 20px}
  .stat .k{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.6px}
  .stat .v{font-size:28px;font-weight:700;margin-top:6px}
  .stat .v small{font-size:13px;color:var(--muted);font-weight:400}
  .section-title{display:flex;align-items:center;gap:10px;margin:0 0 14px;font-size:15px}
  .section-title .count{background:var(--chip);color:var(--muted);border-radius:20px;padding:2px 10px;font-size:12px}
  .routes{display:grid;gap:14px}
  .route{background:var(--panel);border:1px solid var(--border);border-radius:14px;padding:18px 20px;
    transition:border-color .15s,transform .15s}
  .route:hover{border-color:var(--accent);transform:translateY(-1px)}
  .route .top{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
  .route .name{font-weight:700;font-size:16px}
  .ver{font-family:var(--mono);font-size:11px;background:var(--chip);border:1px solid var(--border);
    border-radius:6px;padding:1px 7px;color:var(--accent2)}
  .managed{margin-left:auto;font-size:11px;padding:3px 10px;border-radius:20px;white-space:nowrap}
  .managed.pushed{background:rgba(124,92,255,.15);color:var(--accent2);border:1px solid rgba(124,92,255,.4)}
  .managed.discovered{background:rgba(78,161,255,.14);color:#7bb8ff;border:1px solid rgba(78,161,255,.4)}
  .managed.pending{background:rgba(245,166,35,.12);color:var(--warn);border:1px solid rgba(245,166,35,.4)}
  .ctx{font-family:var(--mono);color:var(--muted);font-size:13px;margin-top:8px}
  .ctx b{color:var(--txt)}
  .verbs{display:flex;gap:6px;flex-wrap:wrap;margin-top:12px}
  .verb{font-family:var(--mono);font-size:11px;padding:2px 9px;border-radius:6px;border:1px solid var(--border);background:var(--panel2)}
  .verb.GET{color:#4ea1ff} .verb.POST{color:#2ecc71} .verb.DELETE{color:#ff6b6b} .verb.PUT{color:#f5a623}
  .meta{color:var(--muted);font-size:11px;margin-top:10px;font-family:var(--mono)}
  .empty{background:var(--panel);border:1px dashed var(--border);border-radius:14px;padding:40px;
    text-align:center;color:var(--muted)}
  .btn{cursor:pointer;border:none;border-radius:9px;font-size:13px;font-weight:600;padding:9px 16px;
    background:linear-gradient(135deg,var(--accent),#4c2fb3);color:#fff;box-shadow:0 4px 14px rgba(124,92,255,.35)}
  .btn:hover{filter:brightness(1.1)} .btn.ghost{background:var(--chip);color:var(--txt);box-shadow:none;border:1px solid var(--border)}
  .btn:disabled{opacity:.5;cursor:default}
  .toolbar{display:flex;justify-content:flex-end;margin-bottom:16px}
  .overlay{position:fixed;inset:0;background:rgba(4,7,10,.72);backdrop-filter:blur(3px);display:none;
    align-items:flex-start;justify-content:center;padding-top:8vh;z-index:20}
  .overlay.show{display:flex}
  .modal{background:var(--panel);border:1px solid var(--border);border-radius:16px;width:min(520px,92vw);
    padding:24px 26px;box-shadow:0 30px 80px rgba(0,0,0,.6)}
  .modal h2{margin:0 0 4px;font-size:17px} .modal .hint{color:var(--muted);font-size:12px;margin-bottom:18px}
  .field{margin-bottom:14px} .field label{display:block;font-size:12px;color:var(--muted);margin-bottom:5px}
  .field input,.field select,.field textarea{width:100%;background:var(--panel2);border:1px solid var(--border);
    border-radius:9px;color:var(--txt);padding:9px 11px;font:13px var(--mono)}
  .field input:focus,.field textarea:focus{outline:none;border-color:var(--accent)}
  .row2{display:grid;grid-template-columns:1fr 1fr;gap:12px}
  .modal .actions{display:flex;justify-content:flex-end;gap:10px;margin-top:20px}
  .msg{font-size:12px;margin-top:12px;min-height:16px}
  .msg.ok{color:var(--ok)} .msg.err{color:#ff6b6b}
  .presets{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:14px}
  .preset{cursor:pointer;font-size:11px;padding:3px 9px;border-radius:6px;background:var(--chip);border:1px solid var(--border);color:var(--muted)}
  .preset:hover{border-color:var(--accent);color:var(--txt)}
  footer{max-width:1080px;margin:0 auto;padding:0 28px 40px;color:var(--muted);font-size:12px}
  a{color:var(--accent2)}
</style>
</head>
<body>
<header>
  <div class="logo">🛰️</div>
  <div>
    <h1>HomeGrown Gateway</h1>
    <div class="sub">Home-grown API runtime · federated by WSO2 API Platform</div>
  </div>
  <div class="badge"><span class="dot"></span> <span id="status">connecting…</span></div>
</header>
<main>
  <div class="stats">
    <div class="stat"><div class="k">Runtime Status</div><div class="v" style="color:var(--ok)">● Live</div></div>
    <div class="stat"><div class="k">Active Routes</div><div class="v" id="s-routes">–</div></div>
    <div class="stat"><div class="k">Total Deploys</div><div class="v" id="s-deploys">–</div></div>
    <div class="stat"><div class="k">Version</div><div class="v" id="s-ver">–</div></div>
  </div>

  <div class="toolbar"><button class="btn" onclick="openModal()">＋ Add / Import API</button></div>

  <div class="section-title">Deployed Routes <span class="count" id="rc">0</span></div>
  <div class="routes" id="routes"></div>
</main>

<div class="overlay" id="overlay">
  <div class="modal">
    <h2>Add / Import API</h2>
    <div class="hint">Deploys directly onto this gateway. No WSO2 marker is written, so the control plane's federated discovery will import it automatically.</div>
    <div class="presets" id="presets"></div>
    <div class="field"><label>API Name</label><input id="f-name" placeholder="OrdersAPI"/></div>
    <div class="row2">
      <div class="field"><label>Base Path / Context</label><input id="f-ctx" placeholder="/orders"/></div>
      <div class="field"><label>Version</label><input id="f-ver" value="v1"/></div>
    </div>
    <div class="field"><label>Backend URL</label><input id="f-backend" placeholder="http://backend:8080/orders"/></div>
    <div class="field"><label>Resources <span style="color:var(--muted)">(one per line: <code>VERB /path</code>)</span></label>
      <textarea id="f-res" rows="3" placeholder="GET /orders&#10;POST /orders&#10;GET /orders/{id}"></textarea></div>
    <div class="msg" id="f-msg"></div>
    <div class="actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn" id="f-submit" onclick="submitApi()">Deploy to Gateway</button>
    </div>
  </div>
</div>
<footer>
  Auto-refreshes every 4s · Runtime API: <code>/mock/status</code> · Deploy: <code>POST /mock/deploy</code> · Uptime since <span id="since">–</span>
</footer>
<script>
  const verbClass=v=>['GET','POST','PUT','DELETE'].includes(v)?v:'GET';
  function timeAgo(iso){const s=(Date.now()-new Date(iso))/1000;
    if(s<60)return Math.floor(s)+'s ago';if(s<3600)return Math.floor(s/60)+'m ago';
    if(s<86400)return Math.floor(s/3600)+'h ago';return Math.floor(s/86400)+'d ago';}
  async function tick(){
    try{
      const r=await fetch('/mock/status');const d=await r.json();
      document.getElementById('status').textContent='HomeGrown '+d.version+' · UP';
      document.getElementById('s-routes').textContent=d.routeCount;
      document.getElementById('s-deploys').textContent=d.deployCount;
      document.getElementById('s-ver').innerHTML=d.version+' <small>runtime</small>';
      document.getElementById('rc').textContent=d.routeCount;
      document.getElementById('since').textContent=new Date(d.startedAt).toLocaleString();
      const box=document.getElementById('routes');
      if(!d.routes.length){box.innerHTML='<div class="empty">No routes deployed yet.<br/>Deploy an API from the WSO2 Control Plane and it will appear here.</div>';return;}
      box.innerHTML=d.routes.map(rt=>{
        const STATE={pushed:['pushed','⇩ pushed by WSO2'],discovered:['discovered','direct / discovered'],pending:['pending','direct / pending']};
        const st=STATE[rt.state]||STATE.pending;
        const managed='<span class="managed '+st[0]+'">'+st[1]+'</span>';
        const verbs=(rt.resources||[]).map(x=>'<span class="verb '+verbClass(x.verb)+'">'+x.verb+' '+x.target+'</span>').join('')||'<span class="meta">no resources declared</span>';
        return '<div class="route"><div class="top"><span class="name">'+rt.name+'</span><span class="ver">'+rt.version+'</span>'+managed+'</div>'
          +'<div class="ctx">route <b>'+rt.context+'</b> &nbsp;→&nbsp; '+rt.backendUrl+'</div>'
          +'<div class="verbs">'+verbs+'</div>'
          +'<div class="meta">deployed '+timeAgo(rt.deployedAt)+'</div></div>';
      }).join('');
    }catch(e){document.getElementById('status').textContent='unreachable';}
  }
  tick();setInterval(tick,4000);

  // ---- Add / Import API modal ----
  const PRESETS=[
    {name:'OrdersAPI',ctx:'/orders',backend:'http://backend:8080/orders',res:'GET /orders\\nPOST /orders\\nGET /orders/{id}'},
    {name:'InvoiceAPI',ctx:'/invoice',backend:'http://backend:8080/invoice',res:'GET /invoices\\nPOST /invoices'},
    {name:'PermitAPI',ctx:'/permit',backend:'http://backend:8080/permit',res:'GET /permits\\nGET /permits/{id}'},
  ];
  const $=id=>document.getElementById(id);
  function renderPresets(){
    $('presets').innerHTML='<span style="color:var(--muted);font-size:11px;align-self:center">quick fill:</span>'+
      PRESETS.map((p,i)=>'<span class="preset" onclick="fillPreset('+i+')">'+p.name+'</span>').join('');
  }
  window.fillPreset=i=>{const p=PRESETS[i];$('f-name').value=p.name;$('f-ctx').value=p.ctx;$('f-ver').value='v1';
    $('f-backend').value=p.backend;$('f-res').value=p.res.replace(/\\\\n/g,'\\n');};
  window.openModal=()=>{$('f-msg').textContent='';$('overlay').classList.add('show');renderPresets();};
  window.closeModal=()=>$('overlay').classList.remove('show');
  $('overlay').addEventListener('click',e=>{if(e.target===$('overlay'))closeModal();});
  window.submitApi=async()=>{
    const name=$('f-name').value.trim(),ctxRaw=$('f-ctx').value.trim(),ver=($('f-ver').value.trim()||'v1'),
      backend=$('f-backend').value.trim(),msg=$('f-msg');
    if(!name||!ctxRaw||!backend){msg.className='msg err';msg.textContent='Name, base path and backend URL are required.';return;}
    const ctx=(ctxRaw.startsWith('/')?ctxRaw:'/'+ctxRaw).replace(/\\/+$/,'');
    const resources=$('f-res').value.split('\\n').map(l=>l.trim()).filter(Boolean).map(l=>{
      const [verb,...rest]=l.split(/\\s+/);return{verb:(verb||'GET').toUpperCase(),target:rest.join(' ')||'/'};});
    const btn=$('f-submit');btn.disabled=true;msg.className='msg';msg.textContent='Deploying…';
    try{
      const r=await fetch('/mock/deploy',{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({name,version:ver,context:ctx+'/'+ver,backendUrl:backend,resources})});
      const d=await r.json();
      if(r.ok){msg.className='msg ok';msg.textContent='✓ Deployed. WSO2 discovery will import it shortly.';
        setTimeout(()=>{closeModal();tick();},900);}
      else{msg.className='msg err';msg.textContent=d.error||'Deploy failed.';}
    }catch(e){msg.className='msg err';msg.textContent=String(e.message||e);}
    finally{btn.disabled=false;}
  };
</script>
</body>
</html>`;
