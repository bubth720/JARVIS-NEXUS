/* Pure bounded graph model, shared by the browser and Node tests. No network. */
(function(root){
  'use strict';
  const MAX_NODES=500,MAX_EDGES=2000,MAX_PAGE_BYTES=1048576;
  const kinds=new Set(['DOCUMENT','CONCEPT','PERSON','ENTITY','CATEGORY','EVENT','SOURCE']);
  const string=(v,n)=>typeof v==='string'&&v.length>0&&v.length<=n;
  class Graph {
    constructor(){this.clear();}
    clear(){this.nodes=new Map();this.edges=new Map();this.collection=null;this.nextNode=null;this.nextEdge=null;}
    load(page){
      if(!page||page.format!=='jarvis.nexus.graph/1'||!string(page.collectionId,128)||!Array.isArray(page.nodes)||!Array.isArray(page.edges)||page.nodes.length>250||page.edges.length>1000)throw Error('Page de graphe invalide ou excessive.');
      if(this.collection&&this.collection!==page.collectionId)throw Error('Videz la vue avant de changer de collection.');
      for(const c of [page.nextNode,page.nextEdge])if(c!==null&&!string(c,128))throw Error('Curseur invalide.');
      const nodes=new Map(this.nodes),edges=new Map(this.edges);
      for(const n of page.nodes){
        if(!n||!string(n.id,128)||!string(n.label,256)||!kinds.has(n.kind)||typeof n.category!=='string'||n.category.length>128||(n.documentId!==null&&!string(n.documentId,128))||(n.sourceUri!==null&&!string(n.sourceUri,2048)))throw Error('Nœud invalide.');
        const clean={id:n.id,label:n.label,kind:n.kind,category:n.category,documentId:n.documentId,sourceUri:n.sourceUri};
        if(nodes.has(n.id)&&JSON.stringify(nodes.get(n.id))!==JSON.stringify(clean))throw Error('Nœud contradictoire entre pages.');
        nodes.set(n.id,clean);
      }
      for(const e of page.edges){
        if(!e||!string(e.id,128)||!string(e.from,128)||!string(e.to,128)||e.from===e.to||!string(e.kind,64)||!['EXPLICIT','SUGGESTED'].includes(e.origin)||!Number.isInteger(e.version)||e.version<1)throw Error('Relation invalide.');
        if(!e.evidence||e.evidence.trusted!==false||!Array.isArray(e.evidence.items)||e.evidence.items.length!==1)throw Error('Preuve absente.');
        const item=e.evidence.items[0];
        if(typeof item.text!=='string'||item.text.length>8192||!Array.isArray(item.citations)||item.citations.length!==1)throw Error('Preuve excessive.');
        const c=item.citations[0];
        if(!c||!string(c.id,128)||!string(c.documentId,128)||!string(c.sourceUri,2048)||!string(c.sourceId,128)||![c.sourceSha256,c.textSha256].every(x=>typeof x==='string'&&/^[a-f0-9]{64}$/.test(x))||!Number.isInteger(c.version)||c.version<1||!Number.isInteger(c.ordinal)||c.ordinal<0||!Number.isInteger(c.start)||!Number.isInteger(c.end)||c.start<0||c.end<c.start||c.end-c.start!==item.text.length)throw Error('Citation invalide.');
        edges.set(e.id,{id:e.id,from:e.from,to:e.to,kind:e.kind,origin:e.origin,version:e.version,evidence:{text:item.text,citation:{...c}}});
      }
      if(nodes.size>MAX_NODES||edges.size>MAX_EDGES)throw Error('Limite de vue atteinte : 500 nœuds / 2 000 relations. Videz la vue pour une autre page.');
      this.nodes=nodes;this.edges=edges;this.collection=page.collectionId;this.nextNode=page.nextNode;this.nextEdge=page.nextEdge;
    }
    visible({query='',category='',selected=null,depth=1,limit=180,suggested=true}={}){
      if(!Number.isInteger(limit)||limit<1||limit>MAX_NODES||!Number.isInteger(depth)||depth<1||depth>3)throw Error('Limites invalides.');
      const edges=[...this.edges.values()].filter(e=>suggested||e.origin==='EXPLICIT');
      let reachable=null;
      if(selected&&this.nodes.has(selected)){
        reachable=new Set([selected]);let frontier=new Set([selected]);
        for(let d=0;d<depth;d++){
          const next=new Set();
          for(const e of edges){if(frontier.has(e.from))next.add(e.to);if(frontier.has(e.to))next.add(e.from);}
          for(const id of next)reachable.add(id);frontier=next;
        }
      }
      const q=query.toLocaleLowerCase();
      const nodes=[...this.nodes.values()].filter(n=>(!reachable||reachable.has(n.id))&&(!category||n.category===category)&&(!q||n.label.toLocaleLowerCase().includes(q))).sort((a,b)=>a.id.localeCompare(b.id)).slice(0,limit);
      const ids=new Set(nodes.map(n=>n.id));
      return {nodes,edges:edges.filter(e=>ids.has(e.from)&&ids.has(e.to))};
    }
    positions(nodes){
      const categories=[...new Set(nodes.map(n=>n.category))].sort();
      const counts=new Map(),totals=new Map();for(const n of nodes)totals.set(n.category,(totals.get(n.category)||0)+1);
      return nodes.map((n,i)=>{
        const group=categories.indexOf(n.category),k=counts.get(n.category)||0;counts.set(n.category,k+1);
        const y=1-2*(k+.5)/totals.get(n.category),r=Math.sqrt(1-y*y),a=k*2.399963;
        let x=Math.cos(a)*r,yy=y,z=Math.sin(a)*r;
        if(categories.length>1){const ay=1-2*(group+.5)/categories.length,ar=Math.sqrt(1-ay*ay),aa=group*2.399963;x=x*.35+Math.cos(aa)*ar;yy=yy*.35+ay;z=z*.35+Math.sin(aa)*ar;const norm=Math.hypot(x,yy,z);x/=norm;yy/=norm;z/=norm;}
        return {...n,x,y:yy,z,group};
      });
    }
  }
  const api={Graph,MAX_NODES,MAX_EDGES,MAX_PAGE_BYTES};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.NexusGraph=api;
})(globalThis);
