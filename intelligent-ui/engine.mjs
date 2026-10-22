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
component('Col', { children: children(), gap: number(),align:optionalText() }, 'Native vertical layout; default root. gap in dp.');
component('Row', { children: children(), gap: number(),align:optionalText(),justify:optionalText(),wrap:z.boolean().optional() }, 'Native wrapping horizontal layout, suitable for narrow screens.');
component('Grid', { children: children(), columns: number(),minChildWidth:number(),gap:number() }, 'Responsive native grid, 1 to 3 columns.');
component('Box', { children: children().optional(), direction:optionalText(), gap:number(),padding:number(),background:optionalText(),radius:optionalText(),border:z.boolean().optional(),width:z.union([z.number(),z.string()]).optional(),height:z.union([z.number(),z.string()]).optional(),flex:number(),align:optionalText(),justify:optionalText(),wrap:z.boolean().optional() }, 'Native flex group. Use native theme presets; no HTML/CSS. Legacy Box(children,background,padding) accepted.');
component('Card', { childrenOrTitle: z.union([children(),z.string()]), variantOrChildren: z.union([z.string(),children()]).optional(), padding:number(),gap:number() }, 'Native Material panel: Card(children, variant?, padding?, gap?). Legacy Card(title, children) is also accepted.');
component('Form', { children: children() }, 'Input group. Submit with Button and @ToAssistant; all variables accompany it.');
component('Carousel', {children:children(),itemWidth:number(),gap:number()}, 'Scrollable cards with previous/next buttons; no auto-scroll.');
component('Popover', {trigger:z.any(),content:z.any()}, 'Tap the trigger to open details. Both slots accept text or components.');
component('Title',{text:z.string(),size:optionalText(),color:optionalText()},'Native Material heading. Sizes sm/md/lg/xl/2xl/3xl use theme typography.');
component('Caption',{text:z.string(),color:optionalText()},'Native secondary text.');
component('Label',{text:z.string()},'Native field or group label.');
component('Badge',{text:z.string(),color:optionalText(),icon:optionalText()},'Native theme status badge, not a button.');
component('Markdown',{text:z.string()},'Readable prose with Markdown.');
component('Quote',{text:z.string(),cite:optionalText()},'Quotation with optional source.');
component('Code',{code:z.string(),language:optionalText()},'Source code with optional language.');
component('Math',{tex:z.string(),inline:z.boolean().optional()},'LaTeX formula, no delimiters.');
component('Text',{text:z.union([z.string(),children()]),size:optionalText(),color:optionalText(),weight:optionalText(),italic:z.boolean().optional(),lineThrough:z.boolean().optional(),tabularNums:z.boolean().optional(),maxLines:number()},'Native text or inline components; Markdown emphasis is supported.');
component('Link',{text:z.string(),url:z.string()},'A user-clicked HTTP(S) link.');
component('Divider', {}, 'Section separator.');
component('Spacer', { size: number() }, 'Vertical space in dp, max 48.');
component('List', { items: z.array(z.any()),marker:optionalText(),timeline:z.boolean().optional() }, 'Native list. Markers bullet, number, none; optional timeline. Legacy numbers/timeline accepted.');
component('ListItem',{titleOrContent:z.any(),descriptionOrLabel:optionalText(),marker:optionalText(),children:children().optional()},'ListItem(title,description?,marker?,children?). Legacy ListItem(component,label) accepted.');
component('Button', { label: z.string(), action: action().optional(), variant: optionalText(),color:optionalText(),size:optionalText(),icon:optionalText(),disabled:z.boolean().optional(),block:z.boolean().optional() }, 'User-triggered native button. Variants solid, outline, soft, ghost (legacy filled, tonal, text accepted).');
component('IconButton',{icon:z.string(),action:action().optional(),label:optionalText(),variant:optionalText()},'Accessible native icon button; include a descriptive label.');
component('Pressable',{children:children(),action:action().optional(),selectedOrLabel:z.union([z.boolean(),z.string()]).optional(),padding:number(),background:optionalText(),radius:optionalText()},'Clickable native container; do not nest other buttons or inputs inside it.');
component('Icon',{name:z.string(),size:z.union([z.number(),z.string()]).optional(),colorOrLabel:optionalText()},'A bundled Lucide icon. Size sm, md, lg, xl or dp; no network request.');
for (const name of ['Input','TextArea','NumberInput']) {
  component(name, { value: bound(), label: optionalText(), typeOrPlaceholder: optionalText(), placeholder:optionalText() }, 'Native editable $variable. Input(value,label?,type?,placeholder?) accepts text/number; NumberInput always writes numbers.');
}
component('Slider', { value: bound(), min: z.number(), max: z.number(), step: number(), label: optionalText(), unit:optionalText(),marks:z.array(z.object({value:z.number(),label:z.string()})).optional() }, 'Native draggable numeric control. Bind a $variable and derive Chart/Svg geometry from it; updates while dragging.');
component('Checkbox', {checked:bound(),label:z.string(),lineThrough:z.boolean().optional()}, 'Native boolean checkbox with a $variable.');
component('Switch', {value:bound(),label:z.string()}, 'Native boolean switch with a $variable.');
const options=()=>z.array(z.union([z.string(),z.object({value:z.string(),label:z.string(),description:optionalText()})]));
component('Select',{value:bound(),options:options(),label:optionalText(),placeholder:optionalText()},'Native dropdown; options may be strings or {value,label} records.');
component('RadioGroup',{value:bound(),options:options(),label:optionalText(),variant:optionalText()},'Native radio choices, including {value,label,description} options.');
component('SegmentedControl',{value:bound(),options:options(),blockOrLabel:z.union([z.boolean(),z.string()]).optional()},'Native single-choice segmented buttons. Third parameter is block; legacy label is accepted.');
component('DatePicker', { value: bound(), label: z.string() }, 'ISO yyyy-MM-dd date input.');
component('Timer',{seconds:bound(),label:optionalText()},'User-started countdown with pause and reset. On e-ink the running display stays static until completion; updates the bound seconds on pause/reset/completion.');
component('Progress', { value: z.number(), max: z.number().optional(), label: optionalText() }, 'Static progress with value label; no animation.');
component('Flashcard', { front: z.any(), back: z.any(),hint:optionalText() }, 'Tap to reveal the answer; sides accept text or components. No flip animation.');
component('Celebration',{when:z.boolean(),message:optionalText()},'Static success confirmation when the condition becomes true; no confetti or animation.');
component('Accordion', { title: z.string(), children: children() }, 'Expandable details.');
component('Table', { columns: z.array(z.string()), rows: z.array(z.array(z.any())) }, 'Data table, max 100 rows; cells may contain components.');
component('Chart', { type: z.enum(['line','bar','scatter','area','pie','donut']), data: z.array(z.record(z.string(),z.any())), x:optionalText(), series:z.array(z.string()).optional(),format:optionalText(),currency:optionalText(),stacked:z.boolean().optional(),height:number(),yMin:number(),yMax:number(),title:optionalText() }, 'Reactive native chart: Chart(type,data,x?,series?,format?,currency?,stacked?,height?,yMin?,yMax?,title?). For parameter exploration set FIXED yMin/yMax so rescaling does not hide curve changes. Legacy fifth-argument title is accepted. Max 200 points.');
component('Image', { src: z.string(), alt: z.string(),aspectRatio:number(),radius:optionalText(),fit:optionalText() }, 'HTTPS image with a meaningful description. No video, iframe or HTML.');
component('SearchImage',{query:z.string(),aspectRatioOrAlt:z.union([z.number(),z.string()]).optional(),radius:optionalText(),fit:optionalText()},'User-triggered Wikipedia/Wikimedia photo search with source attribution. Optional aspectRatio; never searches before a click.');
component('Map', { markers: z.array(z.any()), routeOrTitle:z.union([z.boolean(),z.string()]).optional(),selected:bound().optional(),height:number() }, 'Native map; click to load streets or route. Optional selected $variable records the clicked marker label. No automatic network request.');
component('MapMarker',{labelOrLat:z.union([z.string(),z.number()]),latOrLon:z.number(),lngOrLabel:z.union([z.number(),z.string()]),category:optionalText(),icon:optionalText()},'MapMarker(label,lat,lng,category?,icon?). Legacy MapMarker(lat,lon,label) accepted.');
component('Svg', { childrenOrViewBox:z.union([children(),z.string()]),viewBoxOrChildren:z.union([z.string(),children()]),widthOrDescription:z.union([z.number(),z.string()]).optional(),height:z.union([z.number(),z.string()]).optional() }, 'Reactive native vector diagram: Svg(children,viewBox,width?,height?). Legacy Svg(viewBox,children,description) accepted. Shapes can use $variables and expressions.');
component('G', { children: children(),transform:optionalText(),opacity:number() }, 'Group native vector shapes.');
for (const [name, geometry] of Object.entries({Circle:['cx','cy','r'],Ellipse:['cx','cy','rx','ry'],Rect:['x','y','width','height']})) {
  component(name, {...Object.fromEntries(geometry.map(key=>[key,z.number()])),fill:optionalText(),stroke:optionalText(),strokeWidth:number(),opacity:number(),...(name==='Rect'?{rx:number()}:{})}, 'Native vector shape; colors use theme presets or #RRGGBB.');
}
component('Line',{x1:z.number(),y1:z.number(),x2:z.number(),y2:z.number(),strokeOrFill:optionalText(),widthOrStroke:z.union([z.number(),z.string()]).optional(),opacityOrWidth:number()},'Line(x1,y1,x2,y2,stroke?,strokeWidth?,opacity?). Legacy fill/stroke/width accepted.');
for (const name of ['Path','Polyline','Polygon']) component(name, {data:z.string(),fill:optionalText(),stroke:optionalText(),strokeWidth:number(),opacity:number()}, name==='Path'?'Native path data.':'Space-separated x,y coordinates.');
component('SvgText', {textOrX:z.union([z.string(),z.number()]),xOrY:z.number(),yOrText:z.union([z.number(),z.string()]),size:number(),fill:optionalText(),anchor:optionalText(),weight:optionalText()}, 'SvgText(text,x,y,size?,fill?,anchor?,weight?). Legacy SvgText(x,y,text) accepted.');

const copy = defineAction({name:'CopyToClipboard',description:'Copy text only after a user click.',params:z.object({text:z.string()})});
export const library = createLibrary({id:'rikkahub-native-1',root:'Col',components,functions,actions:[copy]});
export const promptOptions = {
  inlineMode:true, bindings:true, builtinFunctions:true, toolCalls:false, editMode:false,
  preamble:'You are chatting inside RikkaHub. Use ordinary readable Markdown for explanations. When an interactive answer helps, add a complete fenced openui-lang block using this native component library. Include a short text explanation before it. Keep the user\'s language. Never use HTML, JavaScript, YouTube, video, Query, Mutation, @Run, autoplay or decorative animation. Other enabled chat tools remain available outside the UI program.',
  additionalRules:[
    'When the request involves parameter changes, science or math exploration, learning plans, comparisons, checklists, steps or a control panel, proactively provide a WORKING native UI, not a static card or a suggestion to make one. Plain factual questions may stay in Markdown. Include a readable explanation before the UI.',
    'Use $variables for all editable state. Inputs update locally without a model call. Button actions happen only on tap. @ToAssistant drafts a follow-up for the user to review.',
    'Use the exact listed component signatures. Maximum 300 elements, 100 table rows, 200 chart points, 24 levels, and 32 state variables. Avoid huge @Range and deeply nested expressions.',
    'Map loads street tiles and a driving-road overview only after a click; it is not walking/transit guidance. Image requires a trustworthy HTTPS URL; use SearchImage when a photo must be found.',
    'Only Timer may count down after a user starts it. No JavaScript, arbitrary HTML, embedded websites, automatic network requests or tools. Prefer static vector diagrams, charts, math and local controls.',
    'For draggable science/math: declare literal $parameter defaults, bind Slider($parameter,...), derive rows with @Each(@Range(...),"x",{x:x,y:...$parameter...}), and pass the derived rows to Chart. Use fixed yMin/yMax (arguments 9 and 10) and axes, or fixed Svg viewBox. Otherwise autoscaling can hide the change. Calculations and geometry MUST read the bound variable; do not precompute static values.',
    'Form values travel invisibly with the next user message. @ToAssistant contains only natural language intent. Never put variable names, state JSON, binding metadata, or a fake UI_ACTION protocol in the user-visible draft or answer.',
    'Native widgets provide the visual style. Compose built-in controls and meaningful diagrams; do not invent HTML/CSS skins, decorate every paragraph as a card, or use unsupported signatures. $variable defaults must be literals, derived expressions belong in ordinary named statements.',
  ],
  examples:[`$amount = 10\n$price = 3\nroot = Col([Title("费用计算"),NumberInput($amount,"数量"),Slider($price,1,20,1,"单价"),Text("合计：" + @Format($amount * $price,"number",2)),Button("重置",@Reset($amount,$price))])`,
    `$answer = ""\nroot = Col([Title("复习卡"),Flashcard("牛顿第二定律？","F = ma"),RadioGroup($answer,["F = ma","F = mv"],"选择正确公式"),Text($answer == "" ? "请选择答案" : ($answer == "F = ma" ? "回答正确" : "再想一想"))])`],
};
promptOptions.examples.push(`$a = 1\n$shift = 0\npoints = @Each(@Range(-2,2.01,0.1),"x",{x:x,y:$a*x*x+$shift})\nroot = Col([Title("拖动观察抛物线"),Slider($a,-3,3,0.1,"系数 a"),Slider($shift,-4,4,0.5,"上下平移"),Chart("line",points,"x",["y"],"number",null,false,240,-16,16,"y = a x² + b"),Button("恢复初始",@Reset($a,$shift)),Button("解释当前图形",@ToAssistant("请解释当前曲线的变化"))])`);

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
      // Keep canonical upstream positional signatures while retaining programs saved by v1.
      const type=value.typeName;
      if(type==='Card') {
        const legacy=typeof props.childrenOrTitle==='string';
        props.children=legacy?(props.variantOrChildren??[]):(props.childrenOrTitle??[]);
        if(legacy)props.title=props.childrenOrTitle; else props.variant=props.variantOrChildren;
        delete props.childrenOrTitle;delete props.variantOrChildren;
      } else if(type==='Box'&&props.direction&&!['row','col'].includes(props.direction)) {
        props.background=props.direction;props.padding=props.gap;delete props.direction;delete props.gap;
      } else if(type==='Checkbox') { props.value=props.checked;props.valueBinding=props.checkedBinding; }
      else if(type==='Code')props.text=props.code;
      else if(type==='Math')props.text=props.tex;
      else if(type==='List')props.variant=props.timeline||props.marker==='timeline'?'timeline':props.marker==='number'||props.marker==='numbers'?'numbers':props.marker==='none'?'none':'bullets';
      else if(type==='ListItem') {
        const legacy=typeof props.titleOrContent!=='string';
        props.content=legacy?props.titleOrContent:[props.titleOrContent,...(props.descriptionOrLabel?[props.descriptionOrLabel]:[]),...(props.children??[])];
        props.label=legacy?props.descriptionOrLabel:props.marker;
        delete props.titleOrContent;delete props.descriptionOrLabel;delete props.children;
      }
      else if(['Input','TextArea','NumberInput'].includes(type)) {
        if(['text','number'].includes(props.typeOrPlaceholder)) props.inputType=props.typeOrPlaceholder;
        else if(props.typeOrPlaceholder!=null)props.placeholder=props.typeOrPlaceholder;
        delete props.typeOrPlaceholder;
      } else if(type==='SegmentedControl') {
        if(typeof props.blockOrLabel==='string')props.label=props.blockOrLabel;else props.block=props.blockOrLabel;
        delete props.blockOrLabel;
      } else if(type==='Chart') {
        props.xKey=props.x??Object.keys(props.data?.[0]??{})[0]??'x';
        props.series??=Object.keys(props.data?.[0]??{}).filter(k=>k!==props.xKey&&typeof props.data[0][k]==='number');
        if(props.format&&!['number','currency','percent'].includes(props.format)) {props.title??=props.format;props.format='number';}
      } else if(type==='Svg') {
        const legacy=typeof props.childrenOrViewBox==='string';
        props.children=legacy?props.viewBoxOrChildren:props.childrenOrViewBox;
        props.viewBox=legacy?props.childrenOrViewBox:props.viewBoxOrChildren;
        if(legacy)props.description=props.widthOrDescription;else props.width=props.widthOrDescription;
        delete props.childrenOrViewBox;delete props.viewBoxOrChildren;delete props.widthOrDescription;
      } else if(type==='SvgText') {
        const legacy=typeof props.textOrX==='number';
        props.text=legacy?props.yOrText:props.textOrX;props.x=legacy?props.textOrX:props.xOrY;props.y=legacy?props.xOrY:props.yOrText;
        delete props.textOrX;delete props.xOrY;delete props.yOrText;
      } else if(type==='Line') {
        const legacy=typeof props.widthOrStroke==='string';
        props.fill='none';props.stroke=legacy?props.widthOrStroke:props.strokeOrFill;props.strokeWidth=legacy?props.opacityOrWidth:props.widthOrStroke;
        if(!legacy)props.opacity=props.opacityOrWidth;
        delete props.strokeOrFill;delete props.widthOrStroke;delete props.opacityOrWidth;
      } else if(type==='MapMarker') {
        const legacy=typeof props.labelOrLat==='number';
        props.label=legacy?props.lngOrLabel:props.labelOrLat;props.lat=legacy?props.labelOrLat:props.latOrLon;props.lon=legacy?props.latOrLon:props.lngOrLabel;
        delete props.labelOrLat;delete props.latOrLon;delete props.lngOrLabel;
      } else if(type==='Map') {
        if(typeof props.routeOrTitle==='string')props.title=props.routeOrTitle;else props.route=props.routeOrTitle;
        delete props.routeOrTitle;
      } else if(type==='Image') { props.url=props.src; }
      else if(type==='SearchImage') {
        if(typeof props.aspectRatioOrAlt==='string')props.alt=props.aspectRatioOrAlt;else props.aspectRatio=props.aspectRatioOrAlt;
        props.alt??=props.query;delete props.aspectRatioOrAlt;
      } else if(type==='Icon') {
        props.size=typeof props.size==='string'?({sm:16,md:24,lg:32,xl:40}[props.size]??24):(props.size??24);
        props.label=props.colorOrLabel;delete props.colorOrLabel;
      } else if(type==='Pressable') {
        if(typeof props.selectedOrLabel==='string')props.label=props.selectedOrLabel;else props.selected=props.selectedOrLabel;
        delete props.selectedOrLabel;
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
