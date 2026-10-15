async (q) => {
 const sleep=ms=>new Promise(r=>setTimeout(r,ms));
 function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.display!='none'&&s.visibility!='hidden';}
 var l=[...document.querySelectorAll('textarea,[contenteditable=true],[contenteditable=""]')].filter(vis);
 var inp=l.sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0];
 if(!inp)return 'noinput';
 inp.focus();
 function txt(){return inp.tagName=='TEXTAREA'?inp.value:(inp.innerText||'');}
 if(inp.tagName=='TEXTAREA'){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(inp,q);inp.dispatchEvent(new Event('input',{bubbles:true}));}
 else{document.execCommand('selectAll',false,null);document.execCommand('insertText',false,q);}
 await sleep(400);
 var how=[];
 function sendBtn(){var bs=[...document.querySelectorAll('button,[role=button]')].filter(vis);var ir=inp.getBoundingClientRect();
  var c=bs.filter(x=>{var a=((x.getAttribute('aria-label')||'')+' '+(x.getAttribute('data-testid')||'')+' '+(x.getAttribute('class')||'')+' '+(x.id||'')+' '+(x.innerText||'')).toLowerCase();return /send|发送|submit|提交/.test(a);});
  c=c.filter(x=>!x.disabled&&x.getAttribute('aria-disabled')!='true');return c[c.length-1]||null;}
 var b=sendBtn();
 if(!b&&inp.tagName!='TEXTAREA'){ // editor did not take execCommand: try a paste event
   try{var dt=new DataTransfer();dt.setData('text/plain',q);document.execCommand('selectAll',false,null);inp.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));how.push('paste');}catch(e){how.push('pasteerr');}
   await sleep(500);b=sendBtn();}
 for(var i=0;i<6&&!b;i++){await sleep(400);b=sendBtn();}
 if(b){b.click();how.push('click:'+((b.getAttribute('aria-label')||'')+'|'+(b.getAttribute('data-testid')||'')+'|'+(b.getAttribute('class')||'')).slice(0,100));}
 else{['keydown','keypress','keyup'].forEach(function(t){inp.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});how.push('enter');}
 return how.join(',')+' txt='+txt().slice(0,30);
}
