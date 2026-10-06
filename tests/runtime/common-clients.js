/* Real isolated TCP players. Supports legacy 47/340 and modern registry/configuration protocols.
 * Usage: node common-clients.js <fixture-port> [protocol-override] [javap-prefix]
 * Modern prefixes refer to target/<prefix>-protocol-javap.txt and <prefix>-game-javap.txt
 * extracted from the exact cached server. Protocol is discovered with a real status request.
 */
'use strict';
const net=require('net'), zlib=require('zlib'), crypto=require('crypto'), fs=require('fs'), path=require('path');
const root=path.resolve(__dirname,'../..'), port=Number(process.argv[2] || 25594);
if(port<25594 || port>25599) throw new Error('Clients are restricted to isolated compatibility fixture ports');
function vi(value) { const bytes=[]; do {let b=value&127; value>>>=7; if(value)b|=128; bytes.push(b);}while(value); return Buffer.from(bytes); }
function readVi(bytes,offset=0) {let value=0; for(let i=0;i<5;i++){if(offset+i>=bytes.length)return null; const b=bytes[offset+i]; value|=(b&127)<<(i*7); if(!(b&128))return{value:value>>>0,next:offset+i+1};}throw new Error('Invalid VarInt');}
function string(value){const bytes=Buffer.from(value,'utf8');return Buffer.concat([vi(bytes.length),bytes]);}
function offlineUuid(name){const bytes=crypto.createHash('md5').update('OfflinePlayer:'+name).digest();bytes[6]=(bytes[6]&15)|48;bytes[8]=(bytes[8]&63)|128;return bytes;}
function frame(id,body=Buffer.alloc(0)){const packet=Buffer.concat([vi(id),body]);return Buffer.concat([vi(packet.length),packet]);}
function handshake(protocol,state){const bytes=Buffer.alloc(2);bytes.writeUInt16BE(port);return Buffer.concat([vi(protocol),string('127.0.0.1'),bytes,vi(state)]);}
function discover(){return new Promise((resolve,reject)=>{
 const socket=net.connect({host:'127.0.0.1',port});let buffer=Buffer.alloc(0),finished=false;
 socket.once('connect',()=>socket.write(Buffer.concat([frame(0,handshake(-1,1)),frame(0)])));
 socket.on('data',chunk=>{buffer=Buffer.concat([buffer,chunk]);const length=readVi(buffer);if(!length||buffer.length<length.next+length.value)return;const packet=buffer.subarray(length.next,length.next+length.value);const id=readVi(packet);const size=readVi(packet,id.next);const status=JSON.parse(packet.subarray(size.next,size.next+size.value).toString());finished=true;socket.end();console.log('Fixture status '+status.version.name+' protocol='+status.version.protocol);resolve(status.version.protocol);});
 socket.on('error',reject);socket.setTimeout(5000,()=>{socket.destroy();if(!finished)reject(new Error('Status timeout'));});
});}
function mappings(protocol){
 const loginIn={CLIENTBOUND_LOGIN_DISCONNECT:0,CLIENTBOUND_LOGIN_FINISHED:2,CLIENTBOUND_LOGIN_COMPRESSION:3};
 const loginOut={SERVERBOUND_HELLO:0,SERVERBOUND_LOGIN_ACKNOWLEDGED:3};
 if(protocol===47)return {loginIn,loginOut,playIn:{CLIENTBOUND_KEEP_ALIVE:0,CLIENTBOUND_LOGIN:1,CLIENTBOUND_PLAYER_POSITION:8,CLIENTBOUND_DISCONNECT:0x40},playOut:{SERVERBOUND_KEEP_ALIVE:0,SERVERBOUND_MOVE_PLAYER_POS_ROT:6}};
 if(protocol===340)return {loginIn,loginOut,playIn:{CLIENTBOUND_KEEP_ALIVE:0x1f,CLIENTBOUND_LOGIN:0x23,CLIENTBOUND_PLAYER_POSITION:0x2f,CLIENTBOUND_DISCONNECT:0x1a},playOut:{SERVERBOUND_KEEP_ALIVE:0x0b,SERVERBOUND_ACCEPT_TELEPORTATION:0}};
 if(protocol<764)throw new Error('Unsupported isolated client protocol '+protocol);
 const prefix=process.argv[4] || (protocol===774?'common-774':protocol===776?'folia':'common-paper');
 const raw=fs.readFileSync(path.join(root,'target',prefix+'-protocol-javap.txt'),'utf8');
 const game=fs.readFileSync(path.join(root,'target',prefix+'-game-javap.txt'),'utf8');
 const login=raw.split('Compiled from "ConfigurationProtocols.java"')[0],config=raw.split('Compiled from "ConfigurationProtocols.java"')[1];
 function ids(text,flow){const names=[...text.matchAll(/Field [^\r\n]*PacketTypes\.(CLIENTBOUND_[A-Z_]+|SERVERBOUND_[A-Z_]+):/g)].map(m=>m[1]).filter(n=>n.startsWith(flow));if(!names.length)throw new Error('Missing javap mappings for '+prefix);return Object.fromEntries(names.map((name,index)=>[name,index]));}
 return {loginIn:ids(login,'CLIENTBOUND'),loginOut:ids(login,'SERVERBOUND'),configIn:ids(config,'CLIENTBOUND'),configOut:ids(config,'SERVERBOUND'),playIn:ids(game,'CLIENTBOUND'),playOut:ids(game,'SERVERBOUND')};
}
function launch(name,protocol,ids){
 const socket=net.connect({host:'127.0.0.1',port});let state='login',compressed=false,buffer=Buffer.alloc(0),playing=false;
 function send(id,body=Buffer.alloc(0)){if(id===undefined)throw new Error('Missing outbound packet ID');let data=Buffer.concat([vi(id),body]);if(compressed)data=Buffer.concat([vi(0),data]);socket.write(Buffer.concat([vi(data.length),data]));}
 socket.on('connect',()=>{send(0,handshake(protocol,2));send(ids.loginOut.SERVERBOUND_HELLO,protocol>=759?Buffer.concat([string(name),offlineUuid(name)]):string(name));console.log(name+' real TCP login');});
 socket.on('data',chunk=>{
   buffer=Buffer.concat([buffer,chunk]);
   for(;;){const size=readVi(buffer);if(!size||buffer.length<size.next+size.value)break;let packet=buffer.subarray(size.next,size.next+size.value);buffer=buffer.subarray(size.next+size.value);if(compressed){const decoded=readVi(packet);packet=packet.subarray(decoded.next);if(decoded.value)packet=zlib.inflateSync(packet);}const id=readVi(packet);if(!id)continue;const body=packet.subarray(id.next);
     if(state==='login'){
       if(id.value===ids.loginIn.CLIENTBOUND_LOGIN_COMPRESSION){compressed=true;continue;}
       if(id.value===ids.loginIn.CLIENTBOUND_LOGIN_FINISHED){
         if(protocol<764){state='play';console.log(name+' accepted legacy login');}
         else{send(ids.loginOut.SERVERBOUND_LOGIN_ACKNOWLEDGED);state='config';send(ids.configOut.SERVERBOUND_CLIENT_INFORMATION,Buffer.concat([string('en_us'),Buffer.from([2]),vi(0),Buffer.from([1,127]),vi(1),Buffer.from([0,1]),vi(0)]));console.log(name+' configuring');}
       }else if(id.value===ids.loginIn.CLIENTBOUND_LOGIN_DISCONNECT)console.error(name+' login disconnect '+body.toString());
     }else if(state==='config'){
       if(id.value===ids.configIn.CLIENTBOUND_SELECT_KNOWN_PACKS)send(ids.configOut.SERVERBOUND_SELECT_KNOWN_PACKS,vi(0));
       else if(id.value===ids.configIn.CLIENTBOUND_FINISH_CONFIGURATION){send(ids.configOut.SERVERBOUND_FINISH_CONFIGURATION);state='play';console.log(name+' configuration finished');}
       else if(id.value===ids.configIn.CLIENTBOUND_KEEP_ALIVE)send(ids.configOut.SERVERBOUND_KEEP_ALIVE,body);
       else if(id.value===ids.configIn.CLIENTBOUND_PING)send(ids.configOut.SERVERBOUND_PONG,body);
       else if(id.value===ids.configIn.CLIENTBOUND_CODE_OF_CONDUCT)send(ids.configOut.SERVERBOUND_ACCEPT_CODE_OF_CONDUCT);
       else if(id.value===ids.configIn.CLIENTBOUND_DISCONNECT)console.error(name+' configuration disconnect '+body.toString('hex'));
     }else{
       if(id.value===ids.playIn.CLIENTBOUND_KEEP_ALIVE)send(ids.playOut.SERVERBOUND_KEEP_ALIVE,body);
       else if(id.value===ids.playIn.CLIENTBOUND_PING)send(ids.playOut.SERVERBOUND_PONG,body);
       else if(id.value===ids.playIn.CLIENTBOUND_PLAYER_POSITION){
         if(protocol===47)send(ids.playOut.SERVERBOUND_MOVE_PLAYER_POS_ROT,Buffer.concat([body.subarray(0,32),Buffer.from([1])]));
         else if(protocol>=777){
           // 26.3's exact server codec adds accepted XYZ/yaw/pitch after the teleport VarInt.
           // PositionMoveRotation is XYZ, delta XYZ, then yaw/pitch; fixture teleports are absolute.
           const teleport=readVi(body);
           send(ids.playOut.SERVERBOUND_ACCEPT_TELEPORTATION,Buffer.concat([vi(teleport.value),body.subarray(teleport.next,teleport.next+24),body.subarray(teleport.next+48,teleport.next+56)]));
         }else send(ids.playOut.SERVERBOUND_ACCEPT_TELEPORTATION,vi(readVi(body,protocol===340?33:0).value));
         if(ids.playOut.SERVERBOUND_PLAYER_LOADED!==undefined)send(ids.playOut.SERVERBOUND_PLAYER_LOADED);
       }else if(id.value===ids.playIn.CLIENTBOUND_CHUNK_BATCH_FINISHED){const rate=Buffer.alloc(4);rate.writeFloatBE(8);send(ids.playOut.SERVERBOUND_CHUNK_BATCH_RECEIVED,rate);}
       else if(id.value===ids.playIn.CLIENTBOUND_LOGIN&&!playing){playing=true;console.log(name+' PLAY actual player joined');}
       else if(id.value===ids.playIn.CLIENTBOUND_DISCONNECT)console.error(name+' play disconnect '+body.toString('hex'));
     }
   }
 });
 socket.on('error',error=>console.error(name+': '+error.message));socket.on('close',()=>console.log(name+' disconnected'));return socket;
}
(async()=>{const protocol=Number(process.argv[3])||await discover();const ids=mappings(protocol);const clients=['CommonViewer','CommonTarget'].map(name=>launch(name,protocol,ids));let closed=0;const lifetime=setTimeout(()=>clients.forEach(c=>c.end()),300000);clients.forEach(client=>client.on('close',()=>{if(++closed===clients.length)clearTimeout(lifetime);}));})().catch(error=>{console.error(error);process.exitCode=1;});
