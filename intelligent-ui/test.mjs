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

function findNode(result,type,predicate=()=>true) {
  const found=[];
  function walk(value){if(!value||typeof value!=='object')return;if(value.type===type&&value.props&&predicate(value))found.push(value);Object.values(value).forEach(walk);}
  walk(result.root);return found[0];
}

test('real parameter program changes derived curve while dragging and restores committed values',async()=>{
  const source=await readFile(new URL('./examples/parameter-exploration.oui',import.meta.url),'utf8');
  let result=render(source);assert.deepEqual(result.errors,[]);
  const slider=findNode(result,'Slider',node=>node.props.label==='系数 a');
  const original=findNode(result,'Chart').props;
  result=render(source,result.state,{type:'change',target:slider.props.valueBinding,value:2,transient:true});
  const changed=findNode(result,'Chart').props;
  assert.deepEqual(result.errors,[]);
  assert.equal(result.state.$a,2);
  assert.deepEqual([changed.yMin,changed.yMax],[-16,16]);
  const first=original.data.find(row=>row.x===1),second=changed.data.find(row=>row.x===1);
  assert.equal(first.y,1);assert.equal(second.y,2);
  assert.notEqual((first.y-original.yMin)/(original.yMax-original.yMin),(second.y-changed.yMin)/(changed.yMax-changed.yMin));
  result=render(source,result.state,{type:'change',target:slider.props.valueBinding,value:-2,commit:true});
  const persisted=JSON.parse(JSON.stringify(result.state));
  const reopened=render(source,persisted);
  assert.equal(findNode(reopened,'Slider',node=>node.props.label==='系数 a').props.value,-2);
  assert.equal(findNode(reopened,'Chart').props.data.find(row=>row.x===1).y,-2);
});

test('canonical upstream select options forms reset carousel popover and collapsible all remain interactive',async()=>{
  const source=await readFile(new URL('./examples/parameter-exploration.oui',import.meta.url),'utf8');
  let result=render(source);
  const select=findNode(result,'Select');assert.equal(select.props.options[1].label,'直线');
  result=render(source,result.state,{type:'change',target:select.props.valueBinding,value:'linear'});
  const chart=findNode(result,'Chart');assert.equal(chart.props.data.find(row=>row.x===-2).y,-2);
  const input=findNode(result,'Input');
  result=render(source,result.state,{type:'change',target:input.props.valueBinding,value:'解释开口方向'});
  const submit=findNode(result,'Button',node=>node.props.label==='解释当前图形');
  result=render(source,result.state,{type:'action',target:submit.props.action});
  assert.equal(result.effects[0].text,'请根据当前参数解释图形的变化');
  assert.ok(!result.effects[0].text.includes('$')&&!result.effects[0].text.includes('{'));
  assert.equal(result.effects[0].state.$note,'解释开口方向');
  for(const type of ['Accordion','Carousel','Popover','Flashcard','Form'])assert.ok(findNode(result,type),type);
  const reset=findNode(result,'Button',node=>node.props.label==='恢复初始');
  result=render(source,result.state,{type:'action',target:reset.props.action});
  assert.equal(result.state.$curve,'quadratic');assert.equal(result.state.$a,1);
});

test('upstream Svg coordinate bindings move geometry and legacy signatures are retained',()=>{
  const source='$force=8\n$k=40\nextension=$force/$k\nroot=Col([Slider($force,0,20,1,"拉力"),Svg([Line(20,80,20+extension*300,80,"primary",3),Rect(20+extension*300,60,30,40,"surface","ink",2),SvgText("弹簧",20,30,12,"ink")],"0 0 240 160"),Card([Text("原生控件")],"soft"),Card("旧卡片",[Text("兼容")]),Icon("info","sm"),Checkbox($force>0,"只读显示")])';
  let result=render(source);assert.deepEqual(result.errors,[]);
  assert.equal(findNode(result,'Line').props.x2,80);
  const slider=findNode(result,'Slider');
  result=render(source,result.state,{type:'change',target:slider.props.valueBinding,value:16});
  assert.equal(findNode(result,'Line').props.x2,140);
  assert.equal(findNode(result,'Line').props.stroke,'primary');
  assert.equal(findNode(result,'SvgText').props.text,'弹簧');
  assert.equal(findNode(result,'Icon').props.size,16);
  assert.equal(findNode(result,'Card',node=>node.props.title==='旧卡片').props.children[0].props.text,'兼容');
});
