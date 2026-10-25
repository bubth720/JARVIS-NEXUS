const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
const fs=require('node:fs');
const http=require('node:http');
(async()=>{
  // The managed browser blocks file:// navigation. Serve only the four static
  // viewer assets over loopback; no document API and no external host access.
  const assets={'/':'index.html','/index.html':'index.html','/style.css':'style.css','/graph.js':'graph.js','/app.js':'app.js'};
  const server=http.createServer((req,res)=>{const asset=assets[req.url];if(!asset){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',asset.endsWith('.js')?'text/javascript':asset.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(path.resolve('viewer',asset)));});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin='http://127.0.0.1:'+server.address().port;
  const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
  const report={engine:await browser.version(),transport:'explicit loopback static server; managed browser disallows file://',contexts:[],networkRequests:[]};
  try{
    for(const mobile of [false,true]){
      const context=await browser.newContext({viewport:mobile?{width:390,height:844}:{width:1440,height:1000},hasTouch:mobile,isMobile:mobile});
      const page=await context.newPage(),errors=[];
      page.on('pageerror',e=>errors.push(e.message));
      page.on('request',r=>{if(!r.url().startsWith(origin+'/'))report.networkRequests.push(r.url());});
      await page.goto(origin+'/');
      await page.getByRole('button',{name:'Explorer un exemple synthétique'}).click();
      await page.waitForFunction(()=>document.getElementById('title').textContent.includes('120 nœuds'));
      assert.equal(await page.locator('#empty').isVisible(),false);
      assert.ok(await page.locator('#nodeList button').count()>0);
      await page.locator('#nodeList button').first().click();
      assert.ok((await page.locator('#detail').innerText()).includes('relation'));
      await page.locator('#reset').click();
      await page.locator('#search').fill('introuvable-xyz');assert.equal(await page.locator('#count').innerText(),'0');
      await page.locator('#search').fill('');
      const before=await page.locator('#atlas').evaluate(c=>c.toDataURL());
      await page.locator('#atlas').focus();await page.keyboard.press('ArrowRight');await page.waitForTimeout(60);
      const after=await page.locator('#atlas').evaluate(c=>c.toDataURL());assert.notEqual(after,before);
      if(mobile){
        await page.locator('#atlas').scrollIntoViewIfNeeded();
        const bounds=await page.locator('#atlas').boundingBox(),x=Math.round(bounds.x+bounds.width/2),y=Math.round(Math.max(80,bounds.y+100));
        const session=await context.newCDPSession(page),old=await page.evaluate(()=>zoom);
        await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:x-20,y,id:1},{x:x+20,y,id:2}]});
        await session.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x-50,y,id:1},{x:x+50,y,id:2}]});
        await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
        assert.notEqual(await page.evaluate(()=>zoom),old);
        await session.detach();
      }
      // Real local JSON import; HTML payload stays text, never creates DOM elements.
      await page.locator('#clear').click();
      const node={id:'x',label:'<img src=x onerror="globalThis.pwned=1">',kind:'DOCUMENT',category:'Test',documentId:'doc',sourceUri:'file:///synthetic-do-not-open.txt'};
      const data={format:'jarvis.nexus.graph/1',collectionId:'xss-test',nodes:[node],edges:[],nextNode:null,nextEdge:null};
      await page.locator('#file').setInputFiles({name:'test.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(data))});
      await page.waitForFunction(()=>document.getElementById('title').textContent.includes('1 nœuds'));
      await page.locator('#nodeList button').first().click();
      assert.equal(await page.locator('#detail img').count(),0);assert.equal(await page.evaluate(()=>globalThis.pwned),undefined);
      assert.equal(await page.locator('#detail a').getAttribute('href'),'file:///synthetic-do-not-open.txt');
      await page.locator('#file').setInputFiles({name:'huge.json',mimeType:'application/json',buffer:Buffer.alloc(1048577,32)});
      await page.waitForFunction(()=>document.getElementById('status').textContent.includes('limite de 1 Mio'));
      assert.ok((await page.locator('#title').innerText()).includes('1 nœuds'));
      assert.deepEqual(errors,[]);
      await page.locator('#demo').click();await page.waitForFunction(()=>document.getElementById('title').textContent.includes('120 nœuds'));
      if(!mobile)await page.screenshot({path:'/tmp/nexus-final-viewer.png',fullPage:true});
      let jvmExport=null;
      if(process.env.NEXUS_GRAPH_PAGE){
        const raw=fs.readFileSync(process.env.NEXUS_GRAPH_PAGE),exported=JSON.parse(raw);
        await page.locator('#clear').click();
        await page.locator('#file').setInputFiles({name:'JVM-SYNTHETIC-atlas.json',mimeType:'application/json',buffer:raw});
        await page.waitForFunction(n=>document.getElementById('title').textContent.includes(n+' nœuds'),exported.nodes.length);
        const fromJvm=await page.evaluate(()=>[...graph.edges.values()].map(e=>e.evidence.citation));
        assert.equal(fromJvm.length,exported.edges.length);
        for(const edge of exported.edges){const expected=edge.evidence.items[0].citations[0];assert.deepEqual(fromJvm.find(c=>c.id===expected.id),expected);}
        await page.getByRole('button',{name:'Fiche Atome',exact:true}).click();
        assert.ok((await page.locator('#detail blockquote').innerText()).includes('Document synthétique mentionnant Atome'));
        jvmExport={scope:'actual Kotlin GraphService/CSV demo, SYNTHETIC corpus; no CORE or Android integration',nodes:exported.nodes.length,edges:exported.edges.length,allCitationFieldsPreserved:true};
      }
      const render=await page.evaluate(()=>{
        graph.clear();const nodes=Array.from({length:500},(_,i)=>({id:'b'+i,label:'Synthetic '+i,kind:'CONCEPT',category:'Category '+i%6,documentId:null,sourceUri:null}));
        const evidence={trusted:false,items:[{text:'test',citations:[{id:'c',documentId:'d',sourceId:'s',sourceUri:'synthetic:test',sourceSha256:'0'.repeat(64),textSha256:'0'.repeat(64),version:1,ordinal:0,start:0,end:4,page:null}]}]};
        for(let part=0;part<2;part++)graph.load({format:'jarvis.nexus.graph/1',collectionId:'render-benchmark',nodes:nodes.slice(part*250,part*250+250),edges:Array.from({length:1000},(_,j)=>{const i=part*1000+j;return {id:'e'+i,from:'b'+i%500,to:'b'+(i%500+1+Math.floor(i/500))%500,kind:'RELATED',origin:i%2?'SUGGESTED':'EXPLICIT',version:1,evidence};}),nextNode:null,nextEdge:null});
        selected=null;$('search').value='';$('category').value='';$('density').max='500';$('density').value='500';update();
        const timings=[];for(let i=0;i<20;i++){const t=performance.now();rotationY+=.01;draw();timings.push(performance.now()-t);}timings.sort((a,b)=>a-b);
        return {syntheticNodes:500,syntheticEdges:2000,drawMedianMs:timings[10],drawP95Ms:timings[18],jsHeapBytes:performance.memory?.usedJSHeapSize??null,scope:'synchronous Canvas2D draw calls, not compositor FPS or Android device memory'};
      });
      report.contexts.push({viewport:mobile?'390x844 touch emulation (not Android hardware)':'1440x1000 desktop',tests:['local JSON demo','node selection','search','keyboard rotation',...(mobile?['CDP pinch touch events']:[]),'inert imported HTML','safe original URI','oversize atomic refusal'],pageErrors:errors,render,jvmExport});
      await context.close();
    }
    assert.deepEqual(report.networkRequests,[]);
    console.log(JSON.stringify(report,null,2));
  }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exit(1);});
