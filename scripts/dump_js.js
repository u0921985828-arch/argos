// Referencia JS: genera frames RGBA deterministas (sin lienzo) y vuelca cajas.
global.performance = require('perf_hooks').performance;
const mod={exports:{}};
// Las rutas se derivan de la ubicación de ESTE fichero. Estaban absolutas y
// apuntaban a la máquina donde se escribió la prueba, asi que la receta de
// reproduccion del README no podia funcionar en ningun otro sitio --- y una
// prueba de paridad que no se puede reproducir no demuestra nada.
const path=require('path');
const RAIZ=path.resolve(__dirname,'..');
const MOTOR=path.join(RAIZ,'argos','api','static','engine.js');
const SALIDA=process.argv[2] || path.join(RAIZ,'android-native','build','ref_js.txt');
(function(module,exports){ eval(require('fs').readFileSync(MOTOR,'utf8')); })(mod,mod.exports);
const {Engine} = mod.exports;
const W=320,H=180,SRCW=1280,SRCH=720,N=8;

function bgPlate(){
  const d=new Uint8ClampedArray(W*H*4); let s=12345>>>0;
  for(let i=0;i<W*H;i++){ s=(Math.imul(s,1664525)+1013904223)>>>0; const v=60+((s>>>24)%40);
    d[i*4]=v; d[i*4+1]=v; d[i*4+2]=v+5; d[i*4+3]=255; }
  return d;
}
const PLATE=bgPlate();
const objs=Array.from({length:N},(_,k)=>({x:(k*37)%W, y:40+(k*13)%90, vx:0.4+(k%3)*0.17, vy:(k%2?0.07:-0.07), w:7, h:16}));
function frame(){
  const d=new Uint8ClampedArray(PLATE);
  for(const o of objs){ o.x=(o.x+o.vx)%W; o.y+=o.vy; if(o.y<30||o.y>150) o.vy*=-1;
    const x0=Math.floor(o.x), y0=Math.floor(o.y);
    for(let yy=y0; yy<y0+o.h && yy<H; yy++) for(let xx=x0; xx<x0+o.w && xx<W; xx++){
      const p=(yy*W+xx)*4; d[p]=216; d[p+1]=210; d[p+2]=196; d[p+3]=255; } }
  return d;
}
const e=new Engine();
const out=[];
for(let f=0; f<260; f++){
  const r=e.analyze({data:frame(), w:W, h:H, rx:0, ry:0, rw:SRCW, rh:SRCH}, {});
  out.push((r.boxes||[]).map(b=>`${b.id},${b.c},${b.b.map(v=>v.toFixed(4)).join(',')}`).join(';'));
}
require('fs').mkdirSync(path.dirname(SALIDA),{recursive:true});
require('fs').writeFileSync(SALIDA, out.join('\n'));
console.log('JS: '+out.length+' frames, '+out.filter(x=>x).length+' con cajas -> '+SALIDA);
