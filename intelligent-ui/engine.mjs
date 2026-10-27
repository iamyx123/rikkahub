import { z } from 'zod';
import { action, createLibrary, createParser, createStore, defineAction, defineComponent,
  evaluate, evaluateRoot, markReactive, resolveStateField, tagSchemaId } from '@openuidev/lang-core';
import { functions as upstreamFunctions } from './vendor/functions/index.ts';
import { teachingFunctions } from './functions/teaching.ts';

// 参数类型在提示词里印成简短的名字；旧版卡片在同一位置传过别的类型，所以这里不做严格校验
const loose = (name) => { const schema = z.any(); tagSchemaId(schema, name); return schema; };
const Node = () => loose('Node');
const Nodes = () => loose('Node[]');
const Color = () => loose('Color').optional();
const text = () => z.string().optional();
const number = () => z.number().optional();
const flag = () => z.boolean().optional();
const bound = () => { const value = z.any(); markReactive(value); return value; };
const options = () => z.array(z.union([z.string(), z.object({ value: z.string(), label: z.string(), description: text() })]));
const paint = () => ({ fill: Color(), stroke: Color(), strokeWidth: number(), opacity: number() });

const components = [];
function component(name, props, description = '') {
  components.push(defineComponent({ name, props: z.object(props), description, component: null }));
}

// 布局
component('Col', { children: Nodes(), gap: number(), align: text() }, 'Vertical stack; the root of every card.');
component('Row', { children: Nodes(), gap: number(), align: text(), justify: text(), wrap: flag() }, 'Horizontal row that wraps on narrow screens.');
component('Grid', { children: Nodes(), columns: number(), minChildWidth: number(), gap: number() }, '1 to 3 equal columns.');
component('Box', { children: Nodes().optional(), direction: text(), gap: number(), padding: number(), background: Color(), radius: text(), border: flag(), width: loose('Length').optional(), height: loose('Length').optional(), flex: number(), align: text(), justify: text(), wrap: flag() }, 'Group with optional padding, background and border; direction "row" or "col".');
component('Card', { children: Nodes(), variant: loose('string').optional(), padding: number(), gap: number() }, 'Panel that groups related blocks.');
component('Form', { children: Nodes() }, 'Inputs whose values travel with its submit Button\'s @ToAssistant.');
component('Carousel', { children: Nodes(), itemWidth: number(), gap: number() }, 'Swipeable cards.');
component('Popover', { trigger: Node(), content: Node() }, 'Tap the trigger to show the content, e.g. a hint.');
component('Accordion', { title: z.string(), children: Nodes() }, 'Collapsed section; tap the title to expand. Good for hints and full solutions.');
component('Divider', {});
component('Spacer', { size: number() }, 'Vertical space in dp.');

// 文字
component('Title', { text: z.string(), size: text(), color: Color() }, 'Heading; size sm, md, lg, xl.');
component('Text', { text: loose('string | Node[]'), size: text(), color: Color(), weight: text(), italic: flag(), lineThrough: flag(), tabularNums: flag(), maxLines: number() }, 'Paragraph rendered as Markdown, so $LaTeX$, $\\ce{...}$ and $\\smiles{...}$ work. size xs, sm, md, lg.');
component('Markdown', { text: z.string() }, 'Longer Markdown with lists, tables, $LaTeX$, $\\ce{...}$ and $\\smiles{...}$.');
component('Math', { tex: z.string(), inline: flag() }, 'Display formula without $ delimiters; \\ce{...} and \\smiles{...} also work here.');
component('Caption', { text: z.string(), color: Color() }, 'Small secondary text.');
component('Label', { text: z.string() });
component('Badge', { text: z.string(), color: Color(), icon: text() }, 'Small tag for a fact or status.');
component('Quote', { text: z.string(), cite: text() });
component('Code', { code: z.string(), language: text() });
component('Link', { text: z.string(), url: z.string() });
component('List', { items: Nodes(), marker: text(), timeline: flag() }, 'marker "bullet", "number" or "none".');
component('ListItem', { title: Node(), description: loose('string').optional(), marker: text(), children: Nodes().optional() });

// 输入
component('Button', { label: z.string(), action: action().optional(), variant: text(), color: Color(), size: text(), icon: text(), disabled: flag(), block: flag() }, 'variant solid, outline, soft or ghost.');
component('IconButton', { icon: z.string(), action: action().optional(), label: text(), variant: text() });
component('Pressable', { children: Nodes(), action: action().optional(), selected: loose('boolean').optional(), padding: number(), background: Color(), radius: text() }, 'Tappable area, e.g. an answer choice; selected highlights it.');
for (const name of ['Input', 'TextArea', 'NumberInput']) {
  component(name, { value: bound(), label: text(), placeholder: text(), type: text() }, name === 'NumberInput' ? 'Writes a number.' : '');
}
component('Slider', { value: bound(), min: z.number(), max: z.number(), step: number(), label: text(), unit: text(), marks: z.array(z.object({ value: z.number(), label: z.string() })).optional() }, 'Drag to change a number; everything computed from it updates live.');
component('Checkbox', { value: bound(), label: z.string(), lineThrough: flag() });
component('Switch', { value: bound(), label: z.string() });
component('Select', { value: bound(), options: options(), label: text(), placeholder: text() });
component('RadioGroup', { value: bound(), options: options(), label: text(), variant: text() }, 'One choice; good for quiz options.');
component('SegmentedControl', { value: bound(), options: options(), block: loose('boolean').optional() }, 'A few side-by-side choices.');
component('DatePicker', { value: bound(), label: z.string() }, 'yyyy-MM-dd');
component('Timer', { seconds: bound(), label: text() }, 'Countdown the user starts, e.g. for a timed quiz.');
component('Progress', { value: z.number(), max: number(), label: text() });

// 练习
component('Flashcard', { front: Node(), back: Node(), hint: text() }, 'Tap to reveal the back: question and answer, term and meaning.');
component('Celebration', { when: z.boolean(), message: text() }, 'Shown when the condition becomes true, e.g. a correct answer.');

// 数据与媒体
component('Table', { columns: z.array(z.string()), rows: z.array(z.array(z.any())) }, 'Cells may be text (Markdown, formulas) or components.');
component('Chart', { type: z.enum(['line', 'bar', 'scatter', 'area', 'pie', 'donut']), data: z.array(z.record(z.string(), z.any())), x: text(), series: z.array(z.string()).optional(), format: text(), currency: text(), stacked: flag(), height: number(), yMin: number(), yMax: number(), title: text() }, 'Rows like {x: 1, y: 2}; x names the x field, series the plotted fields. Fix yMin and yMax when a Slider changes the curve, so the change stays visible.');
component('Image', { src: z.string(), alt: z.string(), aspectRatio: number(), radius: text(), fit: text() }, 'Image from an https URL.');
component('SearchImage', { query: z.string(), aspectRatio: loose('number').optional(), radius: text(), fit: text() }, 'Photo of a real thing, found on Wikipedia by this query.');
component('Icon', { name: z.string(), size: loose('number | string').optional(), color: Color() }, 'Lucide icon name; size sm, md, lg, xl.');
component('Map', { markers: Nodes(), route: loose('boolean').optional(), selected: bound().optional(), height: number() }, 'Map of MapMarker places; route draws the driving route between them.');
component('MapMarker', { label: loose('string'), lat: z.number(), lng: loose('number'), category: text(), icon: text() });

// 绘图
component('Svg', { children: Nodes(), viewBox: loose('string'), width: loose('number').optional(), height: number() }, 'Drawing that scales to the card width. Shapes take expressions, so $variables can move them.');
component('G', { children: Nodes(), transform: text(), opacity: number() }, 'Group; transform like "rotate(30 50 50)" or "translate(10 0)".');
component('Circle', { cx: z.number(), cy: z.number(), r: z.number(), ...paint() });
component('Ellipse', { cx: z.number(), cy: z.number(), rx: z.number(), ry: z.number(), ...paint() });
component('Rect', { x: z.number(), y: z.number(), width: z.number(), height: z.number(), ...paint(), rx: number() });
component('Line', { x1: z.number(), y1: z.number(), x2: z.number(), y2: z.number(), stroke: Color(), strokeWidth: loose('number').optional(), opacity: number(), dashed: flag() });
component('Path', { d: z.string(), ...paint(), dashed: flag() }, 'SVG path data like "M 0 0 Q 50 40 100 0".');
component('Polyline', { points: z.string(), ...paint(), dashed: flag() }, 'points like "0,0 10,5 20,0"; usually fill "none".');
component('Polygon', { points: z.string(), ...paint() });
component('SvgText', { text: loose('string'), x: loose('number'), y: loose('number'), size: number(), fill: Color(), anchor: text(), weight: text() }, 'anchor start, middle or end.');

const copy = defineAction({ name: 'CopyToClipboard', description: 'Copy text to the clipboard.', params: z.object({ text: z.string() }) });
const functions = [...upstreamFunctions, ...teachingFunctions];
export const library = createLibrary({
  id: 'rikkahub-native-2',
  root: 'Col',
  components,
  functions,
  actions: [copy],
  componentGroups: [
    { name: 'Layout', components: ['Col', 'Row', 'Grid', 'Box', 'Card', 'Form', 'Carousel', 'Popover', 'Accordion', 'Divider', 'Spacer'],
      notes: ['- Color is "ink", "muted", "faint", "surface", "info", "success", "warning", "danger", "purple", "pink", "chart1" to "chart6", or #RRGGBB.'] },
    { name: 'Text', components: ['Title', 'Text', 'Markdown', 'Math', 'Caption', 'Label', 'Badge', 'Quote', 'Code', 'Link', 'List', 'ListItem'],
      notes: ['- Inside a string, write a LaTeX backslash doubled, as JSON does: "$\\\\frac{1}{2}$", "$\\\\ce{H2O}$".'] },
    { name: 'Inputs', components: ['Button', 'IconButton', 'Pressable', 'Input', 'TextArea', 'NumberInput', 'Slider', 'Checkbox', 'Switch', 'Select', 'RadioGroup', 'SegmentedControl', 'DatePicker', 'Timer', 'Progress'],
      notes: ['- An input\'s first argument is its value, usually a $variable that other statements read.'] },
    { name: 'Practice', components: ['Flashcard', 'Celebration'] },
    { name: 'Data and media', components: ['Table', 'Chart', 'Image', 'SearchImage', 'Icon', 'Map', 'MapMarker'] },
    { name: 'Drawing', components: ['Svg', 'G', 'Circle', 'Ellipse', 'Rect', 'Line', 'Path', 'Polyline', 'Polygon', 'SvgText'],
      notes: ['- Shapes with a stroke and no fill stay hollow. Give areas a light fill ("surface", "info", #DCEBFF) and outlines stroke "ink".'] },
  ],
});

const exampleSolution = String.raw`root = Col([Row([Badge("第 " + ($step + 1) + " / 3 步", "info"), Text(s.title, "md", null, "semibold")], 2), Math(s.tex), Text(s.note, "sm", "muted"), Row([Button("上一步", @Set($step, @Clamp($step - 1, 0, 2)), "outline", null, null, null, $step == 0), Button("下一步", @Set($step, @Clamp($step + 1, 0, 2)), "solid", null, null, null, $step == 2), Button("这一步没看懂", @ToAssistant("第" + ($step + 1) + "步「" + s.title + "」没看懂，请换个方式讲"), "ghost")], 2), Accordion("检验", [Text("代入 $x = 2$：$4 - 10 + 6 = 0$ ✓")])])
$step = 0
steps = [{title: "移项", tex: "x^2 - 5x + 6 = 0", note: "把所有项移到等号左边。"}, {title: "因式分解", tex: "(x - 2)(x - 3) = 0", note: "找两个数：积为 6、和为 -5。"}, {title: "得出两根", tex: "x_1 = 2,\\quad x_2 = 3", note: "每个因式分别等于 0。"}]
s = steps[$step]`;

const exampleExplore = String.raw`root = Col([Title("顶点式 $y = a(x-h)^2 + k$", "md"), Slider($a, -3, 3, 0.5, "a（开口与宽窄）"), Slider($h, -3, 3, 0.5, "h（左右平移）"), Slider($k, -4, 4, 0.5, "k（上下平移）"), Chart("line", points, "x", ["y"], null, null, false, 220, -8, 8), Text("顶点 $(" + $h + ", " + $k + ")$，开口" + ($a > 0 ? "向上" : $a < 0 ? "向下" : "——此时不是抛物线"), "sm")])
$a = 1
$h = 0
$k = 0
points = @Each(@Range(-4, 4.01, 0.25), "x", {x: x, y: $a * (x - $h) * (x - $h) + $k})`;

const exampleDrawing = String.raw`root = Col([Slider($angle, 0, 90, 5, "角度 θ", "°"), Svg([Line(110, 130, 210, 130, "muted", 1), Line(110, 130, 110, 30, "muted", 1), Path("M 110 50 A 80 80 0 0 1 190 130", "none", "faint", 1, null, true), Line(110, 130, px, py, "ink", 2), Line(px, py, px, 130, "info", 2, null, true), Line(110, 130, px, 130, "success", 3), Circle(px, py, 4, "ink"), SvgText("θ", 128, 124, 12, "ink"), SvgText("sin θ", px + 6, (py + 130) / 2, 11, "info"), SvgText("cos θ", (110 + px) / 2, 146, 11, "success", "middle")], "0 0 240 160"), Text("$\\sin " + $angle + "^\\circ \\approx " + @Round(@Sin(@Rad($angle)), 3) + "$，$\\cos " + $angle + "^\\circ \\approx " + @Round(@Cos(@Rad($angle)), 3) + "$", "sm")])
$angle = 30
r = 80
px = 110 + r * @Cos(@Rad($angle))
py = 130 - r * @Sin(@Rad($angle))`;

const exampleQuiz = String.raw`root = Col([Text("下列反应属于置换反应的是：", "md"), RadioGroup($pick, [{value: "A", label: "$\\ce{CaCO3 ->[高温] CaO + CO2 ^}$"}, {value: "B", label: "$\\ce{Fe + CuSO4 = FeSO4 + Cu}$"}, {value: "C", label: "$\\ce{NaOH + HCl = NaCl + H2O}$"}]), Button("提交", @Set($checked, true), "solid", null, null, null, $pick == ""), $checked ? Text(right ? "✓ 正确：单质 + 化合物 → 新单质 + 新化合物。" : "✗ 再想想：看反应物里有没有单质。", "sm", right ? "success" : "danger") : null, Celebration($checked && right, "答对了"), $checked ? Button("再来一题", @ToAssistant("再出一道同类型的题"), "soft") : null])
$pick = ""
$checked = false
right = $pick == "B"`;

export const promptOptions = {
  inlineMode: true, bindings: true, builtinFunctions: true, toolCalls: false, editMode: false,
  preamble: 'You are chatting inside RikkaHub, a study app used to explain problems step by step. Answer in Markdown as usual. ' +
    'Whenever trying, seeing or doing something would make it click faster — a step-by-step solution, a parameter to drag, a diagram that changes, a quick quiz, a comparison or a checklist — put a working interactive card right where it helps, as a fenced ```openui-lang block. ' +
    'A reply may hold several cards; keep the explanation in Markdown around them and write in the user\'s language.',
  additionalRules: [
    'Make each card work by itself: keep inputs and the current step in $variables, compute everything shown from them with expressions and @functions, and change them with @Set. Nothing needs another message.',
    'Step-by-step solutions: hold the step in $step, list the steps as objects and show steps[$step] with Math for the formula and a short Text, plus "上一步"/"下一步" Buttons. Put hints and the full solution in Accordion or Flashcard so the student can try first.',
    'Exploration: bind a Slider to each parameter, derive the rows for Chart or the coordinates for Svg from those $variables, and state the changing values in Text. Fix the Chart\'s yMin and yMax so the change stays visible.',
    'Draw with Svg whenever a figure helps: geometry, force diagrams, circuits, motion, cells, number lines, apparatus. Use a viewBox about 240 wide, a dozen or so shapes, SvgText labels, a Line with a small Polygon head for an arrow, and @Sin, @Cos, @Rad for angles. Outlines take stroke "ink"; filled areas take a light fill such as "surface", "info" or #DCEBFF, never a dark fill behind other shapes.',
    'Quizzes: a RadioGroup or SegmentedControl in a $variable, a Button that sets $checked, feedback computed from the choice, and Celebration for a correct answer.',
    'Formulas: Text, Markdown and option labels render Markdown, so inline $...$ LaTeX, $\\\\ce{...}$ equations and $\\\\smiles{...}$ structures work there; Math shows a display formula. Double every backslash inside a string.',
    '@ToAssistant("...") sends a short first-person message for the user right away, with the card\'s current values attached — use it for "这一步没看懂", "再出一道题", "检查我的答案". Never repeat the values in the message.',
    '$variable defaults are literals; put derived values in their own named statements. cond ? a : b picks values or components; there is no @If. Use only the listed @functions.',
    '@Random, @Pick and @Shuffle draw anew on every evaluation, so call them inside @Set.',
    'Keep cards compact: Title "md", Text "sm" for body text, Badges for quick facts. Answer a plain factual question with Markdown only.',
  ],
  examples: [
    `Example 1 — a step-by-step solution with a hint and a follow-up button:\n\n${exampleSolution}`,
    `Example 2 — sliders that reshape a graph:\n\n${exampleExplore}`,
    `Example 3 — a figure driven by a slider:\n\n${exampleDrawing}`,
    `Example 4 — a quiz with feedback (formulas in option labels):\n\n${exampleQuiz}`,
  ],
};

const schema = library.toJSONSchema();
const parser = createParser(schema,library.root);
const functionMap = Object.fromEntries(Object.entries(library.functions).map(([name,fn])=>[name,{...schema.functions[name],fn:fn.fn}]));
let cachedSource = null;
let cachedResult = null;
const MAX_SOURCE=64*1024, MAX_STATE=32*1024, MAX_RESULT=512*1024, MAX_NODES=800, MAX_DEPTH=32;
function checkedState(state) {
  if (!state || typeof state!=='object' || Array.isArray(state)) return {};
  if (JSON.stringify(state).length>MAX_STATE || Object.keys(state).length>32) throw Error('交互状态过大');
  return Object.fromEntries(Object.entries(state).filter(([key])=>/^\$[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(key)));
}

// 模型常把 LaTeX 直接写进字符串："$\frac{1}{2}$" 里的 \f 会被当成换页符，\ce、\alpha 会丢掉反斜杠。
// 解析前把字符串里属于 LaTeX 的反斜杠补成两个；\n、\t 这类真正的转义保持不变。
const LATEX_ON_ESCAPE_LETTER=/^(?:n(?:eq|e|u|abla|ot|i|mid|eg|ewline|parallel)|t(?:imes|an|anh|ext|extbf|extit|heta|au|o|op|riangle|frac|ilde|herefore)|r(?:ight|ightarrow|ho|angle|m|ceil|floor|vert|Vert)|b(?:eta|ar|egin|mod|inom|oxed|ot|ecause|ig|igg|f|m|ullet)|f(?:rac|orall|lat|box)|u(?:nderline|parrow|psilon|nderbrace|nderset|nit)|v(?:ec|ee|arepsilon|arphi|ert|dots))(?![A-Za-z])/;
export function repairLatexEscapes(source) {
  if(!source.includes('\\')) return source;
  let out='', inString=false;
  for(let i=0;i<source.length;i++) {
    const c=source[i];
    if(!inString) { if(c==='"') inString=true; out+=c; continue; }
    if(c==='"') { inString=false; out+=c; continue; }
    if(c!=='\\') { out+=c; continue; }
    const next=source[i+1]??'';
    if(next==='\\'||next==='"') { out+=c+next; i++; continue; }
    // \n \t 后面跟着 LaTeX 命令名（\neq、\times、\frac…）时才补反斜杠，否则是普通转义
    if(/[nrtbfuv]/.test(next)) { out+=LATEX_ON_ESCAPE_LETTER.test(source.slice(i+1))?'\\\\':'\\'; continue; }
    out+='\\\\';
  }
  return out;
}

export function render(source,persisted={},event=null) {
  if(typeof source!=='string'||source.length>MAX_SOURCE) throw Error('交互内容过大');
  if(source!==cachedSource) { cachedResult=parser.parse(repairLatexEscapes(source)); cachedSource=source; }
  const parsed=cachedResult;
  if(parsed.meta.incomplete) throw Error('交互内容尚未完整');
  if(parsed.queryStatements.length||parsed.mutationStatements.length) throw Error('此卡片不能自动运行工具');
  if(parsed.meta.statementCount>1200) throw Error('交互内容过于复杂');
  const store=createStore();
  store.initialize(checkedState(parsed.stateDeclarations),checkedState(persisted));
  const errors=[];
  const ctx={getState:name=>store.get(name),resolveRef:()=>null,functions:functionMap,actions:schema.actions,reportError:message=>{if(errors.length<8)errors.push(message);}};
  let bindings=new Map(), actions=new Map(), nodes=0;
  function normalize(value,path='root',depth=0) {
    if(depth>MAX_DEPTH) throw Error('交互内容嵌套过深');
    if(Array.isArray(value)) {if(value.length>2000)throw Error('数据项目过多');return value.map((v,i)=>normalize(v,`${path}/${i}`,depth+1));}
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
      // 新版参数名映射到原生渲染读取的名字；位置不变，所以旧卡片的写法照样能识别
      const type=value.typeName;
      const move=(from,to)=>{ if(from in props){ props[to]=props[from]; if(from!==to) delete props[from]; } };
      if(type==='Card') {
        if(typeof props.children==='string') { props.title=props.children; props.children=Array.isArray(props.variant)?props.variant:[]; delete props.variant; }
      } else if(type==='Box'&&props.direction&&!['row','col'].includes(props.direction)) {
        props.background=props.direction;props.padding=props.gap;delete props.direction;delete props.gap;
      }
      else if(type==='Code') move('code','text');
      else if(type==='Math') move('tex','text');
      else if(type==='List')props.variant=props.timeline||props.marker==='timeline'?'timeline':props.marker==='number'||props.marker==='numbers'?'numbers':props.marker==='none'?'none':'bullets';
      else if(type==='ListItem') {
        const legacy=typeof props.title!=='string';
        props.content=legacy?props.title:[props.title,...(props.description?[props.description]:[]),...(props.children??[])];
        props.label=legacy?props.description:props.marker;
        delete props.title;delete props.description;delete props.children;
      }
      else if(['Input','TextArea','NumberInput'].includes(type)) {
        if(['text','number'].includes(props.placeholder)) { props.inputType=props.placeholder; props.placeholder=props.type; }
        else if(['text','number'].includes(props.type)) props.inputType=props.type;
        else if(props.placeholder==null&&props.type!=null) props.placeholder=props.type;
        delete props.type;
      } else if(type==='SegmentedControl') {
        if(typeof props.block==='string') { props.label=props.block; delete props.block; }
      } else if(type==='Chart') {
        props.xKey=props.x??Object.keys(props.data?.[0]??{})[0]??'x';
        props.series??=Object.keys(props.data?.[0]??{}).filter(k=>k!==props.xKey&&typeof props.data[0][k]==='number');
        if(props.format&&!['number','currency','percent'].includes(props.format)) {props.title??=props.format;props.format='number';}
      } else if(type==='Svg') {
        if(typeof props.children==='string') { const viewBox=props.children; props.children=props.viewBox; props.viewBox=viewBox; props.description=props.width; delete props.width; }
      } else if(type==='SvgText') {
        if(typeof props.text==='number') { const [x,y,t]=[props.text,props.x,props.y]; props.x=x; props.y=y; props.text=t; }
      } else if(type==='Line') {
        // 旧版 Line(x1,y1,x2,y2,fill,stroke,width)
        if(typeof props.strokeWidth==='string') { props.stroke=props.strokeWidth; props.strokeWidth=props.opacity; delete props.opacity; }
        props.fill='none';
      } else if(type==='Path') move('d','data');
      else if(type==='Polyline'||type==='Polygon') move('points','data');
      else if(type==='MapMarker') {
        if(typeof props.label==='number') { const [lat,lon,label]=[props.label,props.lat,props.lng]; props.lat=lat; props.lon=lon; props.label=label; }
        else props.lon=props.lng;
        delete props.lng;
      } else if(type==='Map') {
        if(typeof props.route==='string') { props.title=props.route; delete props.route; }
      } else if(type==='Image') { props.url=props.src; }
      else if(type==='SearchImage') {
        if(typeof props.aspectRatio==='string') { props.alt=props.aspectRatio; delete props.aspectRatio; }
        props.alt??=props.query;
      } else if(type==='Icon') {
        props.size=typeof props.size==='string'?({sm:16,md:24,lg:32,xl:40}[props.size]??24):(props.size??24);
        props.label=props.color; delete props.color;
      } else if(type==='Pressable') {
        if(typeof props.selected==='string') { props.label=props.selected; delete props.selected; }
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
        } else if(step.type==='continue_conversation') effects.push({type:'send',text:String(step.context??step.message??'继续讲讲这张卡片'),state:store.getSnapshot()});
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
