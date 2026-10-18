import test from 'node:test';
import assert from 'node:assert/strict';
import {render,renderJson,promptOptions,library} from './dist/engine.mjs';
import {readFile,readdir} from 'node:fs/promises';
const source='$n = 2\nroot = Col([Slider($n,0,10,1,"N"),Text("Result: " + @Pow($n,2)),Button("Plus",@Set($n,$n+1)),Button("Reset",@Reset($n))])';
test('prompt examples render without errors',()=>{for(const s of promptOptions.examples)assert.deepEqual(render(s).errors,[]);});
test('bindings actions persisted state and reset',()=>{
  let r=render(source); assert.equal(r.state.$n,2);
  r=render(source,r.state,{type:'change',target:'root/children/0/value',value:4});assert.equal(r.state.$n,4);
  r=render(source,r.state,{type:'action',target:'root/children/2/action'});assert.equal(r.state.$n,5);assert.equal(r.root.props.children[1].props.text,'Result: 25');
  assert.equal(render(source,r.state).state.$n,5);
  r=render(source,r.state,{type:'reset'});assert.equal(r.state.$n,2);
  assert.equal(render(source,{$n:9},{type:'action',target:'root/children/3/action'}).state.$n,2);
});
test('effects require an explicit action and do not auto send',()=>{
  const s='root = Col([Button("Discuss",@ToAssistant("继续")),Button("Copy",@CopyToClipboard("abc")),Button("Open",@OpenUrl("https://example.com"))])';
  assert.deepEqual(render(s).effects,[]);
  assert.equal(render(s,{}, {type:'action',target:'root/children/0/action'}).effects[0].type,'draft');
  assert.equal(render(s,{}, {type:'action',target:'root/children/0/action'}).effects[0].text,'继续');
  assert.equal(render(s,{}, {type:'action',target:'root/children/1/action'}).effects[0].text,'abc');
  assert.equal(render(s,{}, {type:'action',target:'root/children/2/action'}).effects[0].text,'https://example.com');
});
test('formatting works without Intl and preserves decimal rounding',()=>{
  const s='root = Col([Text(@Format(12345.678,"currency",2,"CNY")),Text(@FormatDate("2026-10-19","iso")),Text(@FormatDate("2026-10-19","date"))])';
  assert.deepEqual(render(s).root.props.children.map(v=>v.props.text),['¥12,345.68','2026-10-19','Oct 19, 2026']);
});
test('graphics schemas and functions are executable',()=>{
  const r=render('root = Col([Chart("line",[{x:"一",y:2},{x:"二",y:4}],"x",["y"],"趋势"),Svg("0 0 100 100",[Circle(50,50,20,"primary"),SvgText(10,15,"图",12)],"示意图"),Map([{lat:39.9,lon:116.4,label:"北京"}],"地点"),Text("合计"+@Sum([1,2,3]))])');
  assert.deepEqual(r.errors,[]);assert.equal(r.root.props.children[3].props.text,'合计6');
});
test('invalid input and resource limits yield recoverable errors',()=>{
  assert.throws(()=>render('x'.repeat(65537)));
  assert.throws(()=>render(source,{}, {type:'change',target:'missing',value:2}));
  assert.throws(()=>render(source,{}, {type:'change',target:'root/children/0/value',value:{bad:true}}));
  assert.throws(()=>render(source,{$n:'x'.repeat(32769)}));
  assert.throws(()=>render('root = Col(['+Array.from({length:301},()=> 'Text("x")').join(',')+'])'));
  assert.ok(JSON.parse(renderJson('{invalid')).error);
});

test('all supported upstream primitives render and new actions update bound state',async()=>{
  const source=await readFile(new URL('./examples/all-components.oui',import.meta.url),'utf8');
  const result=render(source);
  assert.deepEqual(result.errors,[]);
  const nodes=[];
  function walk(value){if(!value||typeof value!=='object')return;if(value.type&&value.props)nodes.push(value);Object.values(value).forEach(walk);}
  walk(result.root);
  const rendered=new Set(nodes.map(n=>n.type));
  assert.deepEqual([...rendered].sort(),Object.keys(library.components).sort());
  const upstream=JSON.parse(await readFile(new URL('./upstream-components.json',import.meta.url),'utf8'));
  for(const component of upstream.components) if(!Object.hasOwn(upstream.excluded,component)) assert.ok(rendered.has(component),component);
  for(const type of ['IconButton','Pressable']) {
    const node=nodes.find(n=>n.type===type);
    const changed=render(source,result.state,{type:'action',target:node.props.action});
    assert.equal(changed.state.$n,4,type);
  }
  const timer=nodes.find(n=>n.type==='Timer');
  assert.equal(render(source,result.state,{type:'change',target:timer.props.secondsBinding,value:0}).state.$seconds,0);
  assert.ok(nodes.find(n=>n.type==='Map').props.markers.every(n=>n.type==='MapMarker'));
  assert.deepEqual(result.effects,[],'network/media elements must not trigger effects merely by rendering');
});

test('every bundled icon uses native vector primitives and matches web assets',async()=>{
  const base=new URL('../app/src/main/assets/intelligent-ui/icons/',import.meta.url);
  const web=new URL('../web-ui/public/intelligent-ui/icons/',import.meta.url);
  const names=await readdir(base);assert.equal(names.length,1671);
  for(const name of names){
    const text=await readFile(new URL(name,base),'utf8');
    assert.equal(text,await readFile(new URL(name,web),'utf8'));
    const icon=JSON.parse(text);
    assert.equal(icon.viewBox,'0 0 24 24');
    assert.ok(icon.children.every(n=>['Path','Rect','Circle','Ellipse','Line','Polyline','Polygon'].includes(n.type)));
  }
});
