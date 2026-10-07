#pragma once
// First-time Wi-Fi setup page, shown by the setup hotspot (phones open it by themselves as a "sign in to network" page).
// Plain HTML + JS so it works with no web app uploaded yet. Talks to /api/scan, /api/wifi and /api/wifistatus.
static const char SETUP_HTML[] PROGMEM = R"HTML(<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>Solar Monitor setup</title>
<style>
:root{color-scheme:light dark;--a:#f5a524;--b:#16181d;--c:#fff;--t:#16181d;--m:#5d6470;--l:#e3e6ea}
@media(prefers-color-scheme:dark){:root{--b:#0e1014;--c:#181b21;--t:#eef0f3;--m:#9aa3ae;--l:#2a2f37}}
*{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,sans-serif;background:var(--b);color:var(--t)}
main{max-width:440px;margin:0 auto;padding:20px 16px 40px}h1{font-size:22px;margin:8px 0 4px}p{color:var(--m);margin:6px 0 14px}
.card{background:var(--c);border:1px solid var(--l);border-radius:18px;padding:16px;margin:12px 0}
button,input{font:inherit;width:100%;min-height:48px;border-radius:14px;border:1px solid var(--l);background:var(--c);color:var(--t);padding:0 14px}
button.p{background:var(--a);border:0;color:#16181d;font-weight:600}input{margin:6px 0 12px}
ul{list-style:none;padding:0;margin:0}li button{display:flex;justify-content:space-between;align-items:center;margin:6px 0;text-align:left}
.s{color:var(--m);font-size:13px}.ok{color:#17a34a}.bad{color:#dc2626}label{font-size:14px;color:var(--m)}
</style></head><body><main>
<h1>&#9728;&#65039; Solar Monitor</h1><p>Connect the monitor to your home Wi-Fi. You only need to do this once.</p>
<div class=card id=pick><b>1. Pick your home Wi-Fi</b><ul id=nets><li class=s>Looking for networks&hellip;</li></ul>
<button onclick=scan()>Search again</button></div>
<form class=card id=f onsubmit="return join(event)"><b>2. Enter its password</b>
<label>Network name<input name=ssid id=ssid required autocomplete=off></label>
<label>Password<input name=pass id=pass type=password autocomplete=off></label>
<button class=p>Connect</button><p class=s id=msg></p></form>
<div class=card id=done hidden><b class=ok>Connected!</b><p id=where></p>
<p class=s>Now join your phone back to your home Wi-Fi and open the address above, or open the Solar Monitor app: it finds the monitor by itself.</p></div>
</main><script>
const $=i=>document.getElementById(i);
async function scan(){$('nets').innerHTML='<li class=s>Looking for networks&hellip;</li>';
 for(let k=0;k<12;k++){try{const j=await(await fetch('/api/scan',{cache:'no-store'})).json();
  if(!j.scanning){const seen={};const n=(j.nets||[]).sort((a,b)=>b.rssi-a.rssi).filter(x=>x.ssid&&!seen[x.ssid]&&(seen[x.ssid]=1));
   $('nets').innerHTML=n.length?'':'<li class=s>No networks found. Move closer to the router and search again.</li>';
   n.forEach(x=>{const li=document.createElement('li'),b=document.createElement('button');b.type='button';
    b.innerHTML='<span></span><span class=s>'+(x.rssi>-60?'strong':x.rssi>-72?'good':'weak')+(x.open?'':' &#128274;')+'</span>';
    b.firstChild.textContent=x.ssid;b.onclick=()=>{$('ssid').value=x.ssid;$('pass').focus()};li.appendChild(b);$('nets').appendChild(li)});return}}catch(e){}
  await new Promise(r=>setTimeout(r,1500))}}
async function join(e){e.preventDefault();const m=$('msg');m.className='s';m.textContent='Connecting, this takes up to 30 seconds…';
 try{await fetch('/api/wifi',{method:'POST',body:new URLSearchParams({ssid:$('ssid').value,pass:$('pass').value})})}catch(e){}
 for(let k=0;k<20;k++){await new Promise(r=>setTimeout(r,2000));try{const j=await(await fetch('/api/wifistatus',{cache:'no-store'})).json();
  if(j.connected){$('f').hidden=$('pick').hidden=true;$('done').hidden=false;
   $('where').innerHTML='Your monitor is at <b>http://'+j.ip+'/</b> and <b>http://'+j.host+'.local/</b> on &ldquo;'+j.ssid.replace(/</g,'')+'&rdquo;.';return}
  if(j.failed)break}catch(e){}}
 m.className='s bad';m.textContent='Could not connect. Check the password and that the router is in range, then try again.';return false}
scan();
</script></body></html>)HTML";
