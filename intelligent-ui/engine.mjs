import { z } from 'zod';
import { action, createLibrary, createParser, createStore, defineAction, defineComponent,
  evaluate, evaluateRoot, markReactive, resolveStateField } from '@openuidev/lang-core';
import { functions } from './vendor/functions/index.ts';

const children = () => z.array(z.any());
const optionalText = () => z.string().optional();
const number = () => z.number().optional();
const bound = () => { const value = z.any(); markReactive(value); return value; };
const components = [];
function component(name, props, description) {
  components.push(defineComponent({ name, props: z.object(props), description, component: null }));
}
component('Col', { children: children(), gap: number() }, 'Vertical layout; default root. gap in dp.');
component('Row', { children: children(), gap: number() }, 'Wrapping horizontal layout, suitable for narrow screens.');
component('Grid', { children: children(), columns: number() }, 'Responsive grid, 1 to 3 columns.');
component('Box', { children: children(), background: optionalText(), padding: number() }, 'Grouped content; use surface, primary or secondary colors.');
component('Card', { title: z.string(), children: children() }, 'Material card with a heading and content.');
component('Form', { children: children() }, 'Input group. Submit with Button and @ToAssistant; all variables accompany it.');
for (const name of ['Title','Text','Caption','Label','Badge','Markdown','Quote','Code','Math']) {
  component(name, { text: z.string() }, name === 'Math' ? 'LaTeX formula, no delimiters.' : 'Text content; keep Chinese labels concise.');
}
component('Divider', {}, 'Section separator.');
component('Spacer', { size: number() }, 'Vertical space in dp, max 48.');
component('List', { items: z.array(z.any()) }, 'List of strings or components.');
component('Button', { label: z.string(), action: action(), variant: optionalText() }, 'User-triggered action. variant filled, tonal, or text.');
for (const name of ['Input','TextArea','NumberInput']) {
  component(name, { value: bound(), label: z.string(), placeholder: optionalText() }, 'Editable $variable. NumberInput writes a number; other inputs write text.');
}
component('Slider', { value: bound(), min: z.number(), max: z.number(), step: number(), label: optionalText() }, 'Bound numeric value, local reactive calculation.');
for (const name of ['Checkbox','Switch']) component(name, { value: bound(), label: z.string() }, 'Boolean $variable.');
for (const name of ['Select','RadioGroup','SegmentedControl']) {
  component(name, { value: bound(), options: z.array(z.string()), label: optionalText() }, 'Choose a string value from options.');
}
component('DatePicker', { value: bound(), label: z.string() }, 'ISO yyyy-MM-dd date input.');
component('Progress', { value: z.number(), max: z.number().optional(), label: optionalText() }, 'Static progress with value label; no animation.');
component('Flashcard', { front: z.string(), back: z.string() }, 'Tap to reveal the answer, no flip animation.');
component('Accordion', { title: z.string(), children: children() }, 'Expandable details.');
component('Table', { columns: z.array(z.string()), rows: z.array(z.array(z.any())) }, 'Data table, max 100 rows; cells may contain components.');
component('Chart', { type: z.enum(['line','bar','scatter','area','pie','donut']), data: z.array(z.record(z.string(),z.any())), xKey: z.string(), series: z.array(z.string()), title: optionalText() }, 'Native static chart. Rows contain xKey labels and named numeric series; max 200 points.');
component('Image', { url: z.string(), alt: z.string() }, 'HTTPS image with a meaningful description. No video, iframe or HTML.');
component('Map', { markers: z.array(z.object({lat:z.number(),lon:z.number(),label:z.string()})), title: optionalText() }, 'Native coordinate overview with numbered places. No street tiles or navigation claims; opens maps on tap.');
component('Svg', { viewBox: z.string(), children: children(), description: z.string() }, 'Native vector diagram. viewBox: "0 0 400 240". No scripts, animations or external resources.');
component('G', { children: children() }, 'Group vector shapes.');
for (const [name, geometry] of Object.entries({Circle:['cx','cy','r'],Ellipse:['cx','cy','rx','ry'],Rect:['x','y','width','height'],Line:['x1','y1','x2','y2']})) {
  component(name, {...Object.fromEntries(geometry.map(key=>[key,z.number()])),fill:optionalText(),stroke:optionalText(),strokeWidth:number()}, 'Vector shape; colors are ink, primary, secondary, surface, muted or #RRGGBB.');
}
for (const name of ['Path','Polyline','Polygon']) component(name, {data:z.string(),fill:optionalText(),stroke:optionalText(),strokeWidth:number()}, name==='Path'?'SVG path data.':'Space-separated x,y coordinates.');
component('SvgText', {x:z.number(),y:z.number(),text:z.string(),size:number(),fill:optionalText()}, 'Text inside a vector diagram.');

const copy = defineAction({name:'CopyToClipboard',description:'Copy text only after a user click.',params:z.object({text:z.string()})});
export const library = createLibrary({id:'rikkahub-native-1',root:'Col',components,functions,actions:[copy]});
export const promptOptions = {
  inlineMode:true, bindings:true, builtinFunctions:true, toolCalls:false, editMode:false,
  preamble:'You are chatting inside RikkaHub. Use ordinary readable Markdown for explanations. When an interactive answer helps, add a complete fenced openui-lang block using this native component library. Include a short text explanation before it. Keep the user\'s language. Never use HTML, JavaScript, YouTube, video, Query, Mutation, @Run, autoplay or animation. Other enabled chat tools remain available outside the UI program.',
  additionalRules:[
    'Use interactive UI only when useful, not for every reply. Never put the whole answer only in UI; include readable text for export and fallback.',
    'Use $variables for all editable state. Inputs update locally without a model call. Button actions happen only on tap. @ToAssistant drafts a follow-up for the user to review.',
    'Use the exact listed component signatures. Maximum 300 elements, 100 table rows, 200 chart points, 24 levels, and 32 state variables. Avoid huge @Range and deeply nested expressions.',
    'Map is a coordinate overview, not a street map or directions. Images require trustworthy HTTPS URLs; omit an image rather than invent a URL.',
    'No timer-driven UI, JavaScript, arbitrary HTML, embedded websites, automatic network requests or tools. Prefer static vector diagrams, charts, math and local controls.',
  ],
  examples:[`$amount = 10\n$price = 3\nroot = Col([Title("费用计算"),NumberInput($amount,"数量"),Slider($price,1,20,1,"单价"),Text("合计：" + @Format($amount * $price,"number",2)),Button("重置",@Reset($amount,$price))])`,
    `$answer = ""\nroot = Col([Title("复习卡"),Flashcard("牛顿第二定律？","F = ma"),RadioGroup($answer,["F = ma","F = mv"],"选择正确公式"),Text($answer == "" ? "请选择答案" : ($answer == "F = ma" ? "回答正确" : "再想一想"))])`],
};

const schema = library.toJSONSchema();
const parser = createParser(schema,library.root);
const functionMap = Object.fromEntries(Object.entries(library.functions).map(([name,fn])=>[name,{...schema.functions[name],fn:fn.fn}]));
let cachedSource = null;
let cachedResult = null;
const MAX_SOURCE=64*1024, MAX_STATE=32*1024, MAX_RESULT=256*1024, MAX_NODES=300, MAX_DEPTH=24;
function checkedState(state) {
  if (!state || typeof state!=='object' || Array.isArray(state)) return {};
  if (JSON.stringify(state).length>MAX_STATE || Object.keys(state).length>32) throw Error('交互状态过大');
  return Object.fromEntries(Object.entries(state).filter(([key])=>/^\$[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(key)));
}
export function render(source,persisted={},event=null) {
  if(typeof source!=='string'||source.length>MAX_SOURCE) throw Error('交互内容过大');
  if(source!==cachedSource) { cachedResult=parser.parse(source); cachedSource=source; }
  const parsed=cachedResult;
  if(parsed.meta.incomplete) throw Error('交互内容尚未完整');
  if(parsed.queryStatements.length||parsed.mutationStatements.length) throw Error('此卡片不能自动运行工具');
  if(parsed.meta.statementCount>400) throw Error('交互内容过于复杂');
  const store=createStore();
  store.initialize(checkedState(parsed.stateDeclarations),checkedState(persisted));
  const errors=[];
  const ctx={getState:name=>store.get(name),resolveRef:()=>null,functions:functionMap,actions:schema.actions,reportError:message=>{if(errors.length<8)errors.push(message);}};
  let bindings=new Map(), actions=new Map(), nodes=0;
  function normalize(value,path='root',depth=0) {
    if(depth>MAX_DEPTH) throw Error('交互内容嵌套过深');
    if(Array.isArray(value)) {if(value.length>500)throw Error('数据项目过多');return value.map((v,i)=>normalize(v,`${path}/${i}`,depth+1));}
    if(!value||typeof value!=='object') return value;
    if(value.type==='element') {
      if(++nodes>MAX_NODES)throw Error('交互组件过多');
      const props={};
      for(const [key,v] of Object.entries(value.props)) {
        if(v?.__reactive==='assign') {
          const field=resolveStateField(key,v,store,ctx,()=>null,()=>{});
          bindings.set(`${path}/${key}`,field);
          props[key]=normalize(field.value,`${path}/${key}`,depth+1);
          props[`${key}Binding`]=`${path}/${key}`;
        } else if(v?.steps||key==='action') {
          actions.set(`${path}/${key}`,v);
          props[key]=`${path}/${key}`;
        } else props[key]=normalize(v,`${path}/${key}`,depth+1);
      }
      return {type:value.typeName,id:path,props};
    }
    return Object.fromEntries(Object.entries(value).filter(([key])=>!['__proto__','prototype','constructor'].includes(key)).map(([key,v])=>[key,normalize(v,`${path}/${key}`,depth+1)]));
  }
  function tree() {
    nodes=0; bindings=new Map(); actions=new Map();
    return normalize(evaluateRoot(parsed,{ctx,library,store}));
  }
  let root=tree();
  const effects=[];
  if(event) {
    if(event.type==='change') {
      const field=bindings.get(event.target);
      if(!field)throw Error('输入项已改变，请重试');
      if(typeof event.value==='object'||typeof event.value==='string'&&event.value.length>8192)throw Error('输入内容无效');
      field.setValue(event.value);
    } else if(event.type==='action') {
      const plan=actions.get(event.target);
      if(!plan)throw Error('按钮已改变，请重试');
      const steps=plan.steps??[plan];
      if(steps.length>16)throw Error('按钮操作过多');
      for(let i=0;i<steps.length;i++) {
        // Re-evaluate after each write, so subsequent actions see the new state.
        tree();
        const current=actions.get(event.target);
        const step=(current?.steps??[current])[i];
        if(!step)break;
        if(step.type==='set') {
          if(!Object.hasOwn(parsed.stateDeclarations,step.target))throw Error('变量未声明');
          store.set(step.target,evaluate(step.valueAST,ctx));
        } else if(step.type==='reset') {
          for(const key of step.targets?.length?step.targets:Object.keys(parsed.stateDeclarations)) {
            if(Object.hasOwn(parsed.stateDeclarations,key))store.set(key,parsed.stateDeclarations[key]);
          }
        } else if(step.type==='continue_conversation') effects.push({type:'draft',text:String(step.context??step.message??'继续讨论这张交互卡片'),state:store.getSnapshot()});
        else if(step.type==='open_url') {if(/^https?:\/\/[^\s]+$/i.test(step.url??''))effects.push({type:'url',text:step.url});}
        else if(step.type==='custom_action'&&step.name==='CopyToClipboard') effects.push({type:'copy',text:String(step.params.text??'').slice(0,8192)});
        else throw Error('不支持的按钮操作');
      }
    } else if(event.type==='reset') { for(const [key,value] of Object.entries(parsed.stateDeclarations)) store.set(key,value); }
    else throw Error('不支持的交互事件');
    root=tree();
  }
  if(!root)throw Error('无法显示此交互卡片');
  const result={version:1,root,state:checkedState(store.getSnapshot()),effects,errors:[...parsed.meta.errors.map(e=>e.message),...errors].slice(0,8)};
  if(JSON.stringify(result).length>MAX_RESULT)throw Error('交互结果过大');
  store.dispose();
  return result;
}

export function renderJson(input) {
  try { const request=JSON.parse(input);return JSON.stringify(render(request.source,request.state,request.event)); }
  catch(error) { return JSON.stringify({error:String(error.message??error).slice(0,300)}); }
}
