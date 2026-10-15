(q) => {
 function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.display!='none'&&s.visibility!='hidden';}
 var l=[...document.querySelectorAll('textarea,[contenteditable=true],[contenteditable=""]')].filter(vis);
 var inp=l.sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0];
 if(!inp)return 'noinput';
 inp.focus();
 if(inp.tagName=='TEXTAREA'){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(inp,q);inp.dispatchEvent(new Event('input',{bubbles:true}));}
 else{document.execCommand('selectAll',false,null);document.execCommand('insertText',false,q);}
 return new Promise(res=>setTimeout(()=>{
  var bs=[...document.querySelectorAll('button,[role=button]')].filter(vis);var b=null;
  for(var i=bs.length-1;i>=0;i--){var x=bs[i];var a=((x.getAttribute('aria-label')||'')+' '+(x.getAttribute('data-testid')||'')+' '+(x.getAttribute('class')||'')+' '+(x.id||'')+' '+(x.innerText||'')).toLowerCase();
   if(!x.disabled&&x.getAttribute('aria-disabled')!='true'&&/send|发送|submit|提交/.test(a)){b=x;break;}}
  var how='';
  if(b){b.click();how='click:'+((b.getAttribute('class')||'')+'|'+(b.getAttribute('data-testid')||'')+'|'+(b.getAttribute('aria-label')||'')+'|'+b.id).slice(0,160);}
  else{['keydown','keypress','keyup'].forEach(function(t){inp.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});how='enter';}
  res(how);},700));
}
