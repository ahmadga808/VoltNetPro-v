package com.subsmgr.app

/**
 * صفحة الخريطة (Leaflet + OpenStreetMap) تعمل داخل WebView.
 * تجرّب أكثر من مصدر لمكتبة Leaflet، وأكثر من مزوّد للبلاطات، وتعرض رسالة واضحة بدل الشاشة البيضاء عند أي فشل.
 */
private val MAP_HEAD = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{height:100%;margin:0;background:#e5e7eb;font-family:sans-serif}
#m{position:absolute;top:0;bottom:0;left:0;right:0}
#st{position:absolute;z-index:9999;top:40%;left:0;right:0;text-align:center;padding:16px;color:#475569;font-size:15px;direction:rtl}
.lg{position:absolute;z-index:999;bottom:24px;left:10px;background:#fff;padding:6px 10px;border-radius:8px;font-size:12px;direction:rtl}
</style></head><body><div id="m"></div><div id="st">جارٍ تحميل الخريطة...</div>
<script>
function show(t){var s=document.getElementById('st');s.style.display='block';s.style.color='#b91c1c';s.textContent=t;}
window.onerror=function(m){show('خطأ في الخريطة: '+m);};
function note(t){var n=document.getElementById('nt');if(!n){n=document.createElement('div');n.id='nt';n.style.cssText='position:absolute;z-index:9999;top:8px;left:8px;right:8px;background:#fff3cd;color:#664d03;padding:8px 10px;border-radius:8px;font-size:13px;direction:rtl;text-align:center';document.body.appendChild(n);}n.textContent=t;}
function load(urls,kind,ok,fail,i){
  i=i||0;
  if(i>=urls.length){fail();return;}
  var e,done=false,t;
  function next(){if(done)return;done=true;clearTimeout(t);if(e&&e.parentNode)e.parentNode.removeChild(e);load(urls,kind,ok,fail,i+1);}
  if(kind==='css'){e=document.createElement('link');e.rel='stylesheet';e.href=urls[i];}
  else{e=document.createElement('script');e.src=urls[i];}
  e.onload=function(){if(done)return;done=true;clearTimeout(t);ok();};
  e.onerror=next;
  t=setTimeout(next,7000);
  document.head.appendChild(e);
}
function tiles(map){
  var osm=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'});
  var carto=L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',{maxZoom:19,subdomains:'abcd',attribution:'© OpenStreetMap © CARTO'});
  var sat=L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:19,attribution:'Tiles © Esri'});
  var ld=0,eo=0,ec=0;
  function ok(){ld++;var n=document.getElementById('nt');if(n)n.style.display='none';}
  osm.on('tileload',ok);carto.on('tileload',ok);sat.on('tileload',ok);
  osm.on('tileerror',function(){eo++;if(ld===0&&eo>=3&&map.hasLayer(osm)){map.removeLayer(osm);carto.addTo(map);}});
  carto.on('tileerror',function(){ec++;if(ld===0&&ec>=3&&map.hasLayer(carto)){map.removeLayer(carto);sat.addTo(map);}});
  osm.addTo(map);
  L.control.layers({'خريطة':osm,'خريطة ثانية':carto,'قمر صناعي':sat}).addTo(map);
  setTimeout(function(){if(ld===0)note('لا تظهر صور الخريطة. تحقق من الإنترنت ثم اضغط زر إعادة التحميل.');},9000);
}
var bases=['https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet','https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet','https://unpkg.com/leaflet@1.9.4/dist/leaflet'];
load(bases.map(function(b){return b+'.css';}),'css',function(){},function(){});
load(bases.map(function(b){return b+'.js';}),'js',function(){
  if(window.__s)return;window.__s=1;
  document.getElementById('st').style.display='none';
  try{start();}catch(e){show('خطأ في الخريطة: '+e.message);}
},function(){show('تعذّر تحميل مكتبة الخريطة. تحقق من اتصال الإنترنت ثم اضغط زر إعادة التحميل.');});
</script>
<script>"""

private fun page(startJs: String): String = MAP_HEAD + "function start(){" + startJs + "}</script></body></html>"

/** خريطة اختيار موقع: المس لوضع دبوس، واسحبه للتعديل. تنادي Android.pick(lat,lng). */
fun pickerPage(lat: Double, lng: Double, hasPoint: Boolean): String {
    val zoom = if (hasPoint) 17 else 13
    val init = if (hasPoint) "put($lat,$lng);" else ""
    return page(
        "var m=L.map('m').setView([$lat,$lng],$zoom);tiles(m);var mk=null;" +
            "function put(a,b){if(mk){mk.setLatLng([a,b]);}else{mk=L.marker([a,b],{draggable:true}).addTo(m);" +
            "mk.on('dragend',function(){var p=mk.getLatLng();Android.pick(p.lat,p.lng);});}Android.pick(a,b);}" +
            init + "m.on('click',function(e){put(e.latlng.lat,e.latlng.lng);});"
    )
}

/** خريطة كل المشتركين. data = مصفوفة JSON من {lat,lng,color,popup}. */
fun subsPage(data: String): String = page(
    "var pts=" + data + ";var m=L.map('m');tiles(m);var b=[];" +
        "pts.forEach(function(p){L.circleMarker([p.lat,p.lng],{radius:9,color:p.color,fillColor:p.color,fillOpacity:.8}).addTo(m).bindPopup(p.popup);b.push([p.lat,p.lng]);});" +
        "m.fitBounds(b,{padding:[40,40],maxZoom:17});" +
        "var lg=document.createElement('div');lg.className='lg';lg.innerHTML='🟢 فعّال / مسدّد &nbsp; 🟡 منتهي &nbsp; 🔴 عليه دين';document.body.appendChild(lg);"
)
