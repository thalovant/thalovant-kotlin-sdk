// Independent Node Noise responder. Explicit loopback only; no production credentials.
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomBytes} from 'node:crypto';
const root = resolve(process.argv[2]), req = createRequire(join(root, 'package.json'));
const WebSocketServer = req('ws').WebSocketServer ?? req('ws').Server;
const noise = await import(pathToFileURL(join(root, 'dist/src/noise.js')));
const key = randomBytes(32), nodeID = 'managed-loopback-fixture';
const psk = noise.derivePsk('fixture-password', nodeID);
let pinned, connections = 0, exchanges = 0, queries = 0, closed = 0, finished = false;
const http = createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/fixture/status') {
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({connections, closed}));
  } else response.writeHead(404).end();
});
const server = new WebSocketServer({server: http});
const timer = setTimeout(() => finish(new Error('loopback fixture deadline exceeded')), 60000);
timer.unref();
function finish(error) {
  if (finished) return; finished = true; clearTimeout(timer);
  if (error) { console.error(error.message); process.exitCode = 1; }
  else console.log('Node verified XX to KK, exactly two connections, six encrypted exchanges and two scoped queries');
  server.close(); for (const client of server.clients) client.terminate(); http.close(); http.closeIdleConnections();
}
server.on('connection', socket => {
  const batch = ++connections;
  if (batch > 2 || closed !== batch - 1 || exchanges !== (batch - 1) * 3 || queries !== batch - 1) {
    finish(new Error('unexpected extra or overlapping connection')); return;
  }
  const hello = {node_id: nodeID, pubkey: 'fixture', label: 'café/voice'};
  const offer = {max_protocol_version: 3, binarize: true, encodings: ['JSON-HEX'], ciphers: ['AES-GCM'],
    noise: {patterns: pinned ? ['XXpsk2', 'KKpsk0'] : ['XXpsk2'], suites: ['25519_AESGCM_SHA256']}};
  let handshake, session, querySeen = false;
  const received = new Set();
  socket.send(JSON.stringify({msg_type: 'hello', payload: hello}));
  socket.send(JSON.stringify({msg_type: 'shake', payload: offer}));
  function send(message) {
    for (const frame of session.encryptMessage(Buffer.from(JSON.stringify(message)), true)) socket.send(frame, {binary: true});
  }
  socket.on('message', (bytes, binary) => {
    try {
      if (session) {
        if (!binary) throw new Error('unencrypted application frame');
        const frame = session.decryptFrame(bytes); if (!frame.complete) return;
        const message = JSON.parse(Buffer.from(frame.payload));
        if (message.msg_type === 'hello') return;
        if (message.msg_type === 'query') {
          if (querySeen || received.size !== 3 || message.payload?.msg_type !== 'bus' ||
              message.payload.payload?.type !== 'recognizer_loop:utterance' || !message.metadata?.query_id) throw new Error('invalid query');
          querySeen = true; queries++;
          const event = (id, name, text) => ({msg_type: 'cascade', metadata: {query_id: id},
            payload: {msg_type: 'bus', payload: {type: name, data: {utterance: text}, context: {}}}});
          send(event('unrelated', 'speak', 'wrong'));
          send(event(message.metadata.query_id, 'speak', 'query answer'));
          send(event(message.metadata.query_id, 'hive.query.complete', ''));
          return;
        }
        if (message.msg_type !== 'bus' || message.payload.type !== 'fixture.ping') throw new Error('unexpected application frame');
        const n = message.payload.data.n;
        if (![0,1,2].includes(n) || received.has(n)) throw new Error('duplicate exchange');
        received.add(n); exchanges++;
        send({msg_type: 'bus', payload: {type: 'fixture.pong', data: message.payload.data, context: {}}});
        return;
      }
      if (binary) throw new Error('binary before handshake');
      const params = JSON.parse(bytes).payload.noise;
      if (!handshake) {
        const pattern = pinned ? 'KKpsk0' : 'XXpsk2';
        if (params.pattern !== pattern || params.suite !== '25519_AESGCM_SHA256') throw new Error('unexpected Noise negotiation');
        handshake = new noise.NoiseHandshake(pattern, params.suite, psk,
          noise.buildPrologue(hello, offer, noise.noiseProtocolName(pattern, params.suite)), key, pinned, false);
        handshake.readMessage(Buffer.from(params.msg, 'hex'));
        const reply = handshake.writeMessage(Buffer.from('{"encoding":"JSON-HEX"}'));
        socket.send(JSON.stringify({msg_type: 'shake', payload: {noise: {msg: Buffer.from(reply).toString('hex')}}}));
      } else handshake.readMessage(Buffer.from(params.msg, 'hex'));
      if (handshake.isFinished) { session = handshake.intoSession(); pinned = Buffer.from(session.remoteStaticKey, 'hex'); }
    } catch (error) { finish(error); }
  });
  socket.on('error', finish);
  socket.on('close', () => {
    if (finished) return;
    if (received.size !== 3 || !querySeen) { finish(new Error('connection closed before all exchanges')); return; }
    closed++; if (closed === 2) finish();
  });
});
http.on('error', finish);
http.listen(0, '127.0.0.1', () => console.log(`ws://127.0.0.1:${http.address().port}`));
