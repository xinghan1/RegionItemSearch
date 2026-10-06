/* Isolated, dependency-free offline protocol clients for the cached Folia 26.2 fixture.
 * Packet order is extracted from javap of that exact server, rather than guessed from older versions.
 * These are real TCP connections with login/configuration, keepalive, teleport and chunk-batch ACKs.
 */
'use strict';
const net = require('net');
const zlib = require('zlib');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../..');
function packetIds(file, cls, flow) {
  const raw = fs.readFileSync(path.join(root, 'target', file), 'utf8');
  const section = cls ? raw.substring(raw.indexOf('Compiled from "' + cls + '.java"')) : raw;
  const names = [...section.matchAll(/Field [^\r\n]*PacketTypes\.(CLIENTBOUND_[A-Z_]+|SERVERBOUND_[A-Z_]+):/g)]
    .map(match => match[1]).filter(name => name.startsWith(flow));
  return Object.fromEntries(names.map((name, index) => [name, index]));
}
const ids = {
  loginIn: packetIds('folia-protocol-javap.txt', 'LoginProtocols', 'CLIENTBOUND'),
  loginOut: packetIds('folia-protocol-javap.txt', 'LoginProtocols', 'SERVERBOUND'),
  configIn: packetIds('folia-protocol-javap.txt', 'ConfigurationProtocols', 'CLIENTBOUND'),
  configOut: packetIds('folia-protocol-javap.txt', 'ConfigurationProtocols', 'SERVERBOUND'),
  playIn: packetIds('folia-game-javap.txt', null, 'CLIENTBOUND'),
  playOut: packetIds('folia-game-javap.txt', null, 'SERVERBOUND')
};
// Login extraction stops before the subsequent ConfigurationProtocols class.
for (const side of ['In', 'Out']) {
  const flow = side === 'In' ? 'CLIENTBOUND' : 'SERVERBOUND';
  const raw = fs.readFileSync(path.join(root, 'target/folia-protocol-javap.txt'), 'utf8')
    .split('Compiled from "ConfigurationProtocols.java"')[0];
  const names = [...raw.matchAll(/Field [^\r\n]*PacketTypes\.(CLIENTBOUND_[A-Z_]+|SERVERBOUND_[A-Z_]+):/g)]
    .map(match => match[1]).filter(name => name.startsWith(flow));
  ids['login' + side] = Object.fromEntries(names.map((name, index) => [name, index]));
}
function vi(value) {
  const out = [];
  do { let b = value & 127; value >>>= 7; if (value) b |= 128; out.push(b); } while (value);
  return Buffer.from(out);
}
function readVi(data, start = 0) {
  let value = 0;
  for (let i = 0; i < 5; i++) {
    if (start + i >= data.length) return null;
    const b = data[start + i]; value |= (b & 127) << (7 * i);
    if (!(b & 128)) return {value: value >>> 0, next: start + i + 1};
  }
  throw new Error('Invalid VarInt');
}
function string(value) { const raw = Buffer.from(value, 'utf8'); return Buffer.concat([vi(raw.length), raw]); }
function uuid(name) {
  const raw = crypto.createHash('md5').update('OfflinePlayer:' + name).digest();
  raw[6] = (raw[6] & 15) | 48; raw[8] = (raw[8] & 63) | 128; return raw;
}
function launch(name) {
  const socket = net.connect({host: '127.0.0.1', port: Number(process.env.FOLIA_PROBE_PORT || 25598)});
  let state = 'login', compressed = false, buffer = Buffer.alloc(0), playing = false;
  function send(id, body = Buffer.alloc(0)) {
    let data = Buffer.concat([vi(id), body]);
    if (compressed) data = Buffer.concat([vi(0), data]);
    socket.write(Buffer.concat([vi(data.length), data]));
  }
  socket.on('connect', () => {
    const port = Buffer.alloc(2); port.writeUInt16BE(Number(process.env.FOLIA_PROBE_PORT || 25598));
    send(0, Buffer.concat([vi(776), string('127.0.0.1'), port, vi(2)]));
    send(ids.loginOut.SERVERBOUND_HELLO, Buffer.concat([string(name), uuid(name)]));
    console.log(name + ' connected with real offline TCP login');
  });
  socket.on('data', chunk => {
    buffer = Buffer.concat([buffer, chunk]);
    for (;;) {
      const length = readVi(buffer); if (!length || buffer.length < length.next + length.value) break;
      let packet = buffer.subarray(length.next, length.next + length.value);
      buffer = buffer.subarray(length.next + length.value);
      if (compressed) {
        const size = readVi(packet); if (!size) throw new Error('Short compressed frame');
        packet = packet.subarray(size.next); if (size.value) packet = zlib.inflateSync(packet);
      }
      const id = readVi(packet); if (!id) continue;
      const body = packet.subarray(id.next);
      if (state === 'login') {
        if (id.value === ids.loginIn.CLIENTBOUND_LOGIN_COMPRESSION) { compressed = true; continue; }
        if (id.value === ids.loginIn.CLIENTBOUND_LOGIN_FINISHED) {
          send(ids.loginOut.SERVERBOUND_LOGIN_ACKNOWLEDGED); state = 'config';
          // locale, view distance, chat mode, colors, skin parts, hand, filtering, listing, particle status.
          send(ids.configOut.SERVERBOUND_CLIENT_INFORMATION,
            Buffer.concat([string('en_us'), Buffer.from([2]), vi(0), Buffer.from([1, 127]), vi(1), Buffer.from([0, 1]), vi(0)]));
          console.log(name + ' accepted login, configuring');
        } else if (id.value === ids.loginIn.CLIENTBOUND_LOGIN_DISCONNECT) console.error(name + ' login disconnect: ' + body.toString());
      } else if (state === 'config') {
        if (id.value === ids.configIn.CLIENTBOUND_SELECT_KNOWN_PACKS) send(ids.configOut.SERVERBOUND_SELECT_KNOWN_PACKS, vi(0));
        else if (id.value === ids.configIn.CLIENTBOUND_FINISH_CONFIGURATION) {
          send(ids.configOut.SERVERBOUND_FINISH_CONFIGURATION); state = 'play'; console.log(name + ' completed configuration');
        } else if (id.value === ids.configIn.CLIENTBOUND_KEEP_ALIVE) send(ids.configOut.SERVERBOUND_KEEP_ALIVE, body);
        else if (id.value === ids.configIn.CLIENTBOUND_PING) send(ids.configOut.SERVERBOUND_PONG, body);
        else if (id.value === ids.configIn.CLIENTBOUND_CODE_OF_CONDUCT) send(ids.configOut.SERVERBOUND_ACCEPT_CODE_OF_CONDUCT);
        else if (id.value === ids.configIn.CLIENTBOUND_DISCONNECT) console.error(name + ' config disconnect hex=' + body.toString('hex'));
      } else {
        if (id.value === ids.playIn.CLIENTBOUND_KEEP_ALIVE) send(ids.playOut.SERVERBOUND_KEEP_ALIVE, body);
        else if (id.value === ids.playIn.CLIENTBOUND_PING) send(ids.playOut.SERVERBOUND_PONG, body);
        else if (id.value === ids.playIn.CLIENTBOUND_PLAYER_POSITION) {
          send(ids.playOut.SERVERBOUND_ACCEPT_TELEPORTATION, vi(readVi(body).value));
          send(ids.playOut.SERVERBOUND_PLAYER_LOADED);
        } else if (id.value === ids.playIn.CLIENTBOUND_CHUNK_BATCH_FINISHED) {
          const desired = Buffer.alloc(4); desired.writeFloatBE(8); send(ids.playOut.SERVERBOUND_CHUNK_BATCH_RECEIVED, desired);
        } else if (id.value === ids.playIn.CLIENTBOUND_LOGIN && !playing) { playing = true; console.log(name + ' PLAY entity joined'); }
        else if (id.value === ids.playIn.CLIENTBOUND_DISCONNECT) console.error(name + ' play disconnect hex=' + body.toString('hex'));
      }
    }
  });
  socket.on('error', error => console.error(name + ': ' + error.message));
  socket.on('close', () => console.log(name + ' disconnected'));
  return socket;
}
const clients = ['FoliaViewer', 'FoliaTarget', 'FoliaVictim'].map(launch);
const lifetime = setTimeout(() => { clients.forEach(client => client.end()); }, Number(process.env.FOLIA_PROBE_LIFETIME || 300000));
let closedClients = 0;
clients.forEach(client => client.on('close', () => { if (++closedClients === clients.length) clearTimeout(lifetime); }));
