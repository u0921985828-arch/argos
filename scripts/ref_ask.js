// Referencia del chat para la paridad con Ask.kt: cómo entiende ask.js cada
// frase (qué intención, qué ventana de tiempo, qué clase), con la hora fija y
// en UTC para que Kotlin pueda reproducirlo exactamente.
//
//   TZ=UTC node scripts/ref_ask.js [salida]  -> android-native/build/ask_ref.txt
process.env.TZ = 'UTC';
const fs = require('fs'), path = require('path');
const SAL = process.argv[2] || path.join(__dirname, '..', 'android-native', 'build', 'ask_ref.txt');
const mod = {exports: {}};
(function (module, exports) {
  eval(fs.readFileSync(path.join(__dirname, '..', 'argos', 'api', 'static', 'ask.js'), 'utf8'));
})(mod, mod.exports);
const {AskConsole, ventana} = mod.exports;
const NOW = Date.UTC(2026, 9, 22, 18, 37, 0);
const realNow = Date.now; Date.now = () => NOW;
class Sonda extends AskConsole {
  _objeto(id) { return {texto: `OBJETO ${id}`}; }
  _ahora() { return {texto: 'AHORA'}; }
  _anomalias() { return {texto: 'ANOMALIAS'}; }
  _rapido() { return {texto: 'RAPIDO'}; }
  _contar() { return {texto: 'CONTAR'}; }
  _resumen() { return {texto: 'RESUMEN'}; }
  _no() { return {texto: 'AYUDA'}; }
}
const FRASES = [
  'cuánta gente pasó en la última hora', 'qué pasó entre las 3 y las 4', 'algo raro hoy',
  'vehículos esta semana', 'objeto 47', 'lo más rápido de hoy', '#12', 'qué ves',
  'cuántos coches entre las 22:30 y las 01:15', 'últimos 20 minutos', 'última media hora',
  'últimas 3 horas personas', 'resumen de ayer', 'alertas esta madrugada', 'bicis en total',
  'hola', 'estado', 'ahora mismo cuantas personas', 'de 15:30 a 16:00 vehiculos',
  'cuantos patinetes hoy', 'quien corria hoy', 'ULTIMOS 5 MINUTOS', 'actividad de esta hora',
];
(async () => {
  const a = new Sonda(null);
  const out = [];
  for (const q of FRASES) {
    const r = await a._route(q);
    const v = ventana(q, NOW);
    const n = q.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
    const cls = (/\b(personas?|gente|peatones?|viandantes?)\b/.test(n) ? 'person'
      : /\b(coches?|vehiculos?|furgonetas?|camiones?|autobuses?)\b/.test(n) ? 'vehicle'
      : /\b(bicis?|bicicletas?|motos?|patinetes?)\b/.test(n) ? 'bike' : 'null');
    out.push([q, r.texto, v ? (v[0] ?? 'null') : '-', v ? (v[1] ?? 'null') : '-', v ? v[2] : '-', cls].join('\t'));
  }
  fs.mkdirSync(path.dirname(SAL), {recursive: true});
  fs.writeFileSync(SAL, out.join('\n') + '\n');
  console.log(out.map((l) => l.split('\t').slice(0, 2).join(' -> ')).join('\n'));
})();
