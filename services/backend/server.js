// Unified mock backend.
//
// A single REST service that stands in for every "original" upstream in the
// demo. It infers the collection from the request path — tolerating gateway
// context prefixes and versioned segments — and serves deterministic sample
// data, so any API pointed at it gets a believable endpoint to try out.
//
//   /employee/employees        -> collection "employees"
//   /vehicle/v1/vehicles       -> collection "vehicles"   (drops the "v1" segment)
//   /citizen/v1/citizens/3     -> item 3 of "citizens"
//   /leave                     -> collection "leave"
//
// Verbs: GET (list), GET /{id} (fetch), POST (create), DELETE /{id} (remove).
const http = require('http');

const PORT = process.env.PORT || 8080;

// Deterministic seed data for every collection the demo can exercise: the six
// core APIs plus the on-demand dashboard "template" APIs.
const SEED = {
  employees: [
    { id: 1, name: 'Ada Lovelace', department: 'Engineering', title: 'Principal Engineer' },
    { id: 2, name: 'Alan Turing', department: 'Research', title: 'Scientist' },
    { id: 3, name: 'Grace Hopper', department: 'Platform', title: 'Architect' },
  ],
  leave: [
    { id: 1, employee: 'Ada Lovelace', type: 'Annual', days: 5, status: 'APPROVED' },
    { id: 2, employee: 'Alan Turing', type: 'Sick', days: 2, status: 'PENDING' },
  ],
  vehicles: [
    { id: 1, plate: 'CAB-1123', make: 'Toyota', model: 'Corolla', year: 2021 },
    { id: 2, plate: 'CAR-8890', make: 'Nissan', model: 'Leaf', year: 2023 },
  ],
  parking: [
    { id: 1, bay: 'A-12', vehicle: 'CAB-1123', since: '2026-07-17T08:00:00Z' },
    { id: 2, bay: 'B-04', vehicle: 'CAR-8890', since: '2026-07-17T09:15:00Z' },
  ],
  citizens: [
    { id: 1, name: 'John Silva', nic: '199012345678', district: 'Colombo' },
    { id: 2, name: 'Mary Perera', nic: '199587654321', district: 'Kandy' },
  ],
  payments: [
    { id: 1, ref: 'PAY-1001', amount: 4500, currency: 'LKR', status: 'SETTLED' },
    { id: 2, ref: 'PAY-1002', amount: 12000, currency: 'LKR', status: 'PENDING' },
  ],
  departments: ['Engineering', 'Finance', 'Operations', 'Legal', 'Human Resources'],
  payrolls: ['Jan Cycle', 'Feb Cycle', 'Mar Cycle', 'Apr Cycle'],
  fuels: ['Petrol 95', 'Diesel', 'Electric', 'CNG'],
  tolls: ['North Gate', 'Harbour Bridge', 'City Tunnel', 'Airport Link'],
  taxes: ['Income Tax', 'Property Tax', 'Vehicle Tax', 'Service Tax'],
  licenses: ['Driving', 'Trade', 'Liquor', 'Firearm'],
};

// In-memory store, cloned from the seed so POST/DELETE mutate at runtime.
const store = {};
function itemsFor(collection) {
  if (!store[collection]) {
    const seed = SEED[collection];
    if (Array.isArray(seed) && typeof seed[0] === 'object') {
      store[collection] = seed.map((x) => ({ ...x }));
    } else {
      const names = Array.isArray(seed) ? seed : ['Alpha', 'Beta', 'Gamma'];
      store[collection] = names.map((name, i) => ({
        id: i + 1,
        name,
        collection,
        status: 'ACTIVE',
        reference: `${collection.toUpperCase().slice(0, 3)}-${1000 + i}`,
      }));
    }
  }
  return store[collection];
}

function send(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body, null, 2));
}
function readBody(req) {
  return new Promise((resolve) => {
    let b = '';
    req.on('data', (c) => (b += c));
    req.on('end', () => { try { resolve(b ? JSON.parse(b) : {}); } catch { resolve({}); } });
  });
}

// Resolve { collection, id } from a path, ignoring version segments.
function classify(url) {
  const parts = url.split('/').filter(Boolean).filter((p) => !/^v\d+$/i.test(p));
  if (!parts.length) return null;
  const last = parts[parts.length - 1];
  if (/^\d+$/.test(last) && parts.length >= 2) {
    return { collection: parts[parts.length - 2], id: last };
  }
  return { collection: last, id: null };
}

const server = http.createServer(async (req, res) => {
  const url = (req.url.split('?')[0].replace(/\/+$/, '')) || '/';
  if (url === '/health' || url === '/') return send(res, 200, { status: 'UP', service: 'backend' });

  const c = classify(url);
  if (!c) return send(res, 404, { error: 'not found', path: url });
  const items = itemsFor(c.collection);

  if (req.method === 'GET' && c.id !== null) {
    const it = items.find((x) => String(x.id) === c.id);
    return it ? send(res, 200, it) : send(res, 404, { error: 'not found', id: c.id });
  }
  if (req.method === 'GET') return send(res, 200, items);

  if (req.method === 'POST') {
    const body = await readBody(req);
    const nextId = items.reduce((m, i) => Math.max(m, Number(i.id) || 0), 0) + 1;
    const it = { id: nextId, ...body };
    items.push(it);
    console.log(`[backend] created ${c.collection}/${it.id}`);
    return send(res, 201, it);
  }
  if (req.method === 'DELETE' && c.id !== null) {
    const idx = items.findIndex((x) => String(x.id) === c.id);
    if (idx < 0) return send(res, 404, { error: 'not found', id: c.id });
    const [rm] = items.splice(idx, 1);
    console.log(`[backend] deleted ${c.collection}/${c.id}`);
    return send(res, 200, { deleted: rm });
  }
  return send(res, 405, { error: 'method not allowed', method: req.method, path: url });
});

server.listen(PORT, () => console.log(`backend listening on :${PORT}`));
