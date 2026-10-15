() => {
 function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.display!='none'&&s.visibility!='hidden';}
 function d(e){var r=e.getBoundingClientRect();var c=e.getAttribute('class')||'';return {tag:e.tagName,id:e.id,cls:c.slice(0,140),
   ph:e.getAttribute('placeholder')||e.getAttribute('data-placeholder')||'',tid:e.getAttribute('data-testid')||'',aria:e.getAttribute('aria-label')||'',txt:(e.innerText||'').trim().slice(0,40),
   x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height),dis:!!e.disabled||e.getAttribute('aria-disabled')}}
 var inputs=[...document.querySelectorAll('textarea,input[type=text],input:not([type]),[contenteditable=true],[contenteditable=""]')].filter(vis).map(d);
 var buttons=[...document.querySelectorAll('button,[role=button]')].filter(vis).map(d).slice(-50);
 var links=[...document.querySelectorAll('a')].filter(vis).map(d).filter(x=>/登录|注册|log|sign/i.test(x.txt+x.aria)).slice(0,10);
 var md=[...document.querySelectorAll('[class*=markdown],[class*=Markdown],[class*=message],[data-testid*=message],[class*=answer],[class*=assistant],[class*=response]')].filter(vis).map(e=>{var o=d(e);o.txt=(e.innerText||'').trim().slice(0,80);return o;}).slice(-25);
 var ls={};try{for(var i=0;i<localStorage.length&&i<60;i++){var k=localStorage.key(i);ls[k]=(localStorage.getItem(k)||'').length;}}catch(e){}
 return {url:location.href,title:document.title,inputs,buttons,links,md,text:(document.body&&document.body.innerText||'').slice(0,2500),ls};
}
