import test from 'node:test';
import assert from 'node:assert/strict';
import {render,renderJson,promptOptions} from './dist/engine.mjs';
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
