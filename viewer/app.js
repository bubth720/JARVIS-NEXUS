/* Local-file application. All imported strings are rendered with textContent. */
'use strict';
const $=id=>document.getElementById(id),graph=new NexusGraph.Graph(),canvas=$('atlas'),ctx=canvas.getContext('2d');
let selected=null,visible={nodes:[],edges:[]},points=[],rotationX=.12,rotationY=-.3,zoom=1,panX=0,panY=0,frame=false,listLimit=50;
const palette=['#98d8bf','#b2b5e9','#e4bf83','#8ec5de','#d9a5c0','#c4d294'];
const mobile=matchMedia('(max-width:650px)').matches;
if(mobile){$('density').max='250';$('density').value='100';$('densityValue').value='100';}
function status(text){$('status').textContent=text;}
function element(tag,text,className){const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(className)el.className=className;return el;}
function update(){
  visible=graph.visible({query:$('search').value,category:$('category').value,selected,depth:Number($('depth').value),limit:Number($('density').value),suggested:$('suggested').checked});
  $('empty').hidden=graph.nodes.size>0;$('count').textContent=String(visible.nodes.length);
  $('densityValue').value=$('density').value;
  $('dataset').textContent=graph.collection?(graph.collection==='synthetic-demo'?'EXEMPLE SYNTHÉTIQUE':graph.collection):'Aucune collection chargée';
  $('title').textContent=graph.nodes.size?`${graph.nodes.size} nœuds chargés · ${graph.edges.size} relations`:'Un espace pour comprendre';
  const list=$('nodeList');list.replaceChildren();
  for(const n of visible.nodes.slice(0,listLimit)){const li=element('li'),button=element('button',n.label);button.type='button';button.onclick=()=>select(n.id);li.append(button);list.append(li);}
  $('more').hidden=listLimit>=visible.nodes.length;
  detail();invalidate();
}
function select(id){selected=selected===id?null:id;listLimit=50;update();}
function detail(){
  const panel=$('detail');panel.replaceChildren();
  const node=graph.nodes.get(selected);
  if(!node){panel.append(element('div','⌘','detailicon'),element('h2','Chaque lien a une source.'),element('p','Sélectionnez un nœud pour consulter sa fiche et les relations documentées.','intro'));return;}
  panel.append(element('h2',node.label),element('div',node.kind+' / '+(node.category||'Sans catégorie'),'kind'));
  if(node.documentId)panel.append(element('p','Document : '+node.documentId,'hint'));
  if(node.sourceUri){panel.append(element('code',node.sourceUri));if(/^(file|content):\/\//i.test(node.sourceUri)){const a=element('a','Ouvrir l’original local ↗');a.href=node.sourceUri;a.target='_blank';a.rel='noopener noreferrer';const p=element('p');p.append(a);panel.append(p);}}
  const relations=element('div',undefined,'relations');
  const edges=[...graph.edges.values()].filter(e=>(e.from===node.id||e.to===node.id)&&($('suggested').checked||e.origin==='EXPLICIT')).slice(0,100);
  relations.append(element('p',`${edges.length} relations · extrait de la vue chargée`,'hint'));
  for(const e of edges){
    const article=element('article'),other=graph.nodes.get(e.from===node.id?e.to:e.from);
    article.append(element('div',e.origin==='SUGGESTED'?'SUGGÉRÉ · À CONFIRMER':'LIEN EXPLICITE','badge'),element('p',e.kind+' → '+(other?.label||'Nœud dans une autre page')));
    if(other){const b=element('button','Explorer cette relation','subtle');b.onclick=()=>select(other.id);article.append(b);}
    article.append(element('blockquote',e.evidence.text));
    const c=e.evidence.citation;
    article.append(element('p',`Version ${c.version} · ${c.page?'page '+c.page+' · ':''}emplacement ${c.start}–${c.end}`,'hint'),element('code',c.sourceUri),element('p','Citation exportée, à résoudre avec RagContextPort.','hint'));
    relations.append(article);
  }
  panel.append(relations);
}
function invalidate(){if(!frame){frame=true;requestAnimationFrame(()=>{frame=false;draw();});}}
function draw(){
  const box=canvas.getBoundingClientRect(),w=box.width,h=box.height,dpr=Math.min(2,devicePixelRatio||1);
  if(canvas.width!==Math.round(w*dpr)||canvas.height!==Math.round(h*dpr)){canvas.width=Math.round(w*dpr);canvas.height=Math.round(h*dpr);}
  ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,w,h);
  const radius=Math.min(w,h)*.33*zoom,cx=w/2+panX,cy=h/2+panY;
  ctx.strokeStyle='#38515c40';ctx.lineWidth=.5;
  for(const factor of [.36,.7,1]){ctx.beginPath();ctx.ellipse(cx,cy,radius,radius*factor,0,0,Math.PI*2);ctx.stroke();}
  const cosY=Math.cos(rotationY),sinY=Math.sin(rotationY),cosX=Math.cos(rotationX),sinX=Math.sin(rotationX);
  points=graph.positions(visible.nodes).map(n=>{const x=n.x*cosY+n.z*sinY,z=n.z*cosY-n.x*sinY,y=n.y*cosX-z*sinX,zz=z*cosX+n.y*sinX,p=2.8/(2.8-zz);return {...n,px:cx+x*radius*p,py:cy+y*radius*p,depth:zz,scale:p};});
  const byId=new Map(points.map(p=>[p.id,p]));
  for(const e of visible.edges){const a=byId.get(e.from),b=byId.get(e.to);if(!a||!b)continue;const active=e.from===selected||e.to===selected;ctx.strokeStyle=active?'#a1d5c798':e.origin==='SUGGESTED'?'#a4a0c12b':'#87bac338';ctx.lineWidth=active?1.2:.6;ctx.setLineDash(e.origin==='SUGGESTED'?[3,5]:[]);ctx.beginPath();ctx.moveTo(a.px,a.py);ctx.lineTo(b.px,b.py);ctx.stroke();}
  ctx.setLineDash([]);
  let labels=0;const occupied=[];
  for(const p of [...points].sort((a,b)=>a.depth-b.depth)){
    const active=p.id===selected,size=(active?6:3)*p.scale,color=palette[p.group%palette.length];
    ctx.globalAlpha=.45+(p.depth+1)*.275;
    if(active){ctx.fillStyle=color+'24';ctx.beginPath();ctx.arc(p.px,p.py,17,0,Math.PI*2);ctx.fill();}
    ctx.fillStyle=color;ctx.beginPath();ctx.arc(p.px,p.py,size,0,Math.PI*2);ctx.fill();
    if(active||(p.depth>.4&&labels<24)){
      ctx.font=`${active?'600':'400'} ${active?12:10}px system-ui`;
      const label=p.label.length>30?p.label.slice(0,29)+'…':p.label,box={x:p.px+9,y:p.py-9,w:ctx.measureText(label).width,h:15};
      if(active||!occupied.some(b=>box.x<b.x+b.w+5&&box.x+box.w+5>b.x&&box.y<b.y+b.h+3&&box.y+box.h+3>b.y)){
        ctx.fillStyle=active?'#e2f2ed':'#adc1cb';ctx.fillText(label,p.px+9,p.py+3);occupied.push(box);labels++;
      }
    }
  }
  ctx.globalAlpha=1;
}
async function openFile(file){
  if(!file)return;
  try{
    if(file.size>NexusGraph.MAX_PAGE_BYTES)throw Error('Page refusée : limite de 1 Mio. Réduisez la taille de l’export.');
    const page=JSON.parse(await file.text());graph.load(page);selected=null;listLimit=50;
    const chosen=$('category').value;$('category').replaceChildren(element('option','Toutes'));;$('category').firstChild.value='';
    for(const c of [...new Set([...graph.nodes.values()].map(n=>n.category))].sort()){const option=element('option',c||'Sans catégorie');option.value=c;$('category').append(option);}
    $('category').value=chosen;
    status(`Page ajoutée · ${graph.nodes.size}/500 nœuds · suite nœuds : ${graph.nextNode||'fin'} · suite liens : ${graph.nextEdge||'fin'}`);update();
  }catch(error){status(error.message||'Fichier refusé.');}
  finally{$('file').value='';}
}
$('file').onchange=e=>openFile(e.target.files[0]);
for(const id of ['search','category','depth','density','suggested'])$(id).addEventListener('input',()=>{listLimit=50;update();});
$('more').onclick=()=>{listLimit+=50;update();};
function reset(){rotationX=.12;rotationY=-.3;zoom=1;panX=0;panY=0;selected=null;update();}
$('reset').onclick=reset;
$('clear').onclick=()=>{graph.clear();$('search').value='';$('category').replaceChildren(element('option','Toutes'));$('category').firstChild.value='';status('Vue vidée · aucun original modifié');reset();};
const pointers=new Map();let gesture=null,moved=false;
canvas.onpointerdown=e=>{canvas.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});moved=false;gesture=null;};
canvas.onpointermove=e=>{
  const previous=pointers.get(e.pointerId);if(!previous)return;
  const dx=e.clientX-previous.x,dy=e.clientY-previous.y;
  if(Math.abs(dx)+Math.abs(dy)>2)moved=true;
  pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
  if(pointers.size===2){const [a,b]=[...pointers.values()],distance=Math.hypot(a.x-b.x,a.y-b.y),center={x:(a.x+b.x)/2,y:(a.y+b.y)/2};if(gesture){zoom=Math.max(.35,Math.min(3,zoom*distance/Math.max(1,gesture.distance)));panX+=center.x-gesture.center.x;panY+=center.y-gesture.center.y;}gesture={distance,center};moved=true;}
  else if(e.shiftKey){panX+=dx;panY+=dy;}else{rotationY+=dx*.006;rotationX+=dy*.006;}
  invalidate();
};
canvas.onpointerup=e=>{if(!moved&&pointers.size===1){const box=canvas.getBoundingClientRect(),x=e.clientX-box.left,y=e.clientY-box.top;const hit=[...points].filter(p=>Math.hypot(p.px-x,p.py-y)<14).sort((a,b)=>b.depth-a.depth)[0];if(hit)select(hit.id);}pointers.delete(e.pointerId);gesture=null;};
canvas.onpointercancel=e=>{pointers.delete(e.pointerId);gesture=null;};
canvas.addEventListener('wheel',e=>{e.preventDefault();zoom=Math.max(.35,Math.min(3,zoom*Math.exp(-e.deltaY*.001)));invalidate();},{passive:false});
canvas.onkeydown=e=>{if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','+','-','Escape'].includes(e.key)){e.preventDefault();if(e.key==='ArrowLeft')rotationY-=.1;if(e.key==='ArrowRight')rotationY+=.1;if(e.key==='ArrowUp')rotationX-=.1;if(e.key==='ArrowDown')rotationX+=.1;if(e.key==='+')zoom=Math.min(3,zoom*1.1);if(e.key==='-')zoom=Math.max(.35,zoom/1.1);if(e.key==='Escape')selected=null;update();}};
new ResizeObserver(invalidate).observe(canvas);
$('demo').onclick=()=>{
  graph.clear();const nodes=[],edges=[],categories=['Sciences','Histoire','Technologies','Société','Nature','Arts'];
  for(let i=0;i<120;i++)nodes.push({id:'demo-'+String(i).padStart(3,'0'),kind:i%4===0?'DOCUMENT':'CONCEPT',label:categories[i%6]+' · '+['Méthodes','Observations','Références','Perspectives'][i%4]+' '+(i+1),category:categories[i%6],documentId:i%4===0?'synthetic-document-'+i:null,sourceUri:i%4===0?'demo:synthetic-'+i:null});
  for(let i=1;i<120;i++){const text='Extrait synthétique : relation illustrative, sans valeur documentaire.';edges.push({id:'edge-'+i,from:nodes[i].id,to:nodes[Math.max(0,i-6)].id,kind:'RELATED_TO',origin:i%3===0?'SUGGESTED':'EXPLICIT',version:1,evidence:{trusted:false,items:[{text,citations:[{id:'synthetic-citation-'+i,documentId:'synthetic-document-'+i,version:1,ordinal:0,sourceId:'synthetic-source',sourceUri:'demo:synthetic-'+i,sourceSha256:'0'.repeat(64),textSha256:'0'.repeat(64),page:null,start:0,end:text.length}]}]}});}
  const data={format:'jarvis.nexus.graph/1',collectionId:'synthetic-demo',nodes,edges,nextNode:null,nextEdge:null};
  openFile(new File([JSON.stringify(data)],'synthetic-demo.json',{type:'application/json'}));
};
update();
